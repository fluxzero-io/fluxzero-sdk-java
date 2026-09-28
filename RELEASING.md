# Releasing the Fluxzero SDK

`main` is the active 2.x line; `1.x` is the maintenance branch. Each branch declares its major in
[`.github/release-major`](.github/release-major). The scripts
[resolve-release-version.sh](.github/scripts/resolve-release-version.sh) and
[resolve-release-publication.sh](.github/scripts/resolve-release-publication.sh) enforce allowed versions
and publication destinations, including existing tags on reruns.

## Maintenance patches

A non-documentation push to `1.x` automatically publishes the next patch after the release workflow succeeds. A
merge or push to `1.x` therefore requires explicit authorization for the resulting maintenance release. Assess each
bug fix on `main` for the same defect in supported 1.x behavior; qualify applicable backports and keep unrelated 2.x
features and defaults out of the maintenance patch.

1. Keep the maintenance branch's release major at `1`. The resolver uses the highest reachable 1.x release tag and
   increments its patch number, for example `1.292.8` after `1.292.7`. It ignores 2.x tags and fails if no 1.x tag
   is reachable or the next patch tag already exists elsewhere.
2. Qualify the fix and merge through the required `build-pr` check into `1.x`. Dependabot GitHub Actions updates and
   Maven patch/minor updates use the same auto-merge policy as `main`; Maven major updates remain manual.
3. The push starts `Deploy`. Documentation-only changes are validated without publishing. A manual
   `workflow_dispatch` on `1.x` may leave the version empty for automatic numbering or provide an explicit 1.x patch
   version; 2.x and non-patch versions are rejected.
4. Verify all workflow jobs, the immutable tag and the published artifacts, including that the tag contains the fix.
   A reserved tag can be reused only on the same commit and with a matching requested version. Multiple release tags
   on the same commit are rejected as ambiguous.

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

## Documentation-only PRs

Markdown/MDX, similar documentation text, documentation images and the agent graph manifest use lightweight
validation inside `build-pr`, including graph validation and archive tests. The shared classifier in
`.github/scripts/classify-changes.py` considers the entire merged diff and both sides of renames. Source resources,
fixtures, scripts, configuration and mixed changes retain full SDK qualification. Use ordinary commit messages
without `[skip ci]`. Documentation-only pushes validate without publishing; those edits enter versioned artifacts at
the next maintenance release and existing artifacts remain immutable. Maintenance releases never refresh the
active-major website. This PR filter does not change release authorization or destinations.

## Local policy checks

```bash
bash .github/scripts/resolve-release-version.test.sh
bash .github/scripts/resolve-release-publication.test.sh
python3 .github/scripts/next-maintenance-version.test.py
python3 .github/scripts/classify-changes.test.py
```

These checks exercise valid releases and reruns, rejected branch/version/tag combinations and isolation of maintenance
publication destinations. They do not create tags or publish artifacts.
