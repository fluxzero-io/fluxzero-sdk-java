# Releasing the Fluxzero SDK

`main` is the active 2.x line; `1.x` is the maintenance branch. Each branch declares its major in
[`.github/release-major`](.github/release-major). The scripts
[resolve-release-version.sh](.github/scripts/resolve-release-version.sh) and
[resolve-release-publication.sh](.github/scripts/resolve-release-publication.sh) enforce allowed versions
and publication destinations, including existing tags on reruns.

## Maintenance patches

A completed 1.x fix or backport includes its patch publication unless the requested scope explicitly excludes it.

1. Inspect the latest published 1.x tag and choose the next unused patch version on that minor line, for example
   `1.292.1` after `1.292.0`. Keep the maintenance branch's release major at `1`.
2. Qualify the fix and merge through the required `build-pr` check into `1.x`.
3. Start `Deploy` with `workflow_dispatch`, ref `1.x` and the explicit patch version. Ordinary pushes to `1.x`
   do not publish. The resolver rejects missing versions, 2.x versions, non-patch versions and a conflicting
   existing tag. Multiple release tags on the same commit are rejected as ambiguous.
4. Verify all workflow jobs, the immutable tag and the published artifacts, including that the tag contains the fix.
   A reserved tag can be reused only on the same commit and with a matching requested version.

Maintenance publishes normal stable Maven artifacts and a GitHub release, but never takes over the active major's
channels: package images use `1.x`, Javadoc uses `javadoc/1.x`, GitHub `make_latest` is false and the public SDK website
is not dispatched. Direct Javadoc dispatches from `1.x` must also explicitly select `javadoc/1.x`.

## Active-major and historical prerelease workflows

Accepted changes on `main` use the active major's automatic version policy, package channel `latest`, the general
Javadoc destination and public SDK website signal. Deliberately forward-port maintenance fixes where needed; do not
merge the 1.x release-major declaration into `main`.

The historical `next/2.0` workflow accepts explicit `2.0.0-Mn` or `2.0.0-RCn` prereleases. It publishes immutable
artifacts, package channel `2.0-prerelease` and version-scoped Javadoc, without GitHub Latest or a website signal.
Run an SDK release before a matching Runtime release so Runtime can pin the immutable SDK version.

## Local policy checks

```bash
bash .github/scripts/resolve-release-version.test.sh
bash .github/scripts/resolve-release-publication.test.sh
```

These checks exercise valid releases and reruns, rejected branch/version/tag combinations and isolation of maintenance
publication destinations. They do not create tags or publish artifacts.
