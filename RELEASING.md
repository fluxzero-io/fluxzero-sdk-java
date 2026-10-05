# Releasing the Fluxzero SDK

Fluxzero derives automatic stable versions from Conventional Commits since the latest reachable stable tag.
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

Subsequent accepted changes on `main` use `fix`, `perf`, `deps`, `revert`, `docs`, `chore` and `test` for a patch bump;
`feat` takes precedence and produces a minor bump. Other commit types do not raise a detected patch bump.
When no release-bearing type is present, the historical minor fallback remains. Both scoped and unscoped
subjects are supported. A `!` subject or `BREAKING CHANGE:`/`BREAKING-CHANGE:` footer stops automatic
publication within the declared major; make the major transition explicitly through `.github/release-major`.
The first release of a newly declared major remains `<major>.0.0`. Existing tags on the current commit are reused
for reruns. Prerelease tags and stable tags outside the current history do not select the base; a conflicting
version already reserved elsewhere fails instead of overwriting it.

For example, a fix after `2.1.0` produces `2.1.1`; a feature produces `2.2.0`.
Explicit dispatch versions remain subject to the branch's major/prerelease restrictions. An exceptional critical 1.x repair is
published manually from `1.x` with an explicit patch version such as `1.247.1`; it is then forward-ported to `main`.

## Documentation-only changes

PRs and pushes classify the complete changed-file set with `.github/scripts/classify-changes.py`. Markdown,
MDX, Markdown-text alternatives (`.markdown`, `.rst`, `.adoc`), plain text under `docs/` and conventional
README/license/changelog text files, `LICENSE`, `NOTICE`, documentation images
and diagrams under `docs/`, and `docs/agents/manifest.json` use lightweight validation. Files under `src/` or
`fixtures/` retain full validation even with those extensions: runtime and test inputs need not be compiled.
Known text build inputs such as `requirements.txt` and `CMakeLists.txt` remain on the full route.
Other files, including scripts, build configuration and mixed code/documentation changes, keep the full build.
Both sides of renames count; pushes compare the whole pushed range and PRs compare the merged result with its base.

The required `build-pr` check still runs. Agent graph validation and archive tests run on the lightweight route,
but Java setup, Maven and executable artifact qualification are skipped. Use ordinary commit messages without
`[skip ci]`. Documentation-only pushes do not create SDK versions or publish packages, Javadoc or release ZIPs.
Versioned agent documentation incorporates edits at the next SDK release; existing release
assets remain immutable. Skipped commits remain in history and participate in the next release's version selection.

Validated documentation-only pushes affecting `docs/developer/` still send `fluxzero-sdk-updated` to the website
with the exact SDK SHA and before/after range, without inventing an SDK version. The website refreshes changed
pages and assets from that commit. Stable SDK releases retain their existing website notification. Manual Deploy
dispatch always keeps the complete release path and requires explicit publication authorization. Deploy runs queue
instead of replacing pending releases when a later documentation-only push arrives.

## Local policy check

Run:

```bash
bash .github/scripts/resolve-release-version.test.sh
python3 .github/scripts/classify-changes.test.py
```

This validates commit-based patch/minor selection, breaking-change guards, first-major releases, reruns, milestones, release candidates and exceptional maintenance
patches without creating tags or publishing artifacts.

## Release notes

Stable release notes compare against the highest earlier stable tag reachable from the release commit, including
tags from the previous major. Prereleases are excluded from that automatic stable baseline. Tagged reruns use the
same selection as first publication. The generator's explicit `PREVIOUS_TAG` override remains available for an
intentional custom comparison; prerelease generation retains its existing tag selection.

The generated commit list is the required structure for release notes, including editorial updates. Review the final
release diff and correct descriptions of intermediate behavior that changed again before publication, while retaining
the categorized lists, directly pushed changes, pull requests and their references.

Run `node --test .github/scripts/generate-release-notes.test.mjs` to check release-note boundaries in temporary Git
repositories without publishing artifacts.

### Required published format

Use `VERSION – Mon D, YYYY` as the release title, for example `2.15.1 – Oct 5, 2026`.
Use the exact release tag as VERSION, English three-letter month names and an unpadded day. The date is the
original GitHub `published_at` converted to UTC, never the commit date, local date or last edit time.
This applies to stable, maintenance and prerelease titles. Drafts have no publication date yet.
Deploy normalizes the title after publication with `.github/scripts/set-release-title.py`; reruns preserve the
original date. For an authorized title-only repair, run `python3 .github/scripts/set-release-title.py OWNER/REPO TAG`.
The helper patches only `name`, leaving the release body, tag, assets and release flags intact.

The body starts with the generated
`## [VERSION](comparison-url) (YYYY-MM-DD)` heading. A short introductory paragraph is optional immediately below
it. Then retain the generated `### Features`, `### Bug Fixes`, `### Documentation` and other applicable categories,
with their HTML `<ul>`/`<li>` lists, commit/PR references and expandable `<details><summary>` explanations.
Feature releases use exactly the same structure as fixes: a single feature is still a list item under Features.
Do not replace these lists with a standalone feature article, Highlights, or GitHub's generic What's Changed output.
The generator's explicit size-limit fallback remains available for genuinely oversized histories.

Put substantial feature explanations, examples and migration guidance inside the relevant item's expandable body.
Essential upgrade warnings may also appear in the short opening so they remain visible with details collapsed.
Preserve previous editorial corrections against the final tagged diff; blindly regenerating a release can restore
superseded claims. Do not remove unrelated categories or commits while adding a feature explanation.

For an authorized correction, save the existing release body and metadata first. Generate a local reference from
the immutable release tag using the repository's current generator (replace VERSION and the output path):

```bash
RELEASE_VERSION=VERSION RELEASE_TAG=VERSION CURRENT_REF=VERSION \
  GITHUB_REPOSITORY=fluxzero-io/fluxzero-sdk-java \
  RELEASE_NOTES_PATH=/absolute/path/to/release-notes.md \
  node .github/scripts/generate-release-notes.mjs
```

Review the comparison baseline and the entire replacement before sending it with `gh release edit VERSION
--notes-file /absolute/path/to/release-notes.md`. Re-read the published body and inspect its rendered heading,
categories, list entries and expanded details. Verify the release title, tag target, prerelease/draft status and
asset identities/digests remain unchanged. A notes-only repair must not rerun Deploy or republish artifacts.
CI generation does not prevent a later manual/API edit from replacing the body; this format also binds those edits.
