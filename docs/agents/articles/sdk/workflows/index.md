Use this section for durable processes that correlate several independently arriving messages, cross a deadline, or coordinate external side effects.

Choose the state owner first:

- Use an aggregate when the workflow is naturally one domain consistency boundary. Add namespaced `@Alias` fields for alternate aggregate lookup.
- Use `@Stateful` when the process needs independently persisted handler state and message-to-instance routing through `@Association`.
- Keep a simple reaction stateless when all progress can be derived from existing aggregate state.

Then make correlation identifiers service-owned when the public request does not own them, persist compensation intent before sending side effects, and prove the workflow after reconstruction. The focused children cover those decisions and their symmetric test matrix.
