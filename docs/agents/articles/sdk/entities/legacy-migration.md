# SDK upgrade guidance

`@Aggregate` is the legacy/migration API for existing applications, annotated with `@Deprecated(forRemoval = true)`.
It remains supported in SDK 2.x and is scheduled for removal in SDK 3.0. Plan an explicit Model migration
before upgrading to 3.0.
Java and Kotlin callers receive deprecation warnings; builds treating warnings as errors may need adjustment.
Prefer independent `@Model` types for new domain state. `@Member` remains supported and is not deprecated, including within `@Stateful` handlers.

SDK 2.x requires `@Aggregate` on every persistent aggregate root, including through an interface or superclass.
This applies to Java and Kotlin. Add the annotation to previously unannotated roots: `loadAggregate(...)` and
repository loads reject known unannotated root types. Untyped loads validate the root as soon as its type becomes
known, including during creation or replay, before storing the root or publishing its events. This requirement does
not depend on `fluxzero.defaults.version` or consumer filtering. Ordinary members, independent Models and
side-effect-free `asEntity(...)` wrappers do not require `@Aggregate`.

For implicit aggregate-value injection, the declared parameter type must also carry the marker. An unannotated
bare value parameter is a payload parameter, even if the message carries aggregate context. Explicit `Entity<T>`
arguments and injection inside an existing entity context keep their own resolution path, but loading a persistent
aggregate through those paths still requires an annotated root.

Upgrading to SDK 2.x does not force a data migration. Renaming `@Aggregate` to `@Model` does not transfer existing
streams, snapshots or embedded children. Preserve existing Java/Kotlin handlers and serialized contracts until
an explicitly qualified migration changes their ownership.

For upgrading an application across SDK major versions or migrating its stored representation, read
[Fluxzero 2.0](https://fluxzero.io/docs/fluxzero-2-deep-dive#upgrading-from-1x).

For ordinary application-schema evolution, use the serialization and Model migration-testing articles. Changing a
payload schema and changing its persistence boundary are different operations; qualify the actual stored data.
