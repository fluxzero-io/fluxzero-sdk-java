`Fluxzero.get().client()` exposes the configured low-level `Client`. It is a supported advanced boundary for runtime

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
capabilities that have no complete application-level facade, but it also makes partial and destructive operations
possible. Prefer `aggregateRepository()`, `documentStore()`, message gateways, schedulers, and `Fluxzero.search(...)`
whenever they express the full intent.

Public subsystems include:

| Accessor | Appropriate advanced use |
| --- | --- |
| `getTrackingClient(messageType[, topic])` | bounded log inspection, consumer positions, controlled reset, and tracker disconnection |
| `getEventStoreClient()` | event-stream inspection and exceptional low-level repair/deletion procedures |
| `getSearchClient()` | protocol-level search operations not represented by `DocumentStore`; prefer the store/search builder first |
| `getSchedulingClient()` | low-level schedule inspection; prefer `MessageScheduler` for application scheduling |
| `getGatewayClient(messageType[, topic])` | log-level dispatch, retention, and truncation; prefer typed gateways or `customGateway(topic)` |

## Namespace discipline

`Client`, gateways, document stores, and metrics gateways are namespaced. The application configuration normally
selects the Cloud namespace for every subsystem. A deliberate `forNamespace(...)` call changes the target and must not
be used to probe another tenant or environment. Cross-namespace access should be assumed unavailable unless the
deployment explicitly grants and requires it.

For topic-based `DOCUMENT` and `CUSTOM` tracking/gateway access, always pass the collection or topic. The no-topic
overload rejects those message types because there is no single default log.

## Do not drop below the client contract

The SDK jar contains public transport payload classes such as `ResetPosition`, `DeleteEvents`, and
`RepairRelationships`. Their visibility supports the client/runtime protocol; it does not make constructing and sending
wire commands preferable to calling a client method. Client methods supply message type, topic, guarantee, request
correlation, namespacing, and response handling consistently.

Do not hand-build WebSocket URLs or JSON protocol messages in application management code. Do not expose client IDs,
authorization headers, connection metadata, or protected payload contents in logs. Return a typed operational result
containing the target namespace/resource, before-state summary, acknowledgement, verification result, and any residual
risk.
