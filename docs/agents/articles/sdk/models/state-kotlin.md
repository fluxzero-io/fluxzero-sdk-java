# Model state and lifecycle (Kotlin)

## Core rules

1. Implement model state as immutable data classes and value objects.
2. Put action-specific `@AssertLegal`, `@InterceptApply` and `@Apply` methods on the command/update payload by default.
3. Keep `@Apply` pure and deterministic. It is reused during event sourcing.
4. Do not load, search, publish or perform I/O from `@Apply`.
5. Choose every model boundary by lifecycle first. State that can be created, changed, retained, deleted, or whose
   history matters independently is a separate `@Model`, even when it is normally placed in a parent's collection.
6. Treat a meaningful identity, separate retention, or independent updates as evidence for that boundary, not as
   competing criteria. A child without a globally unique functional ID can use `@EntityId(parentScoped = true)`.
7. Use `@Member` only when creation, every change, history, stream, document, cache, retention, and deletion all
   deliberately belong to the root. Collection shape, searchability, storage choice, and update frequency never make
   independently living state a member.
8. Use typed `Id<T>` values. The exact `Id.toString()` is the persisted model identity.

## Choose details, configuration and state

Business details copied into Model state belong in a cohesive immutable value object, even if it initially contains
only `name`. Choose the group by domain meaning and shared validation, not by field count or Java/Kotlin type.

| Kind of field | Put it where | Examples and boundary |
| --- | --- | --- |
| Descriptive business data | A details value object | `ProjectDetails(name, description)`; start with `ProjectDetails(name)` if that is all the product needs. |
| Configuration / desired policy | A focused settings value object | `NotificationSettings` groups related choices; do not mix unrelated settings into descriptive details. |
| Identity | On the Model, with a typed ID | `@EntityId ProjectId projectId` is not an editable detail. |
| Relationships | An explicit typed reference on the Model | `ownerId` or `@Parent workspaceId`; a reference is not the related Model's details. |
| Current status / control | A simple Model field, or a focused state value when several fields form one invariant | `archived`, `status`, `completedAt`; a business date such as a requested delivery date belongs with its business details instead. |
| Execution bookkeeping | Separate from editable details | `attemptCount`, `lastAttemptAt`, `nextRunAt`; group as execution state if cohesive, or use a separate Model if it has its own lifecycle. |

A boolean can be a user preference or observed state; a timestamp can be business input or execution bookkeeping.
Their meaning decides placement. A details object is not a bag for every field left over after the ID.
Use multiple small named values when the concepts differ. It has no independently addressable lifecycle:
plain `ProjectDetails` needs neither `@Model`, `@EntityId` nor `@Member`. Replacing that value is a change to
its owning Model, not a separate entity update. The Model/Member lifecycle rules still apply to actual entities.

## Define a model

```kotlin
@Model
data class Project(
    @EntityId val projectId: ProjectId,
    val details: ProjectDetails,
    val ownerId: UserId
)

data class ProjectDetails(
    @field:NotBlank val name: String,
    @field:Size(max = 500) val description: String?
)
```

Kotlin uses `copy` and Jakarta Validation's `@field:` use-site targets. Required constructor values are non-null.
`ProjectId` and `UserId` are application-owned typed IDs; `Sender` is the application's authenticated `User`
implementation exposing `userId()`. Keep ordinary ID boilerplate out of feature examples.

## Create and change details

The creation command carries the whole details value and uses `@Valid` to cascade into its constraints.
A focused `RenameProject(id, name)` is still the right contract for renaming: command shape expresses intent,
not the stored object's shape. Validate its new name and replace only that field of the existing details.
Keep the description, identity and owner unchanged; do not construct an otherwise empty replacement details object.
Reserve whole-details replacement for an operation that intentionally edits the whole group.

```kotlin
data class CreateProject(
    val projectId: ProjectId,
    @field:Valid val details: ProjectDetails
) {
    @Apply
    fun apply(sender: Sender) = Project(projectId, details, sender.userId())
}

data class RenameProject(
    val projectId: ProjectId,
    @field:NotBlank val name: String
) {
    @AssertLegal
    fun assertOwner(project: Project, sender: Sender) {
        if (project.ownerId != sender.userId()) {
            throw UnauthorizedException("Not allowed to rename project")
        }
    }

    @Apply
    fun apply(project: Project) =
        project.copy(details = project.details.copy(name = name))
}
```

