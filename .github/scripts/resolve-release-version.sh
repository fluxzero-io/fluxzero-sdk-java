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
  local latest_minor=-1
  local tag minor

  while IFS= read -r tag; do
    if [[ "$tag" =~ ^${release_major}\.([0-9]+)\.([0-9]+)$ ]]; then
      minor="${BASH_REMATCH[1]}"
      if (( 10#$minor > latest_minor )); then
        latest_minor=$((10#$minor))
      fi
    fi
  done < <(if [[ -n "${AVAILABLE_RELEASE_TAGS:-}" ]]; then
             printf '%s\n' "$AVAILABLE_RELEASE_TAGS"
           else
             git tag --list
           fi)

  if (( latest_minor < 0 )); then
    printf '%s.0.0\n' "$release_major"
  else
    printf '%s.%s.0\n' "$release_major" "$((latest_minor + 1))"
  fi
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
    if [[ ! "$requested_version" =~ ^2\.0\.0-(M|RC)[1-9][0-9]*$ ]]; then
      echo "Branch next/2.0 requires an explicit 2.0.0-Mn or 2.0.0-RCn version" >&2
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
