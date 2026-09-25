#!/usr/bin/env bash
set -euo pipefail

branch="${1:?Usage: resolve-release-publication.sh <branch> <requested-version> <release-major> [existing-tag]}"
requested="${2:-}"
major="${3:?Missing release major}"
existing="${4:-}"
resolver="$(dirname -- "${BASH_SOURCE[0]}")/resolve-release-version.sh"

# A reserved tag is still subject to the branch policy on every rerun.
if [[ -n "$existing" ]]; then
  if [[ -n "$requested" && "$requested" != "$existing" ]]; then
    echo "Requested version '$requested' conflicts with existing tag '$existing'" >&2
    exit 1
  fi
  requested="$existing"
fi
version="$(bash "$resolver" "$branch" "$requested" "$major")"

prerelease=false
make_latest=false
notify_site=false
if [[ "$branch" == "1.x" ]]; then
  destination=javadoc/1.x
  channel=1.x
elif [[ "$version" == *-* ]]; then
  prerelease=true
  destination="javadoc/$version"
  channel=2.0-prerelease
else
  destination=javadoc
  channel=latest
  make_latest=true
  notify_site=true
fi

printf 'version=%s\nprerelease=%s\njavadoc_destination=%s\npackage_channel_tag=%s\nmake_latest=%s\nnotify_site=%s\n' \
  "$version" "$prerelease" "$destination" "$channel" "$make_latest" "$notify_site"
