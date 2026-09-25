# Model erasure

Use a domain command returning `null` for logical deletion that retains history. Permanent erasure is a separate,
destructive operation through `ModelRepository` in the selected namespace. Confirm the intended identity, environment
and retention scope before execution.

For exactly one Model:

```java
ModelDeletionResult result = Fluxzero.get().modelRepository()
        .deleteModel(modelId, ModelDeletionCascade.NONE).join();
```

Use `io.fluxzero.common.api.modeling.ModelDeletionCascade` and `ModelDeletionResult`.

For descendants, first create a non-mutating plan with `planDeletion(modelId, ModelDeletionCascade.DESCENDANTS)`.
Inspect the plan and confirm that exact scope before calling `deleteModel(plan)`. A descendant cascade without a
confirmed plan is rejected. Use the overload accepting a stable deletion ID when the procedure needs resumable,
idempotent execution.

Erasure removes owned Model persistence, direct documents, cache and relationships. It does not remove globally
published events or undo an external effect. Inspect dependent projections, schedules, vault data and external
records separately. Logical cascades retain deleted-parent lineage for planning; earlier ordinary moves/detachments
are not silently included in that deleted tree.

Verify the returned result and public after-state, including descendants and the absence of stale projections that
could mislead readers. Never approximate Model erasure by deleting an event log, editing managed storage tables, or
removing a runtime workload. Read the Model deletion article for lifecycle and retained-history boundaries.
