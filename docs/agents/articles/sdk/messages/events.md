# Model Events And Notifications

Events are handled asynchronously. Usually the flow is: `Command -> @Apply -> Event payload`, or when an event is
published explicitly via `Fluxzero.publishEvent(...)`.

#### @HandleEvent

Used for side effects like sending emails or updating secondary projections within a specific context.

[//]: # (@formatter:off)
```java
@Component
@Consumer(name = "analytics")
class AnalyticsHandler {
    @HandleEvent
    void handle(CreateOrder event,
                Order order,
                Graph<Order> graph) {
        // order/graph are exact state after this model event.
    }
}
```
[//]: # (@formatter:on)

Directly addressed models and their parents, grandparents or further ancestors can be injected as `T` or `Graph<T>`.
For events and notifications carrying a model-commit boundary, Fluxzero loads the exact historical model state and
relations for that event. Use `@Association("property")` to select another payload or metadata ID or to qualify an
ancestor path; add `excludeMetadata = true` to require the payload. `Graph<T>` can be empty after logical deletion;
bare non-null `T` only matches a present model. Ordinary indexed events without a model-commit boundary resolve
directly addressed Models at one current pinned boundary. If an Aggregate-to-Model migration linked that global event,
the same parameters resolve its exact historical Model boundary. During live catch-up, configure the owning
`ModelRepository` with `followPublishedEventMigration(theStableConsumerName)`: only a missing mapping consults the
durable consumer position, waits while it is behind and retries the exact boundary after catch-up.

The same parameters work in command, query, schedule, result, error, metrics, document, custom and web handlers when
their payload or metadata addresses at least one model. Those non-event handlers use one current handler load context.
Event-sourced models share its pinned repository boundary; document-loaded models remain current-only direct-document
reads.

#### @HandleNotification

Enables handling ALL events of a filtered type across all message segments. This is often used for global statistics
collection or broadcasting updates over WebSockets.

[//]: # (@formatter:off)
```java
@Component
class GlobalStatsHandler {
    @HandleNotification
    void handle(CompletePayment event) {
        // Collect statistics globally
    }
}
```
[//]: # (@formatter:on)

<a name="specialized-handlers"></a>
