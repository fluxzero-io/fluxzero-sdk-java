# Phase 2 commit loading decisions and measurements

This report records commit-scoped target resolution and loading decisions for
[Dynamic model boundaries](dynamic-model-boundaries-backlog.md).

## Direct target resolution

`ModelTargetResolver` compiles an immutable plan for a payload type and the model-aware handlers selected for that
payload. Structural payload metadata and compiled readers are owned by `ReflectionUtils.TypeMetadata`; message
resolution does no reflective discovery.

For every model receiver, injected model parameter, and apply return target:

1. A parameter-level `@Association("propertyName")` explicitly selects that direct payload property.
2. Otherwise, a property whose name equals the model's `@EntityId` property wins.
3. When that name is absent, exactly one payload `Id<Model>` property is accepted regardless of its property name.
4. Zero candidates or multiple typed candidates fail plan compilation with the missing/ambiguous properties and a
   suggested `@Association` fix.

Resolved values use exactly `ID.toString()`. Null IDs and collection/map/array IDs fail before loading. Identities are
deduplicated by that string alone; requesting the same global identity as incompatible model types fails the commit.
No `@ParentId`, relation, parent, sibling, child, or graph metadata participates in target resolution.

An instance model handler contributes its receiver as a read dependency. An apply returning that receiver makes it a
read/write target. An external apply with one parameter of its return model type makes that parameter the write target.
If an external apply has multiple qualified parameters of the return type, all are direct preloaded reads and the
non-null returned model's `@EntityId` selects the write after invocation. Until another unambiguous target is supplied,
such an apply may not return `null`, because there would be no way to identify which candidate should be deleted.

The plan only produces deduplicated load descriptors. Batched repository I/O and pinning one `readStateIndex` remain
part of the integrated commit repository/protocol slice.

## Commit context and injection

`ModelCommitContext` is the immutable begin-state returned by one future batch load. It accepts exactly the IDs in the
target resolution, validates each loaded entity's exact ID and compatible model type, rejects unrelated loaded state,
and exposes one `readStateIndex` for the complete set. It does not inspect or load relationships.

A dedicated model parameter resolver injects from this context into `@AssertLegal`, `@InterceptApply`, and `@Apply`.
Both model values and `Entity<Model>` are supported. An empty entity can be injected as an `Entity<Model>` so creation
and missing-state rules remain explicit; an absent non-nullable model value does not make a handler applicable.
`@Association` selects a same-type model through the source property already recorded by target resolution. Without a
qualifier, the canonical `@EntityId` property wins, then a single compatible direct target; multiple candidates fail.

The resolver is deliberately not added to the existing aggregate `DefaultEntityHelper`. The model commit engine will
construct its own resolver chain and execute with the context-bearing message active. This preserves the Phase 1
decision that legacy aggregate matcher discovery must not construct `EntityMetadata`.

## Hot-path diagnostic

A disposable ten-fork JVM diagnostic measured a retained compiled plan with two million resolutions per fork and used
thread-allocation counters. The result escaped through a volatile sink.

The first implementation used linked maps and sets for every resolution:

| Scenario | Approximate median | Allocation |
|---|---:|---:|
| one receiver model | 143 ns/op | 1,376 bytes/op |
| two cross-model targets | 225 ns/op | 2,056 bytes/op |

That allocation was rejected. The retained implementation uses an allocation-light single-target path, linear
small-cardinality deduplication, compact access flags, and only allocates deferred-write bookkeeping when needed:

| Scenario | Median | Allocation |
|---|---:|---:|
| one receiver model | 13.3 ns/op | 104 bytes/op |
| two cross-model targets | 73.4 ns/op | 504 bytes/op |

The first commit-context lookup used stream/list materialization and then a capturing error supplier; its successful
lookups allocated 24 bytes. The retained indexed lookup precomputes entity-ID property names when the context is built:

| Context lookup | Median | Allocation |
|---|---:|---:|
| one model, automatic target | 2.73 ns/op | 0 bytes/op |
| two same-type models, qualified target | 4.59 ns/op | 0 bytes/op |

