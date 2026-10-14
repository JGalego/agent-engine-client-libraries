#!/usr/bin/env bash
# Publish one JavaScript SDK package to npm. The caller must have already run
# npm ci and built this package (and its workspace dependencies). Sequencing
# across packages is the caller's job (GHA needs).
#
# Required:
#   --name NAME            npm package name (e.g. @mongodb-js/agent-engine-sdk)
#   --directory DIR        directory under javascript/packages/
#
# Optional:
#   --packages-root PATH   packages tree (default: cwd)
#   DRY_RUN                "true" packs and validates without uploading
#
# The dist-tag is always explicit because npm defaults to `latest`: a
# pre-release goes to `next`, and a stable version takes `latest` only when it
# is at least the current `latest`, otherwise `release-v<MAJOR>.<MINOR>`.
# (A bare `v<MAJOR>.<MINOR>` is rejected: npm forbids tags that parse as a
# semver range.)
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

NAME=""
DIRECTORY=""
PACKAGES_ROOT="."
while [ "$#" -gt 0 ]; do
  case "$1" in
    --name) NAME=${2:?}; shift 2 ;;
    --directory) DIRECTORY=${2:?}; shift 2 ;;
    --packages-root) PACKAGES_ROOT=${2:?}; shift 2 ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "$NAME" ] || die "--name is required"
[ -n "$DIRECTORY" ] || die "--directory is required"

PACKAGES_ROOT="$(cd "$PACKAGES_ROOT" && pwd)"
manifest="$PACKAGES_ROOT/javascript/packages/$DIRECTORY/package.json"
[ -f "$manifest" ] || die "missing $manifest"

version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$manifest")"
SEMVER_PATTERN='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-alpha\.(0|[1-9][0-9]*))?$'
[[ "$version" =~ $SEMVER_PATTERN ]] \
  || die "$manifest has an invalid version: $version"

# The release workflow runs on every push to main, so most runs see a version
# that is already published; skip it so the run and its dependents succeed.
view_err="$(mktemp)"
if published="$(npm view "${NAME}@${version}" version 2>"$view_err")"; then
  rm -f "$view_err"
  if [ "$published" = "$version" ]; then
    echo "skip ${NAME}@${version} (already on npm)"
    exit 0
  fi
elif grep -q E404 "$view_err"; then
  rm -f "$view_err"
else
  cat "$view_err" >&2
  rm -f "$view_err"
  die "could not check whether ${NAME}@${version} is already on npm"
fi

DRY_RUN="${DRY_RUN:-false}"
if [ "$DRY_RUN" = "true" ]; then
  echo "dry-run ${NAME}@${version} (validates packaging; does not validate upload authorization)"
else
  echo "publish ${NAME}@${version}"
fi
original="$(mktemp)"
trap 'cp "$original" "$manifest"; rm -f "$original"' EXIT
cp "$manifest" "$original"
python3 - "$manifest" <<'PY'
import json, sys

path = sys.argv[1]
data = json.load(open(path, encoding="utf-8"))
data.pop("private", None)
scripts = data.get("scripts")
if isinstance(scripts, dict):
    scripts.pop("prepare", None)
    data["scripts"] = scripts
with open(path, "w", encoding="utf-8") as fh:
    json.dump(data, fh, indent=2)
    fh.write("\n")
PY

# Semver precedence, duplicated from the monorepo's scripts/release_versions.py
# because only this packages tree is synced downstream.
version_at_least() {
  python3 - "$1" "$2" <<'PY'
import re, sys

def key(version):
    match = re.fullmatch(
        r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
        r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?",
        version,
    )
    if not match:
        print(f"ERROR: not a semver version: {version}", file=sys.stderr)
        sys.exit(2)
    core = tuple(int(part) for part in match.groups()[:3])
    if match.group(4) is None:
        return core, (1,)
    ids = tuple((0, int(i), "") if i.isdigit() else (1, 0, i) for i in match.group(4).split("."))
    return core, (0, ids)

sys.exit(0 if key(sys.argv[1]) >= key(sys.argv[2]) else 1)
PY
}

resolve_dist_tag() {
  case "$version" in
    *-*)
      echo next
      return 0
      ;;
  esac
  local tags err current
  err="$(mktemp)"
  if ! tags="$(npm view "$NAME" dist-tags --json 2>"$err")"; then
    if grep -q E404 "$err"; then
      rm -f "$err"
      echo latest
      return 0
    fi
    cat "$err" >&2
    rm -f "$err"
    die "could not read npm dist-tags for $NAME"
  fi
  rm -f "$err"
  current="$(printf '%s' "$tags" | python3 -c 'import json,sys; t=sys.stdin.read().strip(); print((json.loads(t) or {}).get("latest") or "" if t else "")')"
  local at_least=0
  [ -z "$current" ] || version_at_least "$version" "$current" || at_least=$?
  case "$at_least" in
    0) echo latest ;;
    1) echo "release-v$(printf '%s' "$version" | cut -d. -f1,2)" ;;
    *) die "cannot compare ${version} with npm latest ${current}" ;;
  esac
}

(
  cd "$PACKAGES_ROOT/javascript/packages/$DIRECTORY"
  dist_tag="$(resolve_dist_tag)"
  echo "npm dist-tag for ${NAME}@${version}: ${dist_tag}"
  publish_args=(--ignore-scripts --access public --tag "$dist_tag")
  if [ "$DRY_RUN" = "true" ]; then
    publish_args+=(--dry-run)
  fi
  npm publish "${publish_args[@]}"
)
