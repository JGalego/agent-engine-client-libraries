#!/usr/bin/env bash
# Build one Python SDK package's distributions for the PyPI publish action.
# Sequencing across packages is the caller's job (GHA needs).
#
# Required:
#   --name DIST            distribution name (e.g. agent-engine-sdk)
#   --directory DIR        directory under python/packages/
#
# Optional:
#   --packages-root PATH   packages tree (default: cwd)
#   --out-dir DIR          output directory (default: dist)
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

NAME=""
DIRECTORY=""
PACKAGES_ROOT="."
OUT_DIR="dist"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --name) NAME=${2:?}; shift 2 ;;
    --directory) DIRECTORY=${2:?}; shift 2 ;;
    --packages-root) PACKAGES_ROOT=${2:?}; shift 2 ;;
    --out-dir) OUT_DIR=${2:?}; shift 2 ;;
    -h|--help)
      sed -n '2,11p' "$0"
      exit 0
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "$NAME" ] || die "--name is required"
[ -n "$DIRECTORY" ] || die "--directory is required"

PACKAGES_ROOT="$(cd "$PACKAGES_ROOT" && pwd)"
python_root="$PACKAGES_ROOT/python"
pyproject="$python_root/packages/$DIRECTORY/pyproject.toml"
[ -f "$pyproject" ] || die "missing $pyproject"

mkdir -p "$OUT_DIR"
dist="$(cd "$OUT_DIR" && pwd)"

echo "build ${NAME} into ${dist}"
(
  cd "$python_root"
  uv build --package "$NAME" --out-dir "$dist"
)
shopt -s nullglob
artifacts=( "$dist"/* )
[ "${#artifacts[@]}" -gt 0 ] || die "uv build produced no artifacts for $NAME"
