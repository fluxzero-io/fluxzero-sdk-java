Use custom metrics for operational observations, diagnostics, and dashboards. Metrics are

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
`MessageType.METRICS` messages; they are not domain events and must never reconstruct or decide business state.

## Publish after successful domain changes

Publish a small immutable payload with `Fluxzero.publishMetrics(...)`. The call delegates to `MetricsGateway`; its
default overload uses `Guarantee.NONE`. Use `@HandleMetrics` for a metrics consumer. It is a specialization of
`@HandleMessage(MessageType.METRICS)`.

```java
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import org.springframework.stereotype.Component;

record BuildTransitionMetric(
        String pipelineId, String buildId, String previousStatus, String newStatus) {
}

@Component
@Consumer(name = "build-transition-metrics")
final class BuildTransitionMetrics {
    @HandleEvent
    void on(BuildStarted event) {
        Fluxzero.publishMetrics(new BuildTransitionMetric(
                event.pipelineId(), event.buildId(), "QUEUED", "RUNNING"));
    }
}

@Component
@Consumer(name = "build-metrics-sink")
final class MetricsSink {
    @HandleMetrics
    void on(BuildTransitionMetric metric) {
        // Forward to operational monitoring only.
    }
}
```

`@Component` is the Spring discovery mechanism; `@Consumer` configures tracked consumption but does not itself create a Spring bean. Outside Spring, register both instances explicitly on the configured `Fluxzero` client. A fixture that receives handler instances proves test registration only, not production discovery.

For a metric that describes a successful aggregate transition, publish from an event handler for the already-applied
update. Do not publish from `@Apply`: apply methods must remain pure and run again during aggregate reconstruction.
Publishing from the command handler before `assertAndApply(...)` can also report a transition that later fails.

Keep sensitive or protected request values out of the metric. Prefer stable IDs, transition names, counts, and elapsed
durations. If another component must react to the observation to enforce business behavior, model that interaction as a
command or domain event instead.

## Assert the metrics channel directly

`TestFixture` collects metrics separately from commands, events, errors, and results:

```java
fixture.whenCommand(startBuild)
        .<BuildTransitionMetric>expectMetric(metric ->
                metric.buildId().equals("build-7")
                && metric.previousStatus().equals("QUEUED")
                && metric.newStatus().equals("RUNNING"));

fixture.whenCommand(repeatedBuildStart)
        .expectExceptionalResult()
        .expectNoMetricsLike(BuildTransitionMetric.class);
```

Use `expectMetrics(...)` for inclusive payload/class matching, `expectMetric(...)` for one predicate,
`expectOnlyMetrics(...)` when every metric is part of the contract, and `expectNoMetricsLike(...)` or
`expectNoMetricLike(...)` for a targeted negative assertion. `expectNoMetrics()` also rejects SDK tracking metrics, so
it is usually too broad for an asynchronous fixture.

## Separate fixture seeding from production replay

Given-phase effects are fully processed but are not collected by the next Then phase. In particular,
`givenAppliedEvents(...)` applies and commits its supplied updates; event observers registered at that time can run and
publish hidden setup metrics. If the test is meant to prove that passive production aggregate loading emits no custom
metric, seed serialized history first, register the metrics observer afterward, then load or query the aggregate in
When.

Do not describe `givenAppliedEvents(...)` itself as passive event-store replay. A literal production-replay test stores
serialized historical events, starts the production observers, loads the aggregate in When, and asserts that loading
does not publish the custom metric.
