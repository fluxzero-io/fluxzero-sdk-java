---
name: domain-core
description: Model and implement Fluxzero domain logic for commands, queries, aggregates, and invariants using @Apply and @AssertLegal. Use when building or changing core business behavior.
---

# Domain Core

Implement domain behavior first, before transport-specific layers.

## Workflow

1. Define payload and value-object shape for the target command/query.
2. Implement invariant checks in `@AssertLegal`.
3. Implement pure state transition in `@Apply`.
4. Inject `Sender` for user context; never put sender user IDs in payloads.
5. Keep aggregates as state holders; avoid side effects in model transitions.
6. Add focused tests for happy path and each business-rule failure.

## Guardrails

- Do not query/search/load unrelated data inside `@Apply`.
- Keep logic deterministic and replay-safe.
- Prefer one explicit domain error per violated rule.

## References

- ../../rules/guidelines.md
- ../../rules/handling.md
- ../../rules/entities.md
- ../../rules/validation.md
- ../../rules/testing.md
