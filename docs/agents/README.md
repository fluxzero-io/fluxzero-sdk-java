# Fluxzero agent documentation

This is the canonical agent documentation graph for applications built with this SDK, alongside the human-oriented
[`docs/developer`](../developer) documentation. Read the graph selectively: start at `/docs`, search titles, summaries
and symbols for the task, then read the relevant articles and follow links only for missing detail.

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
or a moving `latest` version in the manifest. A future release bundle must bind the namespace to the actual SDK release,
source commit and content hash. Documentation corrections ship with a normal SDK release, including a patch release
when only documentation changes; published release contents are immutable.

The graph is data, independent of MCP transport. Its presence here does not yet make it available through the local
dev-server MCP. That integration and versioned download/cache distribution are separate changes. Existing
`project-java.zip` and `project-kotlin.zip` release consumers continue to use the legacy `project-files` tree during
that transition; it is not the source for new graph articles.

## Editing and validation

- Update the relevant graph articles with an SDK behavior change, together with the human/developer documentation.
- Keep every supported concept reachable. Split advanced detail into a focused article instead of deleting correct
  information to shorten a parent. Preserve both Java and Kotlin behavior; use language-specific examples where needed.
- Add an article to the manifest, link to it from a relevant existing article, and link back to its parent.
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
