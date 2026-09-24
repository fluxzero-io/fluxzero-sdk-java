# Releasing the Fluxzero SDK

Fluxzero uses an update train rather than a long-lived minor/patch distinction.
The branch declares the active stable major in [`.github/release-major`](.github/release-major), while
`.github/scripts/resolve-release-version.sh` is the executable owner of allowed branch/version combinations.

## Before 2.0 GA

- `main` publishes the next stable 1.x update automatically after every accepted change.
- `next/2.0` receives `main` regularly and is the integration branch for 2.0.
- A milestone or release candidate is started manually from `next/2.0` with an explicit version such as
  `2.0.0-M1` or `2.0.0-rc.11`.
- Prereleases are published under their immutable Maven, GitHub and package-image version. The moving package tag is
  `2.0-prerelease`; `latest`, the stable Javadoc destination and the public SDK-site signal remain untouched.

### Release-candidate spelling

From RC11 onward, use lowercase `rc` and a dot-separated numeric identifier: `2.0.0-rc.11`, then
`2.0.0-rc.12`. Keep this exact spelling in Maven coordinates, Git tags, GitHub releases, package images and Javadoc
paths. Do not publish alternative spellings of the same candidate.

Maven recognizes `rc` as a prerelease qualifier; the numeric suffix also sorts numerically in SemVer. Lowercase is
important for the transition: SemVer compares `RC.11` below the historical `RC10`, while `rc.11` sorts above both
`RC9` and `RC10`. Maven also orders `RC10 < rc.11 < rc.12 < 2.0.0`. See
[Maven version ordering](https://maven.apache.org/pom.html#Version_Order_Specification) and
[SemVer precedence](https://semver.org/#spec-item-11).

Existing `M1`/`RC1`-style tags and artifact paths remain immutable. The policy still accepts those spellings for
historical reruns; do not rename them or alternate formats for future RCs. Exact dependency pins must use the
published spelling, even when Maven considers two different version strings equivalent.

Run the SDK milestone before the matching Runtime milestone so the Runtime can build from the immutable SDK tag.
Normal changes flow from `main` to `next/2.0`; only deliberately selected 2.0 fixes flow back.

## 2.0 GA

1. Make sure the final 1.x release is green and `next/2.0` contains that exact `main` tip.
2. Create and protect `1.x` from the final 1.x tip. Ordinary pushes to this branch never publish.
3. Merge `next/2.0`, including `.github/release-major` set to `2`, into `main`.
4. The resulting `main` run publishes `2.0.0`, because no stable major-2 tag exists yet.
5. Remove `next/2.0` only after the SDK and Runtime GA releases and downstream checks are green.

Subsequent accepted changes on `main` publish `2.1.0`, `2.2.0`, and so on. An exceptional critical 1.x repair is
published manually from `1.x` with an explicit patch version such as `1.247.1`; it is then forward-ported to `main`.

## Local policy check

Run:

```bash
bash .github/scripts/resolve-release-version.test.sh
```

This validates the update train, first-major release, milestones, release candidates and exceptional maintenance
patches without creating tags or publishing artifacts.
