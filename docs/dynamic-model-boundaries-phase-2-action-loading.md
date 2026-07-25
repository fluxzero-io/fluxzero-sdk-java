# Phase 2 action loading decisions and measurements

This report records action-scoped target resolution and loading decisions for
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
deduplicated by that string alone; requesting the same global identity as incompatible model types fails the action.
No `@ParentId`, relation, parent, sibling, child, or graph metadata participates in target resolution.

An instance model handler contributes its receiver as a read dependency. An apply returning that receiver makes it a
read/write target. An external apply with one parameter of its return model type makes that parameter the write target.
If an external apply has multiple qualified parameters of the return type, all are direct preloaded reads and the
non-null returned model's `@EntityId` selects the write after invocation. Until another unambiguous target is supplied,
such an apply may not return `null`, because there would be no way to identify which candidate should be deleted.

The plan only produces deduplicated load descriptors. Batched repository I/O and pinning one `readStateIndex` remain
part of the integrated action repository/protocol slice.

## Action context and injection

`ModelActionContext` is the immutable begin-state returned by one future batch load. It accepts exactly the IDs in the
target resolution, validates each loaded entity's exact ID and compatible model type, rejects unrelated loaded state,
and exposes one `readStateIndex` for the complete set. It does not inspect or load relationships.

A dedicated model parameter resolver injects from this context into `@AssertLegal`, `@InterceptApply`, and `@Apply`.
Both model values and `Entity<Model>` are supported. An empty entity can be injected as an `Entity<Model>` so creation
and missing-state rules remain explicit; an absent non-nullable model value does not make a handler applicable.
`@Association` selects a same-type model through the source property already recorded by target resolution. Without a
qualifier, the canonical `@EntityId` property wins, then a single compatible direct target; multiple candidates fail.

The resolver is deliberately not added to the existing aggregate `DefaultEntityHelper`. The model action engine will
construct its own resolver chain and execute with the context-bearing message active. This preserves the Phase 1
decision that legacy aggregate matcher discovery must not construct `ModelMetadata`.

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

The first action-context lookup used stream/list materialization and then a capturing error supplier; its successful
lookups allocated 24 bytes. The retained indexed lookup precomputes entity-ID property names when the context is built:

| Context lookup | Median | Allocation |
|---|---:|---:|
| one model, automatic target | 2.73 ns/op | 0 bytes/op |
| two same-type models, qualified target | 4.59 ns/op | 0 bytes/op |

This is a local microdiagnostic, not the Phase 0 end-to-end storage/load gate. The launcher and temporary classpath file
were removed after recording the result.
