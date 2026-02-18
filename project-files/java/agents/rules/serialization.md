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

[//]: # (@formatter:off)
```java
@Revision(2)
public record Project(
    ProjectId projectId,
    ProjectDetails details
) {}
```
[//]: # (@formatter:on)

---

<a name="upcasting"></a>

## Upcasting (@Upcast)

Upcasting transforms an old version of a serialized object into the current version during deserialization. Upcasting
works at the top-level payload/document type, not on nested properties in isolation.

<a name="payload-upcasting"></a>

### Payload Upcasters (ObjectNode)

Use this pattern when you only need to modify the JSON structure of the payload (e.g., adding a default value or
renaming a field).

[//]: # (@formatter:off)
```java
@Component
public class ProjectUpcaster {
    @Upcast(type = "io.fluxzero.app.api.model.Project", revision = 1)
    public JsonNode upcastRev1(ObjectNode payload) {
        if (!payload.has("details")) {
            payload.putObject("details").put("name", "Untitled Project");
        }
        return payload;
    }
}
```
[//]: # (@formatter:on)

<a name="data-upcasting"></a>

### Data Upcasters (Full Message)

Use this pattern when you need to change the **type**, **revision**, or **metadata** of the serialized object. To modify
metadata, you must inject the `SerializedMessage` and call `setMetadata(Metadata)`.

[//]: # (@formatter:off)
```java
@Component
public class CreateProjectUpcaster {
    @Upcast(type = "io.fluxzero.app.old.CreateProject", revision = 0)
    public Data<JsonNode> upcastRev0(Data<JsonNode> data, SerializedMessage message) {
        // Example: Update metadata
        message.setMetadata(message.getMetadata().with("upcasted", "true"));
        
        // Move to a new package and increment revision
        return data.withType("io.fluxzero.app.new.api.CreateProject")
                   .withRevision(1);
    }
}
```
[//]: # (@formatter:on)

---

<a name="downcasting"></a>

## Downcasting (@Downcast)

Downcasting is the inverse of upcasting. It transforms a newer version of an object into an older version. This is
commonly used when supporting **versioned APIs** (endpoints), allowing the internal repository to evolve without
breaking external clients that expect an earlier schema.

Unlike upcasting, downcasting is typically invoked manually.

**Example: Realistic Downcaster (ObjectNode Manipulation)**
Use this pattern to transform complex nested JSON structures back to an earlier revision.

[//]: # (@formatter:off)
```java
@Downcast(type = "com.example.api.ProjectView", revision = 2)
ObjectNode downcastProjectFrom2(ObjectNode project) {
    if (project.get("details") instanceof ObjectNode details) {
        if (details.get("history") instanceof ArrayNode history) {
            Iterator<JsonNode> iterator = history.elements();
            while (iterator.hasNext() && iterator.next() instanceof ObjectNode entry) {
                // Perform nested transformations
                entry.remove("internalNotes");
            }
        }
    }
    return project;
}
```
[//]: # (@formatter:on)

**Example: Versioned API Endpoint**

[//]: # (@formatter:off)
```java
@Component
@Path("/api/v1/projects")
public class LegacyProjectEndpoint {
    
    @HandleGet("/{id}")
    public Object getLegacyProject(@PathParam ProjectId id) {
        Project modernProject = Fluxzero.loadAggregate(id).get();
        
        // Manually downcasting to Revision 1 for legacy V1 clients
        return Fluxzero.downcast(modernProject, 1);
    }
}
```
[//]: # (@formatter:on)

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

[//]: # (@formatter:off)
```java
@Test
void testProjectUpcasting() {
    fixture.whenUpcasting("/projects/old-project-rev0.json")
           .expectResult(Project.class)
           .expectResult(project -> project.details().name().equals("Untitled Project"));
}
```
[//]: # (@formatter:on)

---

<a name="implementation-rules"></a>

## Implementation Rules

- **Registration**: All upcaster classes must be registered with Fluxzero or must be annotated with `@Component` when
  Spring is used. The SDK auto-detects them during startup.
- **Placement**: Upcasters can be placed inside the class they transform (as a static inner class with `@Component`) or
  in a separate package. For shared logic, a separate upcaster component is often cleaner.
- **Chain of Responsibility**: Fluxzero automatically chains upcasters. To move from Revision 0 to 2, the SDK will look
  for a 0->1 upcaster and then a 1->2 upcaster.
- **FQNs**: Always use the Fully Qualified Name of the target class in the `type` attribute of the `@Upcast` annotation.
- **Type Coverage**: If a shared nested value object changes (for example `ProjectDetails`), add upcasters for each
  top-level type that embeds it (for example `CreateProject`, `UpdateProject`, and `Project`).
- **Revision Discipline**: Whenever a payload/document schema changes, increment `@Revision` on each affected top-level
  type.
- **Safe Rollout**: For already-deployed applications, upcasters MUST be verified with
  `TestFixture.whenUpcasting` before production deployment. If deployment status is unclear, ask the user whether the app
  is already deployed before enforcing this check. Since historical messages are immutable, a faulty upcaster can be
  "fixed" with a new deployment without losing data.
- **Idempotency**: Upcasters are called during deserialization. Ensure your logic is safe to run multiple times,
  although the SDK typically handles the orchestration.
