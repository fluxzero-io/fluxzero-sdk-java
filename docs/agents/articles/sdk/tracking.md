Use tracking guidance when a handler consumes messages asynchronously, needs an explicit consumer, or must replay history. Do not simulate tracking positions in application code.

`@Consumer` defines the logical stream position and tracker shape for a handler, class, or package:

- `name` is the durable consumer identity. Reusing it continues from the stored position.
- `threads` controls tracker concurrency for that consumer.
- `singleTracker = true` forces strict global ordering.
- `ignoreSegment = true` lets the consumer receive all segments; combine with `@RoutingKey` only when client-side filtering is intentional.
- `maxFetchBytes` caps serialized payload bytes per fetch.
- `awaitOutgoingWrites = true` waits for outgoing commands started during a batch before storing the consumer position.

```java
@Component
@Consumer(name = "order-projection", threads = 4)
class OrderProjection {
    @HandleEvent
    void on(CreateOrder event) {
        // Update a projection or publish follow-up work.
    }
}
```

Handlers without explicit consumer configuration use the unconfigured-handler fallback. Set
`fluxzero.tracking.unconfiguredHandlerConsumerMode` (`FLUXZERO_TRACKING_UNCONFIGURED_HANDLER_CONSUMER_MODE`) explicitly
to `perHandler`, `perPackage`, or `defaultAppConsumer`. Otherwise `fluxzero.defaults.version` chooses:
missing/before `2026.05.20` → shared application consumer; `2026.05.20` through `2026.07.26` → per handler;
`2026.07.27` or newer → per exact package and message type. Absolute HTTP test stubs use these same rules;
give a stub its own test consumer when it represents an independently running external service.

Start with the default `LoggingErrorHandler`: log failures and continue without retry, with monitoring and recovery
for skipped effects where needed. **`ThrowingErrorHandler` can stop the affected tracker until explicit restart,
even for functional rejection; it does not automatically retry or resume.** Unlimited retries are appropriate only
for recoverable failures with safely repeatable effects, acceptable blocked progress and lag alerts. Read
`/docs/sdk/tracking/error-policy` before changing the policy.

Replay is a code/configuration decision, not a casual runtime action:

```java
@Component
@Consumer(name = "rebuild-orders-v2", minIndex = 0)
class OrderRebuilder {
    @HandleDocument
    OrderDocument on(OrderDocument document) {
        return document;
    }
}
```

Use a new consumer name plus `minIndex = 0` for a full replay. Use `maxIndexExclusive` for a bounded correction window. Convert time windows with `IndexUtils.indexFromTimestamp(...)` when the source period is known.

Before adding replay logic, inspect the handler side effects:

- Projection/document rebuilds are usually safe.
- External writes, payments, emails, and business-impacting commands need idempotency or user confirmation.
- A live replay can run in a separate consumer while the original consumer continues, but only when duplicate effects are safe.

For document rebuilding after search or upcaster changes, increment `@Revision` on the document type and return the document from a new `@HandleDocument` replay consumer so the store writes the current representation.

For monotonic identifiers, model the sequence as a query backed by persisted counter state. Use `@Consumer(singleTracker = true)` only for a global sequence; omit it when routing keys partition independent counters naturally.

Canceling a tracking registration lets an active batch finish before requesting release of its segment ownership
with a `DisconnectTracker` using `STORED` delivery. This also applies when other consumers keep the client connected. Cancellation from
inside a handler releases ownership only after the processing stack has returned. A Runtime handover is not an
exactly-once guarantee after a crash; retain the usual replay-safe handling of external side effects.

Closing the client gives outstanding terminal releases a bounded grace period before closing the transport.
The caching wrapper and WebSocket tracking client each wait at most two seconds; an already completed release
adds no wait. Admitted result callbacks receive at most one additional second. Other WebSocket clients allow
one second for already-issued commands, without waiting for long polls. These are per-component budgets and
can accumulate during full application shutdown. A disconnected Runtime or a timeout still requires the usual
replay-safe recovery; closing from a result callback does not wait on that callback itself.

## Consumer delivery filters and long reads

`fluxzero.tracking.consumerFiltering` (`FLUXZERO_TRACKING_CONSUMER_FILTERING`) enables conservative server filtering
from the actual registered handlers. `fluxzero.tracking.maxWaitDuration` (`FLUXZERO_TRACKING_MAX_WAIT_DURATION`)
accepts ISO-8601 durations such as `PT15M`. Filtering and a fifteen-minute idle wait are automatic on supporting
peers, independently of `fluxzero.defaults.version`. Set filtering to `false` to disable or the wait to `PT1M` for
sixty seconds. Explicit properties override either default;
per-consumer `ConsumerConfiguration` builder values and explicit `@Consumer(maxWaitDuration = ...)` seconds win over
the wait property. Omitted annotation values inherit. Resolve from the owning application's property source.

