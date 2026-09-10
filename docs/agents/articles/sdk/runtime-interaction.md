Fluxzero apps communicate through the runtime, not by direct app-to-app calls. Use this page when a feature crosses handler, consumer, namespace, or replay boundaries.

Command/result flow:

1. App A sends a command.
2. The SDK marks the request source.
3. App B consumes and handles the command.
4. App B appends a result targeted back to App A.
5. App A's request consumer receives the result and completes the pending call.

For command handlers that update aggregates, the SDK waits for asynchronous after-handler aggregate commits by default before returning the handler result. A command followed by a query can often read the committed aggregate/search state. Do not rely on this for downstream projections or event-handler side effects; return needed state from the command or wait for the projection's own signal.

Handler delivery is effectively at-least-once. A tracker fetches a batch, processes it, then commits position. If it crashes before committing, some messages can be processed again.

Agent defaults:

- Make external side effects idempotent or compensatable. Emit ordinary business compensation from a newly persisted false-to-true intent, not whenever an old flag remains true.
- Treat replays as intentional duplicate delivery.
- Do not handle the same command type in multiple result-producing consumers unless the user explicitly asks for that advanced pattern.
- Do not use `exclusive = false` on command handlers by default.

Scaling terms:

- Segment space is `[0, 128)`.
- A message is hashed to one segment through its routing key.
- For one consumer, each segment belongs to one active tracker.
- Multiple app instances sharing the same consumer divide the same segment space across their trackers.
- Equal routing keys are ordered within the same named consumer. The same key in another consumer has an independent
  position and claim, so it does not extend that serialization boundary.

Boundaries:

- Application name scopes generated default consumer names.
- Consumer name owns distribution, replay position, and claiming.
- Namespace is environment or tenant isolation. Assume cross-namespace access is off unless explicitly configured and authorized.

Replay safety depends on side effects. Before adding replay logic, identify the consumer, handler, side effect type, idempotency risk, replay window, live-processing plan, and success signal. Ask the user before replaying handlers with unclear external or business impact.

Use Fluxzero web request/proxy handling for outgoing integrations by default. Read one-way outbound HTTP for absolute URLs, explicit JSON content type, `WebRequestGateway.sendAndForget`, and `SENT` versus `STORED`. Direct networking can be used when explicitly required, but keep idempotency and tracking implications visible in the code review.
