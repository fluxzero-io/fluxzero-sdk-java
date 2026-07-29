# Phase 18 — Local graph parity and model-cache controls

## Outcome

`LocalClient`, synchronous `TestFixture` executions and the websocket test server now exercise the same observable
model-graph completion contract as a runtime-backed application. A command whose effective graph completion policy is
`AWAIT` does not complete until the affected local materialized root documents contain the committed state.

`@Model(searchable = false)` now controls only whether that model has an independently searchable collection. An
explicit `@ParentId(path = "...")` separately opts the child into automatic graph composition. Such a child supplies
its current document through the reserved `$modelGraphComponents` collection, while a non-searchable model without an
explicit path retains the zero-document-write path.

## Local projection contract

The local event and search stores are linked through a narrow materialization callback:

- model commits retain compact affected-model signals;
- direct document and snapshot materialization completes first;
- registered projections resolve affected roots at both the old and new relationship boundary;
- the shared `ModelGraphDocumentStitcher` composes bounded current documents;
- projection-local path overrides and per-root state fences are retained;
- positions and failures are reported honestly and `AWAIT` futures complete only after their requested boundary.

Moves therefore refresh both the former and new root. Logical deletion removes the child document and refreshes its
former root. Rebuilds enumerate current typed roots and remove projection documents for roots that no longer exist.
Exact model-to-document collection resolution prevents a materialized root document from colliding with the direct
root document that has the same model ID.

The local implementation is currently eager once direct documents are visible and deliberately does not introduce a
background thread or polling loop. That eagerness is not the `ASYNC` contract: an asynchronous fixture does not issue
an await request and tests must not assume graph visibility at command-result time. An explicit application-, consumer-,
root- or apply-level `AWAIT` holds the command result until the requested graph boundary is confirmed.
`TestFixture.createAsync()` retains the production default `ASYNC`; it does not silently install an application-wide
await policy.

## Cache controls

`FluxzeroBuilder.withModelCache(Cache)` selects a cache for independent models without changing aggregate or
relationship caches. `disableAutomaticModelCaching()` installs the no-op model cache and therefore also suppresses the
model cache tracker. `DefaultFluxzero.Builder.disableAutomaticTracking()` now includes this model-specific switch.

The two new methods are default interface methods so existing custom `FluxzeroBuilder` implementations remain binary
compatible. A distinct effective model cache is rebuilt, instrumented and closed independently; an inherited shared
cache remains one object and is closed once.

## Performance and storage assessment

The production runtime graph worker and JDBC schemas are unchanged. The only production write-path expansion is
intentional and opt-in: a non-searchable model with an explicit graph path now writes one current document to the
shared internal graph-component collection. Non-searchable models without a path still emit no document mutation.
Searchable models retain their existing direct collection and write behavior.

The in-memory worker performs graph discovery and stitching only for registered projections. It has no database,
network, timer or polling cost and is confined to local/test-server use. Exact collection lookup is one map lookup per
graph model and avoids scanning all in-memory collections when the resolver is available.

## Verification

Focused coverage exercises:

- a root annotation with `DEFAULT` inheriting application-level `AWAIT`;
- an asynchronous fixture retaining default `ASYNC` without an await request;
- an asynchronous fixture explicitly opting into `AWAIT` and waiting exactly once;
- immediate materialized and virtual graph search after command completion;
- a non-searchable child included through its explicit path but absent from its own collection;
- child moves updating both roots;
- logical child deletion;
- graph path overrides and stale projection fences;
- the no-path zero-document fast path;
- dedicated and disabled model-cache selection;
- the complete websocket test-server commit, materialization, await and graph-search round trip.

The final evidence commands and reactor results are recorded in the Phase 18 backlog entry.

## Deliberately deferred

Migration tooling from `@Aggregate` to `@Model` remains a separate design and implementation phase. This phase does not
change aggregate persistence formats or runtime JDBC behavior.
