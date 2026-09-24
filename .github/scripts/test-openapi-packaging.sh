#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$root/.github/qualification/openapi-packaging"
# Run after SDK install. Arguments (e.g. -Dmaven.repo.local=...) are forwarded to Maven.
for variant in valid conflict operation; do
  for packaging in boot shade; do
    "$root/mvnw" -B -f "$fixture/pom.xml" "-P$packaging" "-Dcontract.variant=$variant" "$@" clean package
    count=1
    [[ "$packaging" != boot ]] || count=2
    "${JAVA_HOME:?Set JAVA_HOME}/bin/java" -jar "$fixture/application/target/openapi-probe.jar" "$variant" "$count"
  done
done
