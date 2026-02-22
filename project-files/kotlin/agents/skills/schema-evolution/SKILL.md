---
name: schema-evolution
description: Evolve Fluxzero payload/document schemas using @Revision and upcasting/downcasting without breaking replay/history. Use when changing serialized message or document structures.
---

# Schema Evolution

Handle data shape changes with explicit revision and migration logic.

## Workflow

1. Determine impacted top-level payload/document types.
2. Increment `@Revision` on each changed type.
3. Add upcasters/downcasters at top-level type scope.
4. Update tests to cover old and new revisions.
5. Rebuild or replay projections/documents when required.

## Guardrails

- Upcasting targets top-level payload/document types, not nested properties directly.
- If a shared nested model changes, update all top-level messages/documents that contain it.
- Keep migration logic deterministic and idempotent.

## References

- ../../rules/serialization.md
- ../../rules/tracking.md
- ../../rules/testing.md
