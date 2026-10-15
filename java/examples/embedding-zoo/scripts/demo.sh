#!/usr/bin/env bash
# Runs the Embedding Zoo CLI from a checkout. Argument defaults to demo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/../.."
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g -XX:-UseSuperWord}"
exec mvn -pl examples/embedding-zoo exec:java -Dexec.args="${*:-demo}"
