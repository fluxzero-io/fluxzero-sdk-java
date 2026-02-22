---
name: messaging-scheduling
description: Send and schedule Fluxzero messages correctly, including commands, queries, events, custom logs/topics, metadata tracing, and web requests. Use when adding message publication or scheduling behavior.
---

# Messaging And Scheduling

Implement publication and scheduling with runtime semantics in mind.

## Workflow

1. Choose the correct message type (command/query/event/result/error/metric).
2. Use SDK send/publish APIs instead of custom transport logic.
3. Configure schedules with stable IDs and deterministic timing.
4. Use custom logs/topics only when explicit separation is needed.
5. Add metadata tracing with `$trace.*` keys via SDK metadata utilities.
6. Test both dispatch behavior and downstream handling expectations.

## Guardrails

- Do not bypass runtime tracking for normal app-to-app communication.
- Keep core and second-rank flows separated when needed to protect replay/debug quality.
- Preserve idempotency assumptions by using consistent message identities where applicable.

## References

- ../../rules/sending.md
- ../../rules/handling.md
- ../../rules/testing.md
- ../../rules/runtime-interaction.md
