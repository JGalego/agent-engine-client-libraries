#!/usr/bin/env bash
# Build the Java SDK from source and install it into the local Maven repository.
#
# Usage:
#   install-java-sdk.sh [--branch <ref>] [--repo <url>] [--source <dir>]
#
# Run it from a checkout, or pipe it from the raw GitHub URL of the branch you
# want. GitHub source archives of this repository are empty (see .gitattributes),
# so the script clones with git.
set -euo pipefail

REPO="https://github.com/JGalego/agent-engine-client-libraries.git"
BRANCH="main"
SOURCE=""

usage() {
  sed -n '2,10p' "${BASH_SOURCE[0]:-$0}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    -b|--branch) BRANCH="${2:?--branch needs a value}"; shift 2 ;;
    --repo)      REPO="${2:?--repo needs a value}"; shift 2 ;;
    --source)    SOURCE="${2:?--source needs a value}"; shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null || die "git is required"
command -v mvn >/dev/null || die "Maven 3.8+ is required"
command -v java >/dev/null || die "a JDK, version 17 or newer, is required"

JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')"
[ "${JAVA_MAJOR:-0}" -ge 17 ] || die "JDK 17 or newer is required (found ${JAVA_MAJOR:-unknown})"

if [ -z "$SOURCE" ]; then
  SOURCE="$(mktemp -d)"
  trap 'rm -rf "$SOURCE"' EXIT
  printf 'Cloning %s (%s)\n' "$REPO" "$BRANCH"
  git clone --quiet --depth 1 --branch "$BRANCH" "$REPO" "$SOURCE"
fi

[ -f "$SOURCE/java/pom.xml" ] || die "no java/pom.xml under $SOURCE"

printf 'Building and installing the Java SDK\n'
mvn --batch-mode --quiet --file "$SOURCE/java/pom.xml" install -DskipTests

VERSION="$(mvn --batch-mode --file "$SOURCE/java/pom.xml" help:evaluate -Dexpression=project.version -q -DforceStdout | sed 's/\x1b\[[0-9;]*m//g')"

cat <<MSG

Installed com.mongodb.agentengine:*:$VERSION into ~/.m2/repository.
Add it to your pom.xml:

  <dependency>
    <groupId>com.mongodb.agentengine</groupId>
    <artifactId>agent-engine-sdk-memory</artifactId>
    <version>$VERSION</version>
  </dependency>
MSG
