Message-log retention and truncation are application operations only where the runtime permits them. For Fluxzero
Cloud applications, manage custom topics through the SDK and treat retention for standard command, query, event,
result, error, metrics, scheduling, and web logs as managed platform policy unless the product contract explicitly
exposes a change.

## Custom-topic retention

```java
GenericGateway gateway = Fluxzero.get().customGateway("operator-audit-v2");
gateway.setRetentionTime(Duration.ofDays(45), Guarantee.STORED).join();
```

Retention controls how long messages remain available for tracking and replay; it is not an immediate deletion
deadline and platform policy may affect physical eviction. Before shortening retention, compare it with the oldest
required replay/recovery window and every consumer position. Verify the update through metrics and a bounded historical
read rather than assuming a fixed default.

Search audit collections use `DocumentStore.createAuditTrail(...)`, not message-log retention. Keep that separate from
custom-topic retention because the query and pruning behavior differs.

## Truncation

`Fluxzero.get().customGateway(topic).truncate()` deletes the custom message log and all associated durable tracking
positions. The runtime disconnects active trackers before clearing the log and positions. This is materially broader
than resetting one consumer.

Require explicit approval naming the namespace and topic. Before truncation:

- stop all producers and consumers for the topic;
- capture the required export or confirm that no retained message is needed;
- record all consumer positions and dependent projections;
- define how consumers and projections will be initialized after positions disappear.

After the stored acknowledgement, verify that the log is empty, positions are new, and restarted consumers do not
silently assume their old projection state is synchronized. The runtime normally rejects truncation for standard logs;
never try to bypass that policy with database or platform access.
