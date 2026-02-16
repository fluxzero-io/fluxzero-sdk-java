# Serialization & Schema Evolution

In a message-driven and event-sourced system like Fluxzero, data is stored for long periods. Serialization ensures that
as your domain model evolves, historical data (events, documents, stateful handlers) remains compatible with your latest
code.

---

## Quick Navigation

- [Core Principles](#core-rules)
- [Versioning with @Revision](#revision)
- [Upcasting (@Upcast)](#upcasting)
    - [Payload Upcasters (ObjectNode)](#payload-upcasting)
    - [Data Upcasters (Full Message)](#data-upcasting)
- [Downcasting (@Downcast)](#downcasting)
- [Testing Upcasters](#testing-upcasters)
- [Implementation Rules](#implementation-rules)

---

<a name="core-rules"></a>

## Core Rules

1. **Never Change Stored Messages**: Historical messages (events, commands) are immutable. You must transform them
   during deserialization instead of trying to "fix" the storage. Documents, however, can be updated or rebuilt.
2. **Ask Before Upcasting**: When a model change is required, **always ask the user** if an upcaster is necessary before
   implementing one.
3. **Automatic Application**: Upcasting is applied to **ALL** deserializing objects in Fluxzero, including messages,
   documents, and stateful handlers.
4. **Register as Components**: Upcaster classes must be annotated with `@Component` to be discovered by the SDK.
5. **Test with TestFixture**: Upcasters can be verified using `TestFixture.whenUpcasting(...)`.

---

<a name="revision"></a>

## Versioning with @Revision

By default, every class has a revision of `0`. When you make a breaking change to a class (e.g., renaming a field or
changing a type), you must increment its revision.

```kotlin
@Revision(2)
data class Project(
    val projectId: ProjectId,
    val details: ProjectDetails
)
```

---

<a name="upcasting"></a>

## Upcasting (@Upcast)

Upcasting transforms an old version of a serialized object into the current version during deserialization.

<a name="payload-upcasting"></a>

### Payload Upcasters (ObjectNode)

Use this pattern when you only need to modify the JSON structure of the payload (e.g., adding a default value or
renaming a field).

```kotlin
@Component
class ProjectUpcaster {
    @Upcast(type = "io.fluxzero.app.api.model.Project", revision = 1)
    fun upcastRev1(payload: ObjectNode): JsonNode {
        if (!payload.has("details")) {
            payload.putObject("details").put("name", "Untitled Project")
        }
        return payload
    }
}
```

<a name="data-upcasting"></a>

### Data Upcasters (Full Message)

Use this pattern when you need to change the **type**, **revision**, or **metadata** of the serialized object. To modify
metadata, you must inject the `SerializedMessage` and call `setMetadata(Metadata)`.

```kotlin
@Component
class CreateProjectUpcaster {
    @Upcast(type = "io.fluxzero.app.old.CreateProject", revision = 0)
    fun upcastRev0(data: Data<JsonNode>, message: SerializedMessage): Data<JsonNode> {
        // Example: Update metadata
        message.metadata = message.metadata.with("upcasted", "true")
        
        // Move to a new package and increment revision
        return data.withType("io.fluxzero.app.new.api.CreateProject")
                   .withRevision(1)
    }
}
```

---

<a name="downcasting"></a>

## Downcasting (@Downcast)

Downcasting is the inverse of upcasting. It transforms a newer version of an object into an older version. This is
commonly used when supporting **versioned APIs** (endpoints), allowing the internal repository to evolve without
breaking external clients that expect an earlier schema.

Unlike upcasting, downcasting is typically invoked manually.

**Example: Realistic Downcaster (ObjectNode Manipulation)**
Use this pattern to transform complex nested JSON structures back to an earlier revision.

```kotlin
@Downcast(type = "com.example.api.ProjectView", revision = 2)
fun downcastProjectFrom2(project: ObjectNode): ObjectNode {
    (project.get("details") as? ObjectNode)?.let { details ->
        (details.get("history") as? ArrayNode)?.let { history ->
            val iterator = history.elements()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry is ObjectNode) {
                    // Perform nested transformations
                    entry.remove("internalNotes")
                }
            }
        }
    }
    return project
}
```

**Example: Versioned API Endpoint**

```kotlin
@Component
@Path("/api/v1/projects")
class LegacyProjectEndpoint {
    
    @HandleGet("/{id}")
    fun getLegacyProject(@PathParam id: ProjectId): Any {
        val modernProject = Fluxzero.loadAggregate(id).get()
        
        // Manually downcasting to Revision 1 for legacy V1 clients
        return Fluxzero.downcast(modernProject, 1)
    }
}
```

---

<a name="testing-upcasters"></a>

## Testing Upcasters

You can verify upcasters in a `TestFixture` by providing the old serialized form.

**Sample: old-project-rev0.json**

```json
{
  "@class": "io.fluxzero.common.api.Data",
  "value": {
    "@class": "com.fasterxml.jackson.databind.JsonNode",
    "projectId": "PRJ-1",
    "name": "Legacy Name"
  },
  "type": "io.fluxzero.app.api.model.Project",
  "revision": 0
}
```

**Example: Upcaster Test**

```kotlin
@Test
fun testProjectUpcasting() {
    fixture.whenUpcasting("/projects/old-project-rev0.json")
           .expectResult(Project::class)
           .expectResult { project -> project.details.name == "Untitled Project" }
}
```

---

<a name="implementation-rules"></a>

## Implementation Rules

- **Registration**: All upcaster classes must be registered with Fluxzero or must be annotated with `@Component` when
  Spring is used. The SDK auto-detects them during startup.
- **Placement**: Upcasters can be placed inside the class they transform (as a companion object method with
  `@JvmStatic` and `@Upcast`) or in a separate package. For shared logic, a separate upcaster component is often cleaner.
- **Chain of Responsibility**: Fluxzero automatically chains upcasters. To move from Revision 0 to 2, the SDK will look
  for a 0->1 upcaster and then a 1->2 upcaster.
- **FQNs**: Always use the Fully Qualified Name of the target class in the `type` attribute of the `@Upcast` annotation.
- **Safe Rollout**: Before deploying an upcaster to production, verify it locally using `TestFixture.whenUpcasting`.
  Since historical messages are immutable, a faulty upcaster can be "fixed" with a new deployment without losing data.
- **Idempotency**: Upcasters are called during deserialization. Ensure your logic is safe to run multiple times,
  although the SDK typically handles the orchestration.
