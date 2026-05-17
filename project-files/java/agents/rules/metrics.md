# Metrics

Fluxzero metrics are ordinary `MessageType.METRICS` messages on the metrics log. They are meant for observability,
diagnostics, and operational dashboards, not for domain decisions.

Metrics messages are lightweight, structured, traceable, routable to `@HandleMetrics` handlers, and retained for 1 month
by default because of their volume.

---

## Quick Navigation

- [Publishing Metrics](#publishing)
- [Handling Metrics](#handling)
- [Built-in Tracking Metrics](#tracking-metrics)
- [Ignored Messages](#ignored-messages)
- [Disabling Metrics](#disabling)

---

<a name="publishing"></a>

## Publishing Metrics

Use `Fluxzero.publishMetrics(...)` or `Fluxzero.get().metricsGateway().publish(...)` for custom operational signals.
Prefer small immutable payloads with stable field names.

[//]: # (@formatter:off)
```java
Fluxzero.publishMetrics(new SearchLatencyMetric("projects", duration.toMillis()));
```
[//]: # (@formatter:on)

Do not use metrics to drive business workflow. Publish commands/events for domain behavior and metrics for
observability.

---

<a name="handling"></a>

## Handling Metrics

Use `@HandleMetrics` to consume host, SDK, or custom metric payloads.

[//]: # (@formatter:off)
```java
@HandleMetrics
void on(HostMetrics metrics) {
    // Forward to monitoring, aggregate dashboards, or log summaries.
}

@HandleMetrics
void on(IgnoreMessageEvent event) {
    if (IgnoreMessageEvent.EXPIRED_REQUEST.equals(event.getReason())) {
        // A request matched this handler but was skipped before invocation.
    }
}
```
[//]: # (@formatter:on)

Metrics handlers should be side-effect oriented and should not publish business results.

---

<a name="tracking-metrics"></a>

## Built-in Tracking Metrics

Most built-in metrics are published automatically by the SDK. `ConnectEvent` and `DisconnectEvent` are published by the
runtime when a client connects or disconnects a WebSocket session.

### Dispatch

| Metric                 | Meaning |
|:-----------------------|:--------|
| `Append$Metric`        | Messages were appended to a log. |
| `SetRetentionTime`     | A message log retention time was updated. |

### Tracking

| Metric                 | Meaning |
|:-----------------------|:--------|
| `Read`                 | A tracker requested a new batch from its current position. |
| `ReadResult$Metric`    | Runtime response metrics for a `Read` request. |
| `ProcessBatchEvent`    | A tracker finished processing a batch of messages. |
| `HandleMessageEvent`   | A handler invocation started and returned or failed. For asynchronous results, `completed` may be `false`. |
| `CompleteMessageEvent` | An asynchronous handler result completed after the initial invocation metric. |
| `IgnoreMessageEvent`   | A message matched a handler but was deliberately skipped before invocation. |
| `ReadFromIndex`        | Manual read starting from a specific index. |
| `ReadFromIndexResult$Metric` | Runtime response metrics for a `ReadFromIndex` request. |
| `GetPosition`          | Requests the current consumer position. |
| `GetPositionResult`    | Returns the current consumer position. |
| `StorePosition`        | A tracker position was updated. |
| `ResetPosition`        | A consumer position was reset. |
| `DisconnectTracker`    | A tracker disconnected. |
| `PauseTrackerEvent`    | A tracker paused because no messages were available or it was backing off. |

`HandlerMonitor` and `TrackerMonitor` are the SDK components that publish the standard handler and tracker metrics.

### Events and Relationships

| Metric                 | Meaning |
|:-----------------------|:--------|
| `AppendEvents`         | Events were appended to an aggregate. |
| `GetEvents`            | Requests events for an aggregate. |
| `GetEventsResult$Metric` | Runtime response metrics for `GetEvents`. |
| `DeleteEvents`         | Deletes events for an aggregate. |
| `UpdateRelationships`  | Updates entity-aggregate relationships. |
| `RepairRelationships`  | Repairs relationship consistency. |
| `GetAggregateIds`      | Requests aggregate IDs for an entity. |
| `GetAggregateIdsResult` | Returns aggregate IDs for an entity. |
| `GetRelationships`    | Requests relationships for an entity. |
| `GetRelationshipsResult` | Returns relationships for an entity. |

### Scheduling

| Metric                 | Meaning |
|:-----------------------|:--------|
| `Schedule`             | Schedules one or more messages for future dispatch. |
| `CancelSchedule`       | Cancels a scheduled message. |
| `GetSchedule`          | Requests a schedule by ID. |
| `GetScheduleResult`    | Returns schedule details. |

### Documents and Search

| Metric                 | Meaning |
|:-----------------------|:--------|
| `IndexDocuments`       | Indexes one or more documents for search. |
| `SearchDocuments`      | Executes a search query on a document collection. |
| `SearchDocumentsResult` | Returns document search results. |
| `GetDocument`          | Retrieves a document by ID. |
| `GetDocumentResult`    | Returns a requested document. |
| `GetDocuments`         | Retrieves multiple documents by ID. |
| `GetDocumentsResult`   | Returns multiple requested documents. |
| `HasDocument`          | Checks if a document exists. |
| `DeleteCollection`     | Deletes an entire document collection. |
| `DeleteDocuments`      | Deletes documents matching a query. |
| `MoveDocuments`        | Moves documents between collections. |
| `DeleteDocumentById`   | Deletes a single document by ID. |
| `MoveDocumentById`     | Moves a single document by ID. |
| `BulkUpdateDocuments`  | Updates documents in bulk. |
| `GetFacetStats`        | Requests facet statistics for a search query. |
| `GetFacetStatsResult`  | Returns facet statistics. |

### Common Results and Runtime Connections

| Metric                 | Meaning |
|:-----------------------|:--------|
| `VoidResult`           | Empty acknowledgement for successful commands. |
| `ErrorResult`          | A runtime request failed. |
| `BooleanResult`        | Boolean response for a request. |
| `StringResult`         | String response for a request. |
| `ConnectEvent`         | Runtime-published event when a client connects a WebSocket session. |
| `DisconnectEvent`      | Runtime-published event when a client disconnects a WebSocket session. |

---

<a name="ignored-messages"></a>

## Ignored Messages

`IgnoreMessageEvent` is not a handler error. It means the SDK intentionally did not invoke the handler after normal
handler matching succeeded.

The currently defined reason is:

| Reason           | Meaning |
|:-----------------|:--------|
| `expiredRequest` | An indexed request had an effective timeout that expired before the handler received it. |

Expired request filtering is handler controlled:

- Commands default to `@HandleCommand(skipExpiredRequests = false)` to preserve command execution semantics after a
  sender-side timeout.
- Queries default to `@HandleQuery(skipExpiredRequests = true)`.
- HTTP web handlers default to `skipExpiredRequests = true`; WebSocket lifecycle handlers opt out.
- Local requests without an index are not skipped by this mechanism.
- During replays, set `skipExpiredRequests = false` on the handler when historical requests should still be processed.

---

<a name="disabling"></a>

## Disabling Metrics

Use `FluxzeroBuilder.disableTrackingMetrics()` to disable standard tracking metrics globally. Use `DisableMetrics` as a
consumer/interceptor option for narrower suppression.

Do not disable metrics just to hide noisy behavior. Prefer fixing the cause or handling expected `IgnoreMessageEvent`
signals explicitly.