This is a local microdiagnostic, not the Phase 0 end-to-end storage/load gate. The launcher and temporary classpath file
were removed after recording the result.

## Deterministic commit evaluation

`ModelCommitEngine` is a dedicated, side-effect-free evaluator. It is not installed in the legacy aggregate helper and
does not publish or persist anything. A future runtime-backed model repository may therefore commit only a successfully
returned commit result.

For one original event/substep:

1. all applicable before-assertions read the immutable substep begin-state in priority order;
2. every applicable apply reads that same begin-state, including applies targeting different models;
3. only models actually returned by applies produce transitions;
4. duplicate writes to one exact model ID fail the substep;
5. all transitions become visible together;
6. `afterHandler` assertions read the resulting state and may still reject the complete substep.

A non-null result selects its target by the returned model's exact `@EntityId`. A null result selects the unambiguous
receiver/direct parameter target, records a logical-delete transition, and retains the original message as the event.
An ambiguous null result with two deferred same-type candidates fails. Read-only injected models never produce a
transition.

Interceptor output is evaluated as ordered substeps under one pinned `readStateIndex`. Every output is target-resolved
again, allowing it to introduce new direct targets. Prior successful substep values overlay repository state before a
later substep runs, so two emitted events for one model observe each other in order. Same-payload-type replacement is
intercepted once and then applied, matching the existing aggregate stabilization rule; changed payload types can be
intercepted recursively. Suppression emits no substep, and a hard limit prevents unbounded expansion.

That overlay is also a reconstruction invariant. The historical view of a later substep is the commit's persisted
`readStateIndex` plus earlier ordered substeps of that same commit—not all global state through the later substep's
eventual `stateIndex`. This distinction preserves the exact originally observed dependencies when the default policy
accepts stale reads. Phase 5 reconstructs and caches that commit prefix from `commitId` and ordered memberships.

One `AppliedSubstep` retains one original message plus all its target transitions. A cross-model event is therefore not
duplicated at the SDK commit boundary. Phase 3 maps that logical shape onto one global payload and lightweight
per-target stream memberships.

Any assertion, apply, interceptor, target, or state-boundary failure aborts evaluation without returning commit input.
Tests cover failure after an earlier successful substep and failure from an `afterHandler` assertion. Runtime
transactional rollback and no-store behavior remain the Phase 3 integration responsibility.

## Commit-engine hot-path diagnostic

A disposable five-fork JVM diagnostic measured warmed side-effect-free evaluation with thread allocation counters. The
first correct implementation repeatedly filtered handlers through streams and allocated 1,376 bytes even for an empty
substep; it was rejected. The retained engine compiles assertion/apply/interceptor ordering once per lifecycle-bounded
handler set and uses indexed normal paths:

| Scenario | Median | Allocation |
|---|---:|---:|
| empty substep | 19.0 ns/op | 24 bytes/op |
| one model apply/transition | 259.3 ns/op | 1,904 bytes/op |
| complete one-substep commit | 305.7 ns/op | 2,520 bytes/op |

The remaining allocation includes the returned immutable model, transition, staged entity/context, and commit result;
it is not repeated structural reflection or handler filtering. This diagnostic does not replace the Phase 0 physical
store/load, WAL, reconstruction, or mixed-traffic gates. Its source, compiled classes, and temporary classpath were
deleted after recording the retained result.

## Verification

Focused commit tests cover 39 target-resolution, context-injection, and engine scenarios. They include cross-model
begin-state isolation, same-type qualifiers, conditional handlers, receiver-side handlers, ordered interceptor
expansion, new and repeated targets, suppression, state-boundary mismatch, before/after assertions,
duplicate writes, null delete, and rollback after an earlier successful substep.

The SDK/common Javadoc build and full `./mvnw -B install` reactor passed, including 1,780 SDK tests, test-server/proxy,
annotation processing, and Java/Kotlin downstream compatibility.
