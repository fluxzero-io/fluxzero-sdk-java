Use these handlers only when their message log matches the objective. Ordinary application behavior should still start
with command, query, event, schedule, or web handlers.

## Notifications reach every matching application instance

`@HandleNotification` handles `MessageType.NOTIFICATION`. It observes matching messages across all segments and uses a
client-controlled index rather than an ordinary durable consumer position. This fits in-process broadcast to every
connected application instance, including per-session WebSocket delivery. It is not a substitute for one durable
projection that must process each event once.

```java
@HandleNotification(allowedClasses = ViewChanged.class)
void broadcast(ViewChanged notification) {
    session.sendMessage(notification);
}
```

Keep the payload type or `allowedClasses` narrow. Test at least two application/socket instances when the contract says
every connected instance receives the notification.

## Document handlers observe collection updates

`@HandleDocument` consumes `MessageType.DOCUMENT` for one collection. Select it by:

- `@HandleDocument("collection-name")` for an explicit collection;
- `@HandleDocument(documentClass = ProjectionView.class)` to derive it from `@Searchable` or the class name;
- bare `@HandleDocument` to infer from the first handler parameter.

Document tracking is last-state oriented. When a tracker is behind, an older intermediate representation can be
skipped and the handler receives the most recent document for the index. Do not model a workflow that requires every
historical transition as a document handler; use events for that.

A returned higher-revision document can update the same stored document during a controlled rebuild. Pair it with a
new consumer name, a deliberate replay boundary, and a reconstruction/search test.

## Result and web-response handlers are advanced

`@HandleResult` consumes result-log messages and `@HandleWebResponse` consumes responses emitted for web requests.
Most request callers should await the typed `CompletableFuture` or use the high-level gateway callback instead of
building a second result consumer.

Use these annotations for a deliberate durable observer, audit, or asynchronous integration protocol:

```java
@Component
@Consumer(name = "integration-response-observer")
final class IntegrationResponseObserver {
    @HandleWebResponse
    void on(WebResponse response) {
        // Observe a safe status/correlation signal; do not duplicate business decisions.
    }
}
```

Result and response logs can replay. Any follow-up side effect must be idempotent and correlated to the original
request. A transport acknowledgement is not automatically a business completion event.

## Multi-payload handlers

`allowedClasses` is appropriate for a genuinely generic action such as one cleanup method for several terminal facts.
If the method has no payload parameter, list every supported type explicitly. Prefer one typed method per behavior when
the payloads require different validation, correlation, or effects.
