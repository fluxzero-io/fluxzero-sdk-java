Use a type alias when a stored or queued class/package name changes but its payload shape and revision remain
compatible. Use an upcaster for an actual schema/revision migration. Combining an upcaster with an alias is valid:
keep `@Upcast.type` on the historical source name and let the alias map the result to its current class.

## Prefer application configuration

Put the complete alias list in one `application.properties` value:

```properties
fluxzero.serialization.typeAliases=com.example.legacy.RenameLabel=com.example.labels.ChangeLabel,com.example.legacy.events.*=com.example.labels.events.*
```

The conventional environment variable is `FLUXZERO_SERIALIZATION_TYPE_ALIASES`; the compact
`FLUXZERO_SERIALIZATION_TYPEALIASES` spelling is also supported. An environment value takes precedence over system
and application properties and replaces the complete list; it does not append entries from lower-priority sources.
Entries can be separated by commas, semicolons or newlines. Package aliases use `.*` on both sides.

For aliases deliberately owned by application code or a test, configure the builder:

```java
DefaultFluxzero.builder()
        .addTypeAlias("com.example.legacy.RenameLabel", "com.example.labels.ChangeLabel")
        .addPackageAlias("com.example.legacy.events", "com.example.labels.events");
```

The same calls are available in Kotlin. Programmatic aliases override property aliases with the same source.
Builder aliases reach the primary serializer, snapshot serializer and serializer-backed document serializer.

## Matching and ordering

- Exact aliases win over package aliases.
- The longest matching package prefix wins; package boundaries are respected.
- Aliases may chain, but cycles are rejected during registration.
- Revision upcasters select the historical serialized type first. Aliases map the resulting type before deserialization,
  so a class rename does not skip an upcaster registered for that historical FQN.
- Registered simple envelope names are normalized before selecting revision upcasters; this is separate from the
  post-upcast mapping of historical aliases. Read registered type names when combining both mechanisms.

Aliases apply to serialized envelope types, root and nested polymorphic JSON `@class` values, and JSON-encoded
metadata read through typed `Metadata.get(...)`. Raw metadata strings remain unchanged.

## Fixture and resource boundaries

`TestFixture` uses the configured aliases for JSON resources. It also offers `registerTypeAlias(...)` and
`registerPackageAlias(...)`. Non-revisioned `@class` values are resolved before Jackson loads the class; a revisioned
root preserves the historical type until its upcasters have run. Use `@revision`, not a payload field named `revision`.

Direct untyped `JsonUtils` reads outside the fixture have no ambient application serializer. Supply its resolver through
`JsonUtils.fromFileWithTypeMapper(...)` when aliases or registered names are needed. `Serializer.resolveTypeName`
combines alias and registered-name resolution; `upcastType` alone only handles aliases.

Verify exact and package renames, overlapping packages, nested polymorphic values, typed metadata, and a historical
type that needs both a revision upcaster and an alias. Do not introduce an upcaster solely to rewrite a compatible FQN.
