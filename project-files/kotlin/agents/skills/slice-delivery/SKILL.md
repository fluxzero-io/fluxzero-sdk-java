---
name: slice-delivery
description: Deliver Fluxzero work as vertical feature slices with backlog discipline, one-slice scope, tests, checkpointing, and compliance reporting. Use when starting a project/phase or selecting the next implementation slice.
---

# Slice Delivery

Drive implementation as small vertical slices and keep progress auditable.

## Use This Workflow

1. Create or update a tickable backlog for the current project/phase.
2. Select the next uncompleted slice.
3. Re-read the applicable rules before editing code.
4. Implement only the selected slice.
5. Add tests for happy path and business-rule failures.
6. Run modified tests and relevant suite.
7. Refactor only code/tests touched in the slice.
8. Pause for review/checkpoint.
9. Update backlog status and report compliance.

## Guardrails

- Do not batch multiple unrelated slices in one pass.
- Keep endpoint work last for each slice.
- Treat guides as source of truth and use this skill as execution wrapper.

## References

- ../../rules/guidelines.md
- ../../rules/testing.md
- ../../rules/runtime-interaction.md
