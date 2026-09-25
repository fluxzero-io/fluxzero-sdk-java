#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
resolver="$script_dir/resolve-release-publication.sh"

assert_publication() {
  local expected="$1"
  shift
  local actual
  actual="$(AVAILABLE_RELEASE_TAGS=$'1.292.0\n2.0.0\n2.1.2' bash "$resolver" "$@")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Unexpected publication for %s:\n%s\n' "$*" "$actual" >&2
    exit 1
  fi
}
assert_rejected() {
  if bash "$resolver" "$@" >/dev/null 2>&1; then
    echo "Expected rejection for $*" >&2
    exit 1
  fi
}
maintenance=$'version=1.292.1\nprerelease=false\njavadoc_destination=javadoc/1.x\npackage_channel_tag=1.x\nmake_latest=false\nnotify_site=false'
assert_publication "$maintenance" 1.x 1.292.1 1
assert_publication "$maintenance" 1.x 1.292.1 1 1.292.1
assert_publication "$maintenance" 1.x '' 1 1.292.1
assert_publication $'version=2.1.2\nprerelease=false\njavadoc_destination=javadoc\npackage_channel_tag=latest\nmake_latest=true\nnotify_site=true' main '' 2 2.1.2
assert_publication $'version=2.0.0-RC1\nprerelease=true\njavadoc_destination=javadoc/2.0.0-RC1\npackage_channel_tag=2.0-prerelease\nmake_latest=false\nnotify_site=false' next/2.0 2.0.0-RC1 2
assert_rejected 1.x '' 1
assert_rejected 1.x 2.1.3 1
assert_rejected 1.x 1.293.0 1
assert_rejected 1.x 1.292.1 2
assert_rejected 1.x '' 1 2.1.2
assert_rejected 1.x 1.292.1 1 2.1.2
assert_rejected 1.x 1.292.2 1 1.292.1
assert_rejected main '' 2 1.292.1
assert_rejected feature/example 1.292.1 1
printf 'Release publication policy passed\n'
