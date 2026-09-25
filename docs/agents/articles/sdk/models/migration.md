# Model deletion and erasure

This contract applies to both Java and Kotlin applications.

## Deletion

- Returning `null` from `@Apply` is logical deletion and preserves history.
- Logical parent deletion recursively deletes children whose relevant `@Parent` keeps the default
  `deleteOnParentDeletion = true`. This follows pathless relations and shared descendants too; a shared descendant is
  deleted when any owning parent disappears. Moving a child away in the same atomic commit preserves it.
- `modelRepository().deleteModel(id, NONE)` physically erases that model's stream, current document, snapshots and
  cache state while leaving the global event log untouched.
- Physical descendant erasure remains a separate destructive operation and requires `planDeletion(...)` followed by
  confirmation/execution of that exact plan.
- Erasure fences prevent delayed document, snapshot or projection writes from resurrecting deleted data.
- Relations closed by parent deletion remain discoverable for later descendant erasure, including nested logical
  cascades. Earlier ordinary detachments or moves are not added back to the deleted tree.
- Always inspect the deletion plan before confirming it. Upgrading does not repair missing lineage markers written
  by older implementations; an already logically deleted tree needs separately verified scope before erasure.
