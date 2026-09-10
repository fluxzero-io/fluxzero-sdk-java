Use this article when `@Stateful` persistence depends on handler return values or nested `@Member` workflow objects.
These semantics are storage operations; test create, update, split, rekey, and delete independently.

## Parent state return semantics

| Handler result | Persisted effect |
| --- | --- |
| stateful instance of the declared type | create/update that ID |
| same-type instance with another `@EntityId` | store the new ID and delete the old ID |
| collection of same-type instances | store each; delete the current ID when omitted |
| empty collection | delete the current instance |
| `null` from a state-compatible instance method | delete the current instance |
| `void`, `Duration`, or unrelated type | no state mutation |

Returning a `Duration` can schedule a next run but does not persist a modified stateful copy. Persist the state change
explicitly in a separate state-compatible result when both are needed.

```java
@Stateful
record ReleaseProcess(
        @EntityId @Association String releaseId,
        Status status,
        @Member List<ReleaseStage> stages) {

    @HandleEvent
    static ReleaseProcess start(ReleaseStarted event) {
        return new ReleaseProcess(event.releaseId(), Status.ACTIVE, List.of());
    }

    @HandleEvent
    ReleaseProcess complete(ReleaseCompleted event) {
        return null;
    }
}
```

Do not publish a non-idempotent effect and delete the state in the same handler while claiming atomicity. Retain a
durable pending/terminal marker until the effect protocol is safely completed.

## Member creation, update, and deletion

A member can declare `@Handle...` and `@Association`. Fluxzero loads the parent, invokes every matching member, and
rebuilds the immutable parent.

```java
record ReleaseStage(
        @EntityId @Association String stageId,
        StageStatus status) {

    @HandleEvent
    static ReleaseStage add(StageAdded event, ReleaseProcess parent) {
        return new ReleaseStage(event.stageId(), StageStatus.PENDING);
    }

    @HandleEvent
    ReleaseStage finish(StageFinished event) {
        return new ReleaseStage(stageId, StageStatus.COMPLETE);
    }

    @HandleEvent
    ReleaseStage remove(StageRemoved event) {
        return null;
    }
}
```

A static member create must identify a parent through an association in the message unless
`@Association(always = true)` deliberately targets every parent. Multiple matching members can all be invoked.
Use `@EntityId` for unique member identity and `@Association` for non-unique correlation keys.

For map-backed members, Fluxzero uses the member `@EntityId` or `@Member(idProperty = "...")` as the key. Record
members can be rebuilt with their canonical constructor; use an explicit/generated wither (for example Lombok
`@With`) or `@Member(wither = "...")` only for a non-record or deliberately custom immutable update shape.

## Required tests

Cover creation under the correct parent, update, deletion, duplicate/non-unique association behavior, two parents with
interleaved messages, reconstruction, and any collection split/rekey. Query the persisted state in a later fixture
phase or fresh fixture; a returned Java object alone does not prove repository mutation.
