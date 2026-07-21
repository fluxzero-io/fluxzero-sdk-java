# Fluxzero CI performance scenarios

This module contains a small set of application-shaped JMH scenarios. They are intentionally broad: a failure says
that a representative SDK hot path regressed, not which internal unit caused it.

The scenarios cover outbound dispatch, local command handling, tracking handling, and an event-sourced aggregate
lifecycle. Network and external storage are replaced with deterministic in-memory boundaries so GitHub-hosted runners
measure SDK work instead of I/O variance.

Reported time and allocation are normalized to one representative operation:

- outbound dispatch: one event within a 32-event batch;
- local command handling: one command through the public gateway and default local handler stack;
- tracking handling: one message within a 32-message batch;
- aggregate lifecycle: one load that replays 50 events, applies three new events, and commits them.

Build and run the scenarios locally with:

```shell
./mvnw -Pbenchmarks -pl benchmarks -am -DskipTests package
java -jar benchmarks/target/fluxzero-benchmarks.jar -prof gc
```

The pull-request job builds the same benchmark sources against the pull-request base and the proposed SDK, runs the resulting
jars in ABBA order on one runner, and blocks material time or allocation regressions.