Payload selection is derived per handler method from the active resolver chain and `allowedClasses`, then unioned
per effective consumer. Known bean and trigger resolvers only constrain their annotated parameters; an extra
`JsonNode` view does not widen a separate typed payload parameter. Final classes can supply
exact names. Open and sealed supertypes remain broad until a complete subtype catalog exists: reflection may omit
unloadable permitted subclasses, so enumerating the currently visible sealed leaves is insufficient. This includes
`non-sealed` branches and constant-specific enum subclasses. Final `@TrackSelf` payloads provide their mandatory type
boundary even for methods without a payload parameter; web self-handlers retain route matching and automatic OPTIONS.
Automatic Model command handlers expose their actual payload matcher boundary, for both command-owned and
Model-owned `@Apply` methods. Scheduled commands select the `ScheduledCommand` envelope on SCHEDULE, then apply
ordinary COMMAND selection after relay; do not narrow the relay by locally registered inner command types.
Local automatic handling issues no server Read. These rules apply equally to Java and Kotlin.
All persistent legacy aggregate roots require `@Aggregate`; unannotated loads or newly created roots fail.
Implicit legacy aggregate-value arguments require `@Aggregate` on their declared type, including inherited markers.
Such an argument does not constrain payload selection; another required payload argument or `allowedClasses` can.
An unannotated bare value is a payload argument. Runtime does not inspect aggregate metadata for filtering.
Payload/revision facts include upcaster inputs and aliases, conservatively widened for dynamic transformations,
custom extensions, arrays and open supertypes. Web filters reuse compiled route patterns and automatic HEAD/OPTIONS.
Web selection uses the actual composed package/class/method/property paths, absolute origins and automatic
HEAD/OPTIONS, including static mount/fallback routes. Socket consumers stay broad to preserve handshake,
pong and close cleanup even on unmatched paths.
Unknown metadata/formats remain eligible. Standard document/interceptor wrappers preserve selection; subclasses
that can widen invocation remain unrestricted unless they explicitly supply conservative metadata. Stateful/member,
socket-session and graph-change expansion currently retain broad delivery. Runtime uses the Common implementation; Test Server/local tracking match.
`SerializedMessage.route` is optional and independent of body `Data.type`. Web producers project method + space +
origin/raw path after their final serialized interceptors, including proxy chunks and socket frames. Query and fragment
are excluded; encoded separators, plus signs and Unicode are not decoded. Runtime filtering never reads metadata or
body bytes. Missing routes stay eligible, while null body type does not widen a known route. Low-level Java/Kotlin
publishers can call `WebRequestRoute.update(message)` after changing final request metadata.
`Fluxzero-Message-Envelope-Version: 2` independently negotiates native routed envelopes. Older peers receive ordinary
JSON/CBOR (CBOR fallback for BINARY); route-absent native messages stay byte-compatible v1. Old stored inputs remain
eligible without deriving routes from metadata. SDK protocol readers accept both envelope versions.
Runtime storage writers default to native v1 (routes omitted). Upgrade all storage readers before setting
`fluxzero.runtime.messageEnvelopeVersion=2` / `FLUXZERO_RUNTIME_MESSAGE_ENVELOPE_VERSION=2` and restarting writers.
Before activation, type filtering works and absent stored routes remain broadly eligible. Switching back to `1` does
not rewrite existing v2 data; use the Runtime offline route downcaster before rollback to a compatible older 2.x reader.
Runtime and Test Server advertise `Fluxzero-Consumer-Filter-Versions: 2` in the existing websocket handshake.
The SDK adapts every send/replay to the actual selected session. Missing/unknown capabilities strip the filter,
keep metadata-only refreshes local and fall back to the historic scan wait for SDK-selected long waits; explicit
builder waits retain their historical semantics. New runtimes accept old clients. Upcasting remains
application-side. Filters are delivery optimization, never authorization.

Full snapshots travel on first/changed reads, small references thereafter. Read telemetry retains request correlation
and carries only the reference. Cache misses recover before claims or
scans without cursor movement. Registration refreshes open reads before it returns; it does not replay previously
skipped messages. Subscriptions are application-instance scoped. Filtered consumers bypass broad SDK prefetch caches on supporting peers; legacy peers retain their configured shared cache.
A prefetch already started against legacy peers retains its normal lifecycle until client close, preserving cache
continuity for unfiltered consumers and downgrades. Fresh supporting connections do not start that broad prefetch.
Long reads preserve immediate relevant wakeup, independent transport liveness and cancellation. SDK-selected long
waits keep the previous sixty-second scan-yield boundary under irrelevant traffic. No stored-data migration is needed.

Synchronous TestFixture applies the same consumer selection before tracked handler execution, including automatic
Model and `@TrackSelf` handlers. Original serialized inputs retain their pre-upcast type/revision. True local handlers
bypass selection; registration and caster changes refresh the consumer union. The same filtering off-switch applies.
This validates admission while retaining synchronous dispatch semantics. Use async coverage for serialized dispatch
interceptors, batching and consumer lifecycle; use connected tests for protocol negotiation.

Asynchronous TestFixture completion follows the conservative handler selection for all peers: inputs excluded by a
consumer's blueprint do not keep that consumer pending, including when an older peer still delivers them broadly.
Set `fluxzero.tracking.consumerFiltering=false` when a test also needs to await batch interceptors for handler-irrelevant
inputs. Disabling filtering retains the previous batch-acknowledgement wait. Pending selection uses the original
serialized envelope and owning namespace, including schedules registered before their consumer.
