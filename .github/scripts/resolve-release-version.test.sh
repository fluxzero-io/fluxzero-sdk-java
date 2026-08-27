#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
resolver="$script_dir/resolve-release-version.sh"

assert_version() {
  local expected="$1"
  local branch="$2"
  local requested="$3"
  local major="$4"
  local tags="${5:-}"
  local actual

  actual="$(AVAILABLE_RELEASE_TAGS="$tags" "$resolver" "$branch" "$requested" "$major")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected '$expected' but got '$actual' for $branch / $requested / $major" >&2
    exit 1
  fi
}

assert_rejected() {
  local branch="$1"
  local requested="$2"
  local major="$3"

  if AVAILABLE_RELEASE_TAGS="1.244.0" "$resolver" "$branch" "$requested" "$major" >/dev/null 2>&1; then
    echo "Expected rejection for $branch / $requested / $major" >&2
    exit 1
  fi
}

assert_version "1.245.0" main "" 1 $'1.243.0\n1.244.0\n2.0.0-M1'
assert_version "1.244.1" main "1.244.1" 1
assert_version "2.0.0" main "" 2 $'1.244.0\n2.0.0-M1\n2.0.0-RC1'
assert_version "2.1.0" main "" 2 $'2.0.0\n2.0.1\n2.0.0-RC1'
assert_version "2.0.0-M1" next/2.0 "2.0.0-M1" 2
assert_version "2.0.0-RC2" next/2.0 "2.0.0-RC2" 2
assert_version "1.247.1" 1.x "1.247.1" 1

assert_rejected next/2.0 "" 2
assert_rejected next/2.0 "2.0.0" 2
assert_rejected next/2.0 "2.1.0-M1" 2
assert_rejected next/2.0 "2.0.0-M1" 1
assert_rejected 1.x "1.248.0" 1
assert_rejected 1.x "2.0.1" 1
assert_rejected main "2.0.0-M1" 2
assert_rejected feature/example "2.0.0-M1" 2

echo "Release version policy passed"
