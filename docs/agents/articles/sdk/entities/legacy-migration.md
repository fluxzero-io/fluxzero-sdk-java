# SDK upgrade guidance

`@Aggregate` is the legacy/migration API for existing applications, annotated with `@Deprecated(forRemoval = true)`.
It remains supported in SDK 2.x and is scheduled for removal in SDK 3.0. Plan an explicit Model migration
before upgrading to 3.0.
Java and Kotlin callers receive deprecation warnings; builds treating warnings as errors may need adjustment.
Prefer independent `@Model` types for new domain state. `@Member` remains supported and is not deprecated, including within `@Stateful` handlers.

Upgrading to SDK 2.x does not force a data migration. Renaming `@Aggregate` to `@Model` does not transfer existing
streams, snapshots or embedded children. Preserve existing Java/Kotlin handlers and serialized contracts until
an explicitly qualified migration changes their ownership.

For upgrading an application across SDK major versions or migrating its stored representation, read
[Fluxzero 2.0](https://fluxzero.io/docs/fluxzero-2-deep-dive#upgrading-from-1x).

For ordinary application-schema evolution, use the serialization and Model migration-testing articles. Changing a
payload schema and changing its persistence boundary are different operations; qualify the actual stored data.
