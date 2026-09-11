# Historical SDK agent documentation

This catalog reconstructs agent documentation for released SDK versions that only
shipped the earlier Java/Kotlin manuals. It starts from the first published graph,
SDK 1.268.0, and applies reviewed changes backwards. The 2.0 prereleases form a
separate branch from that baseline. New 2.x application guidance uses `@Model`;
existing Aggregate state keeps its explicit compatibility and migration guidance.

Each catalog revision pins its parent, exact patch checksum, resulting graph hash,
historical manual-set hash and applicable SDK release commits. Several releases
with unchanged manuals have separate graphs because the SDK API changed between
them. A missing historical API must not be inferred from later documentation.

The patches are curated documentation changes, not a generic Markdown converter.
Keep the graph's granular articles, links and discoverable symbols. Compare both
language manuals and the tagged SDK code when editing a transition. An old typo or
missing explanation is not a reason to reintroduce incorrect guidance.

## Local reconstruction

Python 3.9+ and a Git checkout containing the pinned commits are sufficient:

```sh
python3 .github/scripts/build-agent-docs-history.py --validate-only
python3 .github/scripts/test-agent-docs-history.py
python3 .github/scripts/build-agent-docs-history.py
```

The last command requires the reviewed history and packaging scripts to be
committed. It builds all missing historical classifiers under
`target/agent-docs-backfill/<version>/`. Use repeated `--version` arguments for a
subset and `--output` for another local destination. This tool does not upload.

Each output contains `fluxzero-sdk-java-<version>-agent-docs.zip`, its SHA-256 sidecar and a
provenance sidecar. `inventory.json` lists the built artifacts. The normal graph
validator and release packager are reused. Reconstruction rejects altered patches,
wrong context, unsafe paths, duplicate versions and graph hash mismatches.

The ZIP's `release.json.sourceCommit` identifies the **documentation curation
commit**, which actually contains the catalog and patches. The catalog and
provenance sidecar separately identify the historical **SDK release commit**.
Backfilled articles did not exist in that old SDK tree. The component version
still names the historical SDK so the existing MCP reader selects the right docs.

## Publication boundary

Qualify every local graph and the artifacts with the supported MCP reader before
publishing. Upload only missing documentation classifiers and their required
sidecars through the normal artifact publisher. Never republish old SDK JARs,
POMs or tags, and never overwrite an existing documentation artifact. Preserve the
original ZIP bytes and checksums for retries; do not rebuild between upload
attempts after changing the curation commit.
