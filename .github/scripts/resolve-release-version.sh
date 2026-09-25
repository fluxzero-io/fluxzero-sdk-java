#!/usr/bin/env bash

set -euo pipefail

branch="${1:?Usage: resolve-release-version.sh <branch> [requested-version] <release-major>}"
requested_version="${2:-}"
release_major="${3:?Usage: resolve-release-version.sh <branch> [requested-version] <release-major>}"

if [[ ! "$release_major" =~ ^[1-9][0-9]*$ ]]; then
  echo "Invalid release major '$release_major'" >&2
  exit 1
fi

stable_version() {
  if [[ ! "$1" =~ ^${release_major}\.[0-9]+\.[0-9]+$ ]]; then
    echo "Version '$1' must be a stable ${release_major}.x version on main" >&2
    exit 1
  fi
  printf '%s\n' "$1"
}

next_stable_version() {
  python3 "$(dirname -- "${BASH_SOURCE[0]}")/next-stable-version.py" "$release_major"
}

case "$branch" in
  main)
    if [[ -n "$requested_version" ]]; then
      stable_version "$requested_version"
    else
      next_stable_version
    fi
    ;;
  next/2.0)
    if [[ "$release_major" != "2" ]]; then
      echo "Branch next/2.0 must declare release major 2" >&2
      exit 1
    fi
    # Keep historical spellings accepted for existing release reruns. New RCs use rc.<number>.
    if [[ ! "$requested_version" =~ ^2\.0\.0-((M|RC)[1-9][0-9]*|rc\.[1-9][0-9]*)$ ]]; then
      echo "Branch next/2.0 requires an explicit 2.0.0-Mn, legacy 2.0.0-RCn or 2.0.0-rc.n version" >&2
      exit 1
    fi
    printf '%s\n' "$requested_version"
    ;;
  1.x)
    if [[ "$release_major" != "1" ]]; then
      echo "Branch 1.x must declare release major 1" >&2
      exit 1
    fi
    if [[ ! "$requested_version" =~ ^1\.[0-9]+\.[1-9][0-9]*$ ]]; then
      echo "Branch 1.x requires an explicit 1.x patch version" >&2
      exit 1
    fi
    printf '%s\n' "$requested_version"
    ;;
  *)
    echo "Releases are not allowed from branch '$branch'" >&2
    exit 1
    ;;
esac
