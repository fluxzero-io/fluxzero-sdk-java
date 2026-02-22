---
name: web-surface
description: Build Fluxzero REST and WebSocket surfaces as thin adapters over domain logic, including @Path composition, status mapping, and endpoint-focused tests. Use when implementing or modifying API/websocket endpoints.
---

# Web Surface

Expose domain behavior through thin transport handlers.

## Workflow

1. Confirm domain slice is implemented and tested first.
2. Add endpoint handlers (`@HandleGet/@HandlePost/...`) with minimal orchestration.
3. Keep path composition explicit and predictable.
4. Map domain outcomes to HTTP status consistently.
5. For test fixture web calls, add explicit result typing/casts when needed.
6. Verify both internal endpoint behavior and external web integrations.

## Guardrails

- Do not move business invariants into endpoint handlers.
- Prefer `/api` base path unless explicitly requested otherwise.
- Keep websocket endpoints focused on message translation and flow control.

## References

- ../../rules/handling.md
- ../../rules/testing.md
- ../../rules/validation.md
