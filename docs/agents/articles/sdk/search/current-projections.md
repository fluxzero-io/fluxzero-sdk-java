# Current searchable projections

Choose representation, trigger, persistence and read boundary separately:

| Choice | Use when |
| --- | --- |
| Model search | The indexed Model already contains the needed fields. |
| Graph search, optionally ASYNC/AWAIT | The result is the composed domain graph. Materialization saves composition work, not business calculations or clock/dependency invalidation. |
| `@Stateful` read model | A custom searchable summary has a lifecycle maintained by handlers. The SDK owns replacement and deletion. |
| Plain record + manual indexing | An explicit writer owns the document lifecycle. Await storage and configure failure handling. |

Use `@HandleDocument Graph<T>` to observe current root/descendant state when intermediate representations may be skipped. Graph materialization is not required. Use events for every-transition reactions, including source deletion. An ancestor's content change does not itself notify a child's Graph subscription. A Graph property is not a dependency subscription.

A custom row is useful when derived stage, eligibility or financial signals must be filtered/sorted **before** pagination and count. Do not move those calculations after selecting a page. Display-only calculations can be made for a bounded result page.

## One writer, several invalidation sources

For a projection refreshed by documents, deletion events and schedules, publish a small refresh event from each source. Only one EVENT consumer writes the row. `singleTracker = true` on a class does not serialize different message-type streams into one writer. The refresh payload identifies the source; it does not carry an old snapshot to install.

```java
record RefreshProject(ProjectId projectId) {}

@Consumer(name = "project-overview-signals", errorHandler = ForeverRetryingErrorHandler.class)
class ProjectUpdates {
    @HandleDocument void changed(Graph<Project> graph) {
        if (graph.get() != null) Fluxzero.publishEvent(new RefreshProject(graph.get().projectId()));
    }
    @HandleEvent void deleted(Graph<Project> graph) {
        if (graph.get() == null && graph.previous() != null && graph.previous().get() != null)
            Fluxzero.publishEvent(new RefreshProject(graph.previous().get().projectId()));
    }
    @HandleSchedule void due(RefreshProject refresh) { Fluxzero.publishEvent(refresh); }
}

@Stateful(collection = "project-overviews")
@Consumer(name = "project-overview-writer", singleTracker = true,
          errorHandler = ForeverRetryingErrorHandler.class)
record ProjectOverview(@EntityId @Association ProjectId projectId,
                       @Association PolicyId policyId, String name, String policyName) {
    @HandleEvent static ProjectOverview create(RefreshProject event) { return read(event.projectId()); }
    @HandleEvent ProjectOverview refresh(RefreshProject event) { return read(event.projectId()); }
    @HandleEvent ProjectOverview policyChanged(SetPolicy event) { return read(projectId); }

    static ProjectOverview read(ProjectId id) {
        var graph = Fluxzero.loadCurrentGraph(id).strict(true);
        if (graph.get() == null) return null;
        var project = graph.get();
        var policy = Fluxzero.loadGraphAt(project.policyId(), graph.stateIndex()).strict(true).get();
        return new ProjectOverview(id, project.policyId(), project.name(), policy.name());
    }
}
```

`SetPolicy` has a `policyId` property; the matching field association deliberately refreshes every dependent row and no other policy's rows. Domain Models remain authoritative; commands never validate against this eventually consistent summary. Register the observer and Stateful type through ordinary production discovery, and explicitly register both in a fixture.

The static factory creates a missing row. The instance handler replaces it, or deletes it by returning null if the source is gone. A stale refresh therefore cannot resurrect a deleted source. A static `@Association(always = true)` factory can upsert a stable ID, but it has no loaded current entry: returning null there does **not** delete an old row. Do not use it as a complete lifecycle recipe.

`publishEvent` participates in tracked outgoing-write completion (`awaitOutgoingWrites` defaults to true); keep that boundary and a retry policy on the source consumer. An event can be emitted again after failure, so replacement must be idempotent. Stateful repository writes are awaited by default. Use one writer for the same collection; do not run a separate replay writer concurrently. Global single tracking is the conservative example; scale with proven per-row routing across every invalidation source, not independent writers.

## Pin source reads and make assessments pure

`loadCurrentGraph` pins a fresh current boundary outside a mutation. Navigation shares it, but a separate static `loadGraph` still inherits its own ambient handler context. It does not inherit the current Graph merely because it is called from the same helper. Load unrelated typed IDs with `loadGraphAt(id, graph.stateIndex())`, or pass already collected values to pure assessment functions. Do not reopen independently current factories inside every helper and call that one snapshot.

The example uses strict reads and event-sourced Models with retained history. DOCUMENT-only state cannot reconstruct an overwritten revision; strict reads can fail rather than mix versions. Ordinary historical fallback is not a snapshot guarantee. This read-only projection recipe must not replace mutation reads: commands need their active attempt's staged state and tracked dependencies, not a separately pinned historical factory.

Collect shared facts once per logical refresh where they are needed for many rows. Keep that collection separate from per-row assessment. Associations remove manual lookup plumbing, but they do not make repeated full-customer scans cheap. Subscribe to the narrow dependency that changes the result; a parent Graph subscription includes **all** descendants and can accidentally turn N insertions into quadratic work. Preserve real cross-row effects and version-bound decisions when narrowing triggers.

## Time, deletion and rebuilding

For display-only expiration, store the deadline and compare it with query time. For indexed time-dependent fields, schedule a refresh under a stable ID at the relevant boundary; the schedule handler emits into the same writer stream. The writer replaces/cancels that schedule idempotently. Schedule storage and row storage are separate effects: a failed row write must retry, and repeated refreshes must converge to one row and one deadline. Do not treat materialization as clock refresh.

Rebuild by sending refreshes for source IDs and stored row IDs, including rows whose source no longer exists. A source-only scan cannot remove orphan rows. Bound the scan, use durable continuation for a large cohort, and use the same writer rather than a second indexer. Changes during enumeration require a defined reconciliation/catch-up boundary; offset paging is not a point-in-time cohort. Retained Models, not the projection, supply the facts. Rebuild after schema/index changes deliberately; annotation changes do not backfill old documents.

## Qualify the complete recipe

`CurrentProjectionRecipeTest` exercises root changes, child moves, source deletion, delayed/repeated refreshes, cold reconstruction, stale-row cleanup, scheduled expiration, shared policy invalidation and a helper reading an older pinned boundary after a concurrent commit. Synchronous and asynchronous variants cover the lifecycle; a storage-failure test proves retry and no consumer advance past the failing input before storage succeeds. `StatefulGraphProjectionTest` separately characterizes direct Graph-document factory replacement; it is not the complete lifecycle example.

Inject automatic Stateful persistence failure at the storage client used by `HandlerRepository`. A spy on the application-facing `Fluxzero.documentStore()` is not a replacement for every already-wired internal repository dependency. Install stubs in the when phase: the fixture resets spies before that phase. For failure after earlier successful inputs, permit positions below the failing index and forbid positions at or beyond it; `never storePosition` is appropriate only for the first isolated input.

Keep the original large pagination and recovery scenarios. Add operation-count assertions for unrelated changes and genuine shared dependencies; measure unchanged workloads after correction. Do not hide repeated calculation behind `commitInBatch`: batching can reduce writes, not the work done to produce them.
