# Model Deletion And Erasure

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
- Detached descendants remain discoverable through deleted-parent lineage for later GDPR/lifecycle erasure.
