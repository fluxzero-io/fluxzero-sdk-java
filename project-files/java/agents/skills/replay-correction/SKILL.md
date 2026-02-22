---
name: replay-correction
description: Perform targeted retroactive correction and replay safely using consumer windows, tracker settings, and side-effect awareness. Use when fixing historical processing issues or rebuilding projections.
---

# Replay Correction

Apply replay-based fixes deliberately and safely.

## Workflow

1. Identify the affected handler(s), consumer(s), and message window.
2. Compute `minIndex`/`maxIndexExclusive` (use `IndexUtils` for timestamp conversion).
3. Implement isolated correction consumer/handler configuration.
4. Validate side-effect safety; confirm with user if risk is unclear.
5. Run targeted replay and verify corrected outcomes.
6. Remove or retire temporary correction paths when done.

## Guardrails

- Prefer bounded replay windows over full-history replay when feasible.
- Keep correction handlers explicit and auditable.
- Treat external side effects as at-least-once-sensitive.

## References

- ../../rules/tracking.md
- ../../rules/runtime-interaction.md
- ../../rules/testing.md
