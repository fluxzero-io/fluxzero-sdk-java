Use these controls when an applied update needs non-default publication or when a handler/query must compare current
aggregate state with a reconstructed previous version. Keep ordinary aggregate updates on the default stored-and-
published path.

## Separate change detection from publication destination

`EventPublication` decides whether a logical update creates an event at all, for example `IF_MODIFIED` suppresses an
event when the resulting state is equal. `EventPublicationStrategy` decides where an accepted update goes:

| Strategy | Event store | Global event log | Aggregate state effect |
| --- | --- | --- | --- |
| `STORE_AND_PUBLISH` | stored | published | advances state |
| `STORE_ONLY` | stored | not published | advances state |
| `PUBLISH_ONLY` | not stored | published | does not advance an event-sourced aggregate's reconstructible state |

`STORE_ONLY` is suitable for a deliberately silent stored migration or internal state transition whose absence from
tracked event consumers is part of the contract. `PUBLISH_ONLY` is a notification-like applied update: on an
event-sourced aggregate it cannot advance cached, snapshot, relationship, or searchable aggregate state because replay
could not reconstruct that change. Do not use either strategy merely to hide a side effect or repair handler ordering.

Test aggregate reconstruction separately from tracked event publication. An assertion on only the returned in-memory
entity can miss both storage and publication mistakes.

## Understand apply compatibility checks

A creation-shaped `@Apply` method that has no current-state parameter rejects an already existing entity. An
update-shaped method with a required current-state parameter rejects a missing entity. The SDK exposes these categories
as `Entity.ALREADY_EXISTS_EXCEPTION` and `Entity.NOT_FOUND_EXCEPTION`; application code should normally assert the
business outcome rather than compare singleton exception instances.

Use a nullable state parameter for an intentional create-or-update path. `@Apply(disableCompatibilityCheck = true)` is
an advanced escape hatch that removes the automatic signature/state compatibility guard; it does not make a method
safe for both states. The method must handle every allowed state explicitly and its tests must cover missing, existing,
duplicate, and replayed inputs.

## Inspect previous state without inventing duplicate events

An injected `Entity<T>` wrapper exposes `previous()` for the preceding reconstructed version and
`playBackToCondition(...)` for walking history to a condition. `previous()` returns `null` at the first known version.
Use this when a read or tracked observer needs to calculate a state delta from the stored history.

Do not emit a second generic "changed" event only to rediscover the prior value: the applied payload already is the
event by default, and aggregate history contains earlier state. Also do not call history APIs from a pure `@Apply`
method; inject the needed state or inspect history at the orchestration/query boundary.

For a history-dependent behavior, test the first version, one prior version, multiple updates, member history,
store-only updates, and synthetic reconstruction in a new fixture. Keep a separate assertion for whether each update appears in
the tracked event channel.
