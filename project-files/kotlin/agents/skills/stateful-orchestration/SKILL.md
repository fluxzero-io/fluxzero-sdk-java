---
name: stateful-orchestration
description: Build and evolve Fluxzero @Stateful workflows, including associations, retries, schedule loops, and state lifecycle transitions (create/update/fan-out/complete). Use when implementing long-running process orchestration.
---

# Stateful Orchestration

Implement long-running workflows with explicit state lifecycle semantics.

## Workflow

1. Model stateful identity (`@EntityId` or associations) and correlation inputs.
2. Implement creation handler and update handlers per workflow step.
3. Use return semantics intentionally: instance update, collection fan-out, null delete.
4. Configure retries/timeouts/consumer settings where needed.
5. Add tests for normal progression, retries, compensation, and completion.

## Guardrails

- Keep state transitions deterministic.
- Use clear association keys for routing and replay behavior.
- Avoid side effects without explicit retry/error handling strategy.

## References

- ../../rules/sagas.md
- ../../rules/tracking.md
- ../../rules/testing.md
- ../../rules/handling.md
