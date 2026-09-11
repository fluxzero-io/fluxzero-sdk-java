Use serialization guidance before changing a payload, document, aggregate state, or stateful handler type that may already be stored. Historical messages are immutable; compatibility is handled during deserialization.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

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

Use `@Upcast` for old serialized objects. Upcasters work at the top-level stored type, not at arbitrary nested field paths. Register them as Spring `@Component` classes or explicit Fluxzero components.

Use an `ObjectNode` upcaster when only JSON shape changes:

```java
@Component
class ProjectUpcaster {
    @Upcast(type = "com.example.project.api.model.Project", revision = 1)
    JsonNode fromRevision1(ObjectNode payload) {
        if (!payload.has("details")) {
            payload.putObject("details").put("name", "Untitled");
        }
        return payload;
    }
}
```

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
  "name": "Legacy name"
}
```

`@class` and `@revision` provide the serialized source type and revision and are removed before the payload reaches
the upcaster. A plain field named `revision` remains payload data. The revisioned-JSON article explains arrays,
NDJSON and fixture versus direct `JsonUtils` resource reads.

```java
fixture.whenUpcasting("/project/project-rev1.json")
        .expectResult(Project.class)
        .expectResult(project -> project.details().name().equals("Untitled"));
```

`@Downcast` is for versioned API responses or compatibility adapters and is usually invoked manually with `Fluxzero.downcast(...)`. Do not use downcasting to mutate the stored event stream.

Documents can be rebuilt after schema/index changes. Pair the upcaster or revision change with a replay consumer that returns the document so the store writes the current representation.