Bean constraints validate incoming values; `@AssertLegal` protects state-dependent rules such as ownership.
`@Apply` only constructs the new immutable state. For a rule involving both the changed field and retained fields,
validate that combined candidate in `@AssertLegal` as well; a field constraint alone cannot express that rule.
The SDK does not automatically validate every returned Model. Enable cascaded bean validation at each input boundary
that accepts details; simply annotating a field inside `ProjectDetails` does not cascade from an unannotated command.

Validation assumes the payload can first be deserialized. The default serializer normalizes blank strings to null;
a serialized blank for Kotlin's non-null `name` is rejected during deserialization, before bean validation.
Do not assume that rejection has the same exception type as a locally validated command.

This is a modeling convention, not a new SDK restriction. For already stored Models, moving `name` to `details.name`
changes serialized shape and query paths: plan the appropriate event/document upcasting or migration rather than
silently renaming fields in an existing application's history.

## Storage is a separate choice

The default above is event sourcing without a direct document or periodic snapshots. Add storage only for a concrete
read requirement. Use the central matrix at `/docs/sdk/entities/graph-search`.
Relationship-scoped search can use an internal component without `DOCUMENT`; ordinary `search(T.class)` lists cannot.

Important settings:

- `name`: durable logical Model type name; defaults to the concrete class's simple name. Keep an explicit value stable
  across Java/Kotlin class or package renames. It is separate from serializer payload types and has no aliases or FQN
  fallback. `fluxzero.model.namePrefix` is prepended literally for applications sharing a namespace (`billing` +
  `Invoice` = `billingInvoice`). Changing either value after data exists requires an application-managed data
  transition.
- `persistence`: selects a non-empty set of durable representations:
  - `[EVENT_SOURCED]` (default): reconstruct from Model events, without a direct document.
  - `[EVENT_SOURCED, DOCUMENT]`: reconstruct from events and also maintain a current document.
  - `[DOCUMENT]`: load authoritative current state from the current document.
- `ignoreUnknownEvents`: deliberately tolerates unhandled stored events during event-sourced reconstruction.
- `document`: optional `DocumentProjection` configuration for the direct collection, timestamp paths, and public
  searchability. It is valid only when `persistence` contains `DOCUMENT`; use `searchable = false` for a document that
  should remain available by Model ID, alias, parent relation and Graph composition without entering typed search.
- `eventPublication`: controls whether unchanged transitions create an event.
- `publicationStrategy`: `DEFAULT`, `STORE_AND_PUBLISH`, `STORE_ONLY` or `PUBLISH_ONLY`.
- `snapshotPeriod` and `maxSnapshotCount`: event-sourcing optimizations.
- `checkpointPeriod`: bounds repeated replay work within one reconstruction session.
- `cached` and `cachingDepth`: current and previous revisions retained in the SDK cache.
- `conflictPolicy`: `ACCEPT`, `RETRY`, `FAIL` or inherited `DEFAULT` for concurrent writes.
- `commitPolicy`: controls commit timing and completion-phase concurrency; normally keep `DEFAULT`.
- `automaticHandling`: opt out when an explicit command handler must call `Fluxzero.assertAndApply`.
- `materializeGraph`: enables the optional durable whole-tree read model.
- `graphProjection`: optional advanced `GraphProjection` configuration; its collection defaults to the resolved direct
  Model collection plus `-graphs` when a direct document exists, or `<logical Model name>-graphs` otherwise, and
  materializes the complete finite graph without implicit size limits.

Persistence does not control event storage or publication. Those remain owned by `eventPublication`,
`publicationStrategy` and per-apply overrides. Internal Graph-component documents are also orthogonal: they neither
make an `EVENT_SOURCED` Model directly searchable nor change its load path. Event-sourcing-only options such as
`ignoreUnknownEvents`, snapshots and replay checkpoints are rejected on `DOCUMENT`-only Models.

## Embedded members

`@Model` plus `@Member` is the intentional shared-stream option:

```kotlin
@Model
data class Invoice(
    @EntityId val invoiceId: InvoiceId,
    @Member val lines: List<InvoiceLine>
)

data class InvoiceLine(
    @EntityId val lineId: LineId,
    val amount: BigDecimal
)
```

Choose this only if each line has no meaningful lifecycle outside its invoice: creation, every change, history,
retention and deletion all belong to the root. If any of those concerns can diverge, use a separate `@Model` plus
`@Parent`. A list-shaped field, frequent updates, or convenient whole-document storage is never sufficient reason to
use `@Member`.
