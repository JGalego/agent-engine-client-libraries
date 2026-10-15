#!/usr/bin/env bash
# Package a built Java example as a directory the Agent Engine CLI can build and deploy.
#
# Usage:
#   package-java-agent.sh <example-dir> [--out <dir>] [--include <path>[:<dest>]]... [--prune <jar>:<entry>[,<entry>...]]...
#
# Run `mvn package` on the example first. The output holds a Python shim that starts the JVM, the
# agent's jars, its agent.yaml, and a platform agent.yaml. Extra platform settings (network egress,
# sandboxes, features, version) come from a platform.yaml in the example; without one the agent gets
# deny-all egress. --include copies a file or directory into the package, to <dest> (default: its
# basename) relative to the package root. --prune deletes zip entries (shell patterns) from a jar in
# the package, to stay under the platform's 50 MiB source limit: native libraries for other platforms
# are the usual weight.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHIM="$ROOT/java/shim"
EXAMPLE=""
OUT=""
INCLUDES=()
PRUNES=()

usage() {
  sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out)     OUT="${2:?--out needs a value}"; shift 2 ;;
    --include) INCLUDES+=("${2:?--include needs a value}"); shift 2 ;;
    --prune)   PRUNES+=("${2:?--prune needs a value}"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*)        printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    *)         [ -z "$EXAMPLE" ] || die "only one example directory"; EXAMPLE="$1"; shift ;;
  esac
done

[ -n "$EXAMPLE" ] || { usage >&2; exit 2; }
EXAMPLE="$(cd "$EXAMPLE" && pwd)"
OUT="${OUT:-$EXAMPLE/target/platform}"

command -v uv >/dev/null || die "uv is required to write the lock file (https://docs.astral.sh/uv/)"
[ -f "$EXAMPLE/agent.yaml" ] || die "$EXAMPLE has no agent.yaml"
[ -d "$EXAMPLE/target/lib" ] || die "$EXAMPLE/target/lib is missing; run 'mvn package' on the example first"
JAR="$(ls "$EXAMPLE"/target/*.jar 2>/dev/null | grep -v -e '-sources' -e '-javadoc' | head -n 1 || true)"
[ -n "$JAR" ] || die "no agent jar in $EXAMPLE/target; run 'mvn package' first"

yaml_value() { sed -n "s/^$1:[[:space:]]*//p" "$EXAMPLE/agent.yaml" | head -n 1 | tr -d "\"'"; }
NAME="$(yaml_value name)"
[ -n "$NAME" ] || die "agent.yaml has no name"
SAFE_NAME="$(printf '%s' "$NAME" | tr -c 'A-Za-z0-9\n' '-' | sed 's/^-*//; s/-*$//' | tr 'A-Z' 'a-z')"

rm -rf "$OUT"
mkdir -p "$OUT/java/lib"
cp -R "$SHIM/java_shim" "$OUT/java_shim"
find "$OUT/java_shim" -name __pycache__ -prune -exec rm -rf {} +
sed "s/@NAME@/$SAFE_NAME/" "$SHIM/pyproject.toml" > "$OUT/pyproject.toml"
cp "$EXAMPLE"/target/lib/*.jar "$JAR" "$OUT/java/lib/"
cp "$EXAMPLE/agent.yaml" "$OUT/java/agent.yaml"

for include in ${INCLUDES[@]+"${INCLUDES[@]}"}; do
  src="${include%%:*}"
  dest="${include#*:}"
  [ "$dest" != "$include" ] || dest="$(basename "$src")"
  [ -e "$src" ] || die "--include $src does not exist"
  mkdir -p "$OUT/$(dirname "$dest")"
  cp -R "$src" "$OUT/$dest"
done

for prune in ${PRUNES[@]+"${PRUNES[@]}"}; do
  command -v zip >/dev/null || die "zip is required for --prune"
  jar_name="${prune%%:*}"
  [ "$jar_name" != "$prune" ] || die "--prune needs <jar>:<entry>[,<entry>...]"
  IFS=',' read -r -a entries <<< "${prune#*:}"
  for jar in "$OUT"/java/lib/$jar_name; do
    [ -f "$jar" ] || die "--prune: no jar matches $jar_name"
    zip -q -d "$jar" "${entries[@]}" >/dev/null || true
  done
done

{
  printf 'name: %s\n' "$SAFE_NAME"
  printf 'entrypoint: java_shim:app\n'
  printf 'framework: langgraph\n'
  if [ -f "$EXAMPLE/platform.yaml" ]; then
    cat "$EXAMPLE/platform.yaml"
    grep -q '^version:' "$EXAMPLE/platform.yaml" || printf 'version: 0.1.0\n'
    grep -q '^network:' "$EXAMPLE/platform.yaml" || printf 'network:\n  egress_mode: deny_all\n'
  else
    printf 'version: 0.1.0\nnetwork:\n  egress_mode: deny_all\n'
  fi
} > "$OUT/agent.yaml"

(cd "$OUT" && uv lock --quiet)

printf 'Packaged %s in %s\n' "$NAME" "$OUT"
LIMIT=$((50 * 1024 * 1024))
SIZE="$(tar -czf - -C "$OUT" . | wc -c)"
printf 'Compressed size: %s MiB (the platform accepts at most 50)\n' "$((SIZE / 1024 / 1024))"
[ "$SIZE" -le "$LIMIT" ] || printf 'warning: over the platform'"'"'s source limit; the build will be refused\n' >&2
printf 'Next, from that directory:\n'
printf '  agentengine init && agentengine deploy --auto\n'
