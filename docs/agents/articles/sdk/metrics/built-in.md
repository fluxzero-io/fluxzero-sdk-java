Fluxzero publishes SDK and runtime activity as `MessageType.METRICS` messages. Consume these signals with
`@HandleMetrics` for diagnostics, dashboards, alerting, and application operations. They are not domain events and
must not reconstruct state, authorize behavior, or decide a business transition.

Metrics are intentionally high-volume and generally have shorter, platform-configurable retention than business
messages. Do not assume a fixed retention period. Export or aggregate signals that must survive beyond the configured
metrics log.

## Handler and tracker signals

The most useful SDK payloads for application operations are:

| Payload | Important fields and interpretation |
| --- | --- |
| `HandleMessageEvent` | `consumer`, `handler`, `messageIndex`, `messageType`, `topic`, `payloadType`, `exceptionalResult`, `nanosecondDuration`, and `completed`. A false `completed` means an asynchronous result still has a later completion signal. |
| `CompleteMessageEvent` | The final success/failure and total duration of an asynchronous handler result. Correlate by consumer, handler, index, type, topic, and payload type. |
| `ProcessBatchEvent` | `consumer`, `trackerId`, message type/topic, segment, last index, batch size, and duration. Use successive observations to diagnose throughput and stalled consumers. |
| `IgnoreMessageEvent` | A matching handler was deliberately not invoked. `reason=expiredRequest` means its effective request timeout elapsed before invocation; this is not a handler failure. |
| `PauseTrackerEvent` | Identifies a consumer and tracker that paused while idle or backing off. A pause alone is not failure evidence. |

`HandlerMonitor` and `TrackerMonitor` publish the standard handler and tracker signals. For an asynchronous handler,
do not count an initial `HandleMessageEvent(completed=false)` as a completed success; wait for the matching
`CompleteMessageEvent`.

Expired-request behavior is handler-specific. Commands preserve execution by default after a sender-side timeout,
while queries and ordinary HTTP request handlers normally skip an indexed request that expired before invocation.
Local requests without a log index are not skipped by this mechanism. During deliberate historical replay, set
`skipExpiredRequests = false` only when those old requests really should run.

## Runtime operation inventory

Operation payloads show runtime requests, results, or maintenance activity. A request-shaped metric is not by itself
proof that the operation succeeded; correlate it with its result, an error signal, or direct after-state verification.

### Dispatch and tracking

| Payload | Meaning |
| --- | --- |
| `Append.Metric` | A batch was appended to a message log. |
| `SetRetentionTime` | A log-retention update was requested. |
| `Read` / `ReadResult.Metric` | A tracker requested and received a batch from its position. |
| `ReadFromIndex` / `ReadFromIndexResult.Metric` | A bounded diagnostic or reprocessing read started at an explicit index. |
| `GetPosition` / `GetPositionResult` | A durable consumer position was requested and returned. |
| `StorePosition` | A segment position was advanced. |
| `ResetPosition` | All segment positions for a consumer were forcibly reset. |
| `DisconnectTracker` | A named tracker was disconnected from its consumer. |

### Events and relationships

| Payload | Meaning |
| --- | --- |
| `AppendEvents.Metric` | Aggregate event batches were appended. |
| `GetEvents` / `GetEventsResult.Metric` | An aggregate event stream was requested and returned. |
| `DeleteEvents` | The event stream for an aggregate was deleted through the low-level event-store client. |
| `UpdateRelationships` | Entity-to-aggregate relationships were changed. |
| `RepairRelationships` | Stored relationships were replaced from reconstructed aggregate state. |
| `GetAggregateIds` / `GetAggregateIdsResult` | Aggregate owners for an entity ID were requested and returned. |
| `GetRelationships` / `GetRelationshipsResult` | Relationship records were requested and returned. |

### Scheduling

| Payload | Meaning |
| --- | --- |
| `Schedule.Metric` | One or more messages were scheduled. |
| `CancelSchedule` | A scheduled message was cancelled. |
| `GetSchedule` / `GetScheduleResult.Metric` | Schedule state was requested and returned. |

### Documents and search

| Payload | Meaning |
| --- | --- |
| `IndexDocuments.Metric` | Documents were submitted for indexing. |
| `SearchDocuments` / `SearchDocumentsResult.Metric` | A search request and its result. |
| `GetDocument` / `GetDocumentResult.Metric` | One document was requested and returned. |
| `GetDocuments` / `GetDocumentsResult.Metric` | Multiple documents were requested and returned. |
| `HasDocument` | Document existence was checked. |
| `DeleteDocumentById` | One document was deleted by collection and ID. |
| `DeleteDocuments` | All documents matching a search query were deleted. |
| `DeleteCollection` | An entire document collection was deleted. |
| `MoveDocumentById` | One document was moved to another collection. |
| `MoveDocuments` | Matching documents were moved to another collection. |
| `BulkUpdateDocuments.Metric` | Ordered index/delete updates were applied in bulk. |
| `GetFacetStats` / `GetFacetStatsResult.Metric` | Facet value counts were requested and returned. |

### Results, connections, host, and cache

`VoidResult`, `BooleanResult`, and `StringResult` are ordinary runtime acknowledgements or values. `ErrorResult`
indicates a failed runtime request. `ConnectEvent` and `DisconnectEvent` report WebSocket session lifecycle.

Host collection can publish `HostMetrics` and focused CPU, container, disk, file-descriptor, JVM class, garbage
collection, memory, thread, and uptime payloads when host metrics are enabled. `CacheEvictionEvent` can expose SDK
cache pressure. Treat these as application-process health signals; managed database and cluster internals remain a
platform concern.

## Consume selectively

Register narrow handlers for the payloads an operational component needs:

```java
@Component
@Consumer(name = "handler-health-metrics")
final class HandlerHealthMetrics {
    @HandleMetrics
    void on(HandleMessageEvent metric) {
        if (metric.isCompleted() && metric.isExceptionalResult()) {
            // Forward a small operational signal; do not trigger business state changes.
        }
    }

    @HandleMetrics
    void on(IgnoreMessageEvent metric) {
        if (IgnoreMessageEvent.EXPIRED_REQUEST.equals(metric.getReason())) {
            // Count or alert on unexpected expiry by handler and payload type.
        }
    }
}
```

Keep protected payload values out of exported labels and logs. Prefer stable identifiers, type names, counts, and
durations. `FluxzeroBuilder.disableTrackingMetrics()` disables standard tracking metrics globally; `DisableMetrics`
can suppress them more narrowly. Suppress only intentionally measured noise, not evidence of an unresolved failure.
