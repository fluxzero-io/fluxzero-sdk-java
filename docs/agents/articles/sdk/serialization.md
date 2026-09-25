Use serialization guidance before changing a payload, document, Model state, or stateful handler type that may already be stored. Historical messages are immutable; compatibility is handled during deserialization.

Polymorphic ID properties (`Id<?>` / Kotlin `Id<*>`, or abstract ID classes) retain a discriminator automatically:
Model IDs use `{"name":"project","id":"owner-a"}` with their logical Model name; non-Model IDs use
`{"@class":"example.ExternalId","id":"owner-a"}`. Concrete ID properties and standalone IDs remain scalar.
Each named Model must declare its concrete ID class as `@EntityId`. `@Parent(types=...)` restricts which Models may
be decoded; outside parents a Model type argument or the generated Model index provides the mapping.
Explicit Jackson type contracts/custom serializers keep precedence. Unknown or ambiguous discriminators fail closed.
Discriminated values use the concrete ID's custom deserializer when configured, retaining the enclosing property context.
Upgrade readers before writers; upcast ambiguous old polymorphic scalars and renamed non-Model `@class` values at
their enclosing payload revision. See [Java Graph relations](models/graphs-java.md) and
[Kotlin Graph relations](models/graphs-kotlin.md).

Default path:

- Add fields in a backward-compatible way when possible.
- When a stored top-level type has a breaking schema change, increment `@Revision`.
- Ask whether the app has deployed historical data before adding an upcaster.
- Verify upcasters with `TestFixture.whenUpcasting(...)` before deployment.
- For a compatible class/package rename, configure a type alias instead of inventing a revision migration.
- For current producers using stable short type names, use `@RegisterType` and preserve generated registry resources.

```java
@Revision(2)
public record Project(ProjectId projectId, ProjectDetails details) {
}
```

Use `@Upcast` for old serialized objects. Upcasters work at the top-level stored type, not at arbitrary nested field
paths. Spring registers caster beans automatically. Outside Spring, call `serializer.registerCasters(...)`; in a
fixture, call `TestFixture.create().registerCasters(...)` before supplying old data. Passing a caster to
`TestFixture.create(caster)` only registers a handler, not its caster methods.

Use an `ObjectNode` upcaster when only JSON shape changes:

```java
class ProjectUpcaster {
    @Upcast(type = "com.example.project.api.model.Project", revision = 1)
    JsonNode fromRevision1(ObjectNode payload) {
        JsonNode name = payload.remove("name");
        if (name == null || !name.isTextual()) {
            throw new IllegalArgumentException("Revision 1 requires a textual name");
        }
        payload.putObject("details").set("name", name);
        return payload;
    }
}
```

This is a `name` → `details.name` refactor, not a request to rename existing projects. Preserve the stored name,
identity and unrelated fields. Only introduce a default for genuinely missing data when the old schema permits that
absence and the domain explicitly defines the default. Do not replace a known value with `"Untitled"`.

Use a full `Data<JsonNode>` upcaster when a revision migration also changes the type, revision, or format. For a
pure compatible class/package rename, follow the type-aliases article instead:

```java
@Component
class CreateProjectUpcaster {
    @Upcast(type = "com.example.old.CreateProject", revision = 0)
    Data<JsonNode> fromRevision0(Data<JsonNode> data) {
        return data.withType("com.example.project.api.CreateProject")
                .withRevision(1);
    }
}
```

Message metadata has a separate immutable upcasting contract. Inject `Metadata` and return the replacement `Metadata`; do not mutate the source `SerializedMessage`. Read metadata upcasting for message-only versus reusable non-message signatures, complete replacement semantics, successive payload/metadata revisions, and failure tests.

Fluxzero chains upcasters by revision. To move revision `0` data to revision `2`, provide `0 -> 1` and `1 -> 2` steps. If a shared nested value object changes, add upcasters for every top-level stored type that embeds it.

Test with old serialized JSON:

```json
{
  "@class": "com.example.project.api.model.Project",
  "@revision": 1,
  "projectId": "project-1",
  "name": "Legacy name"
}
```

`@class` and `@revision` provide the serialized source type and revision and are removed before the payload reaches
the upcaster. A plain field named `revision` remains payload data. The revisioned-JSON article explains arrays,
NDJSON and fixture versus direct `JsonUtils` resource reads.

```java
TestFixture.create().registerCasters(new ProjectUpcaster())
        .whenUpcasting("/project/project-rev1.json")
        .expectResult(new Project(new ProjectId("project-1"), new ProjectDetails("Legacy name")));
```

The example's current `Project` is revision **2**, the resource is revision **1**, and the caster consumes revision
**1**. `ProjectDetails` is `record ProjectDetails(String name) {}`; the application's `ProjectId` is an `Id<Project>`.
For Kotlin use `.registerCasters(ProjectUpcaster())` and `.expectResult(Project(ProjectId("project-1"),
ProjectDetails("Legacy name")))`; class matchers take `Project::class.java`, not `Project::class`.

Test event replay separately: register a caster for each changed historical `@Apply` event and use
`givenModelEvents(modelId, oldSerializedEvent, ...)`. A Model-state caster alone does not migrate its creation event.
The Model migration and reconstruction articles distinguish caster tests, synthetic event application and retained
storage written by an older SDK.

`@Downcast` is for versioned API responses or compatibility adapters and is usually invoked manually with `Fluxzero.downcast(...)`. Do not use downcasting to mutate the stored event stream.

Read-time upcasting does not rewrite stored JSON or search indexes. Ordinary derived documents can be reindexed by a
revision-aware `@HandleDocument` migration. A materialized Model Graph has its own complete-Graph return contract.
For internal sources use `@HandleDocument(modelState = Project.class)` and return the upcast value unchanged:
identity/state changes and split/drop upcasts are rejected; a full-head/body/proof guard skips stale rewrites.
The independent public DOCUMENT projection uses ordinary `documentClass` handling and stays ancestor-queryable.
Do not use ordinary writes to replace internal Model state. Follow the Model migration recipe for
before/after queries and the distinction between these representations.
