# Model publication and history

`EventPublication` determines whether an apply produces an event. Models default to `IF_MODIFIED`; an unchanged
value produces no event. Choose `ALWAYS` only when a no-op is an intentional domain occurrence. `NEVER` can change
DOCUMENT-loaded state without an event, but cannot discard the events needed to reconstruct event-sourced state.

`EventPublicationStrategy` determines the destination of an accepted event:

| Strategy | Model stream | Global event log |
|---|---|---|
| `STORE_AND_PUBLISH` | Stored | Published |
| `STORE_ONLY` | Stored | Not published |
| `PUBLISH_ONLY` | Not stored | Published |

An event-sourced Model cannot change reconstructible state with `PUBLISH_ONLY`; that route only permits an unchanged
result. A DOCUMENT-loaded Model can change its current state under that policy. Do not assume every combination has
identical behavior for both load strategies. Storing some events for a DOCUMENT-only Model does not turn it into a
supported historical archive.

Use injected `Graph<T>` to inspect an event's before/after state. `previous()` returns a preceding view or `null`;
`previousValue(...)` selects a prior field and `hasChanged(...)` compares it. Retain `EVENT_SOURCED` for every historical
node you inspect. A sole-Graph change handler receives a before boundary for the whole affected graph; ordinary
`revisions()` follows the selected Model's own revisions, not every descendant's update.

Test state reconstruction separately from global publication. Include unchanged results, first creation, logical
deletion, `STORE_ONLY` updates and a cold reader. Read Temporal Graphs for traversal and complete-change examples.
