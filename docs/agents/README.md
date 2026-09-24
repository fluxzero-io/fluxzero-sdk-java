# Fluxzero agent documentation

This is the canonical agent documentation graph for applications built with this SDK, alongside the human-oriented
[`docs/developer`](../developer) documentation. Read the graph selectively: start at `/docs`, search titles, summaries
and symbols for the task, then read the relevant articles and follow links only for missing detail.

On SDK v2, start new persisted state at `/docs/sdk/models`: Java 25+, independent Model lifecycle, automatic commands,
atomic commits and lazy Graph reads. The retained entity topics serve existing aggregate state, not new v2 modeling.

## Graph contract

`manifest.json` declares `schemaVersion: 1`, the documentation `namespace: sdk`, the root article, and the article
inventory. Each article has a stable logical `path`, a title, a short summary, a relative Markdown `source`, searchable
`symbols`, and directed `links` with descriptions. Paths such as `/docs/sdk/serialization` are logical article IDs,
not paths in the checkout. Existing IDs are retained so incoming references survive the migration from the dashboard.
Article bodies intentionally leave graph navigation in the manifest rather than repeating large link lists.

An article's identity is the namespace, component release version, and logical path together. Future documentation
namespaces may reuse the same paths; consumers must not treat a path or numeric version alone as globally unique.
This repository currently supplies only `sdk`. Existing application-facing CLI, IDP and Cloud guidance is retained
in this graph; it does not claim that those independently released tools share the SDK version.

The SDK tag/checkout identifies which SDK the graph describes. Do not hard-code the dashboard's dependency version
or a moving `latest` version in the manifest. Documentation corrections ship with a normal SDK release, including
a patch release when only documentation changes; published release contents are immutable.

The graph is data, independent of MCP transport. A documentation bridge must load the archive matching the project's
SDK version; transport, download and cache ownership remain outside this archive. Existing
`project-java.zip` and `project-kotlin.zip` release consumers continue to use the legacy `project-files` tree during
that transition; it is not the source for new graph articles.

## Release archive

Maven `package` produces `target/fluxzero-sdk-java-<version>-agent-docs.zip`, attached to
`io.fluxzero:fluxzero-sdk-java:<version>` with classifier `agent-docs` and type `zip`. One archive contains both Java
and Kotlin guidance. Standard Maven deployment publishes it to Fluxzero Packages:

```text
https://packages.fluxzero.io/maven/io/fluxzero/fluxzero-sdk-java/<version>/fluxzero-sdk-java-<version>-agent-docs.zip
```

The SHA-256 checksum is available at the same path with `.sha256` appended. The GitHub SDK release also contains
the exact ZIP and its checksum, using the same filenames, as a backup for restoring Packages.

The archive contains `manifest.json`, its `articles/` sources and `release.json`. The latter records `schemaVersion`,
`namespace`, `componentVersion`, `sourceCommit` and `contentHash`. The content hash is SHA-256 over the concatenated
UTF-8 records `path + NUL + lowercase SHA-256(file bytes) + LF`, sorted by path, for the manifest and article files.
It excludes `release.json`; the archive checksum covers all bytes, including release metadata. ZIP entry timestamps
and permissions are fixed so repeated builds of the same inputs produce the same archive.

Packaging requires Python 3.9+ and Git. `-Dagent-docs.python=<executable>` selects Python;
`-Dagent-docs.sourceCommit=<full-commit-hash>` supports building a source archive without Git metadata.

## Editing and validation

- Update the relevant graph articles with an SDK behavior change, together with the human/developer documentation.
- Keep every supported concept reachable. Split advanced detail into a focused article instead of deleting correct
  information to shorten a parent. Preserve both Java and Kotlin behavior; use language-specific examples where needed.
- Add an article to the manifest, link to it from a relevant existing article, and link back to its parent.
- Keep logical article IDs in manifest links. In article prose, write paths such as `/docs/sdk/models` as code;
  these are documentation lookup keys, not root-relative filesystem links.
- Index exact API symbols and useful task language. Do not make a search return the entire graph by default.
- Match instructions to the project version. A documentation mismatch must not cause an automatic SDK upgrade.
- Obtain current CLI options and dev configuration from the installed tools; these have independent release cycles.

Validate the manifest and graph structure from the repository root:

```bash
python3 .github/scripts/validate-agent-docs.py
```

The check verifies the namespace/schema, unique article IDs, safe and existing sources, the complete Markdown
inventory, valid and nonduplicate logical graph links, and reachability from the root.

Lychee checks ordinary Markdown links during Maven `verify`.

Check changed behavioral examples against the relevant SDK APIs and focused tests.
