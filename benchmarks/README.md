# Fluxzero CI performance scenarios

This module contains a small set of application-shaped JMH scenarios. They are intentionally broad: a failure says
that a representative SDK hot path regressed, not which internal unit caused it.

The scenarios cover outbound dispatch; local command, self-query, and standalone query handling; self-tracked,
standalone, and stateful tracked handling; entity injection in tracked event handlers; and an event-sourced aggregate
lifecycle. Network and external storage are replaced with deterministic in-memory boundaries so GitHub-hosted runners
measure SDK work instead of I/O variance.

Reported time and allocation are normalized to one representative operation:

- outbound dispatch: one event within a 32-event batch;
- local command handling: one command through the public gateway and default local handler stack;
- local self handling: one query whose payload contains a no-argument `@HandleQuery` method;
- local standalone handling: one query routed to a multi-method local query handler;
- self-tracked command handling: one concrete command handled by an `@HandleCommand` method on its `@TrackSelf`
  interface, within a 32-command batch;
- standalone tracked command handling: one command routed to a multi-method handler within a 32-command batch;
- standalone tracked event handling: one event routed to a multi-method, multi-parameter handler that injects either
  `Entity<T>` or `T`, within a 32-event batch;
- mixed tracking handling: one message handled by standalone and `@Stateful` handlers within a 32-message batch;
- aggregate lifecycle: one load that replays 50 events, applies three new events, and commits them.

Build and run the scenarios locally with:

```shell
./mvnw -Pbenchmarks -pl benchmarks -am -DskipTests package
java -jar benchmarks/target/fluxzero-benchmarks.jar -prof gc
```

The pull-request job builds the same benchmark sources against the pull-request base and the proposed SDK, runs the resulting
jars in ABBA order on one runner, and blocks material time or allocation regressions.
