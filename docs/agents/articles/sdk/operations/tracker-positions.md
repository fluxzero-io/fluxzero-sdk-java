Use the public `TrackingClient` only for deliberate observation, recovery, or replay of one message log. Normal
application consumption belongs in `@Consumer` configuration. A new projection should normally receive a new consumer
name and `minIndex`, not mutate another consumer's durable position.

## Select the exact log

Access the configured, namespaced client through the running Fluxzero instance:

```java
TrackingClient events = Fluxzero.get().client().getTrackingClient(MessageType.EVENT);
Position position = events.getPosition("projection-v3");
```

`DOCUMENT` and `CUSTOM` logs require the topic/collection overload
`getTrackingClient(messageType, topic)`. Selecting the wrong message type or topic means inspecting or changing a
different position store. Do not construct `ResetPosition`, `StorePosition`, or other wire commands directly.

## Read without changing a consumer

- `getPosition(consumer)` returns the most recently committed index ranges per segment.
- `readFromIndex(minIndex, maxSize)` reads serialized messages for diagnostics without advancing a consumer.
- `readRange(minInclusive, maxExclusive, maxSize)` bounds that inspection; use the byte-limited overload for logs with
  potentially large payloads.

Direct reads bypass handler invocation and consumer configuration. They are suitable for inspection or a controlled
export, not for silently reimplementing tracking in application code.

## Reset safely

`resetPosition(consumer, lastIndex)` forcibly sets the same last-processed index for every segment. Messages whose
index is greater than `lastIndex` become eligible for processing again. It can rewind or advance a consumer.

Use this sequence:

1. Confirm the namespace, message type/topic, durable consumer name, intended first reprocessed message, and current
   position.
2. Stop every application instance that owns the consumer, or disconnect its active trackers. Otherwise a concurrently
   running tracker can commit a newer position after the reset.
3. Confirm that every handler effect in the replay window is idempotent, deduplicated, compensatable, or explicitly
   approved for repetition.
4. Reset to the index immediately before the first message that should run again and wait for `Guarantee.STORED`.
5. Read back the position before restarting consumers.
6. Observe handler/batch metrics and verify the rebuilt state or corrected effect.

Do not infer a reset index from wall-clock time with hand-written bit shifting. Use `IndexUtils.indexFromTimestamp(...)`
when a time boundary is the source of truth, then account for the exclusive `lastIndex` meaning deliberately.

## Disconnect and manual position storage

`disconnectTracker(consumer, trackerId, sendFinalEmptyBatch)` removes one active tracker from its claimed segment. It
does not delete the durable consumer position and a running SDK client may reconnect. `ProcessBatchEvent` exposes the
consumer and tracker ID needed to identify active work. Prefer stopping the owning application for a stable maintenance
window; use tracker disconnection for controlled handoff or recovery.

`storePosition(consumer, segment, lastIndex)` is lower level than reset. Normal trackers use it to advance a segment.
An operator call can skip unprocessed messages, so use it only when the skipped range has been independently examined
and explicit data-loss approval exists. Read the position back and retain an audit record of the old and new ranges.
