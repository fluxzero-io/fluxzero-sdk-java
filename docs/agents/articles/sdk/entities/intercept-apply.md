Use `@InterceptApply` to transform an update before legality checks and state application. It is orchestration around a
transition, not the deterministic transition itself.

## Return contract

An interceptor may return:

| Result | Effect |
| --- | --- |
| the original update | continue unchanged |
| a replacement with another payload type | restart interception for the replacement type |
| a replacement of the same payload type | continue with that replacement without invoking the same interceptor again |
| `null`, `void`, empty `Optional`, empty collection/stream | suppress this update |
| `Optional`, collection, or stream | emit zero or more updates in order |

Fluxzero recursively inspects a transformed value when its payload type changes. It does not repeatedly invoke an
interceptor merely because a new instance of the same payload type was returned. Avoid type cycles such as A rewriting
to B while B rewrites back to A.

## Rewrite or suppress

```java
record ChangeLabel(ItemId itemId, String label) {
    @InterceptApply
    Object normalizeOrSuppress(Item current) {
        String normalized = label.strip();
        if (normalized.equals(current.label())) {
            return null;
        }
        return new RecordLabelChange(itemId, normalized);
    }
}
```

Because `RecordLabelChange` is another payload type, the replacement goes through matching `@InterceptApply` methods
for that type, then `@AssertLegal`, then `@Apply`. A same-type normalized replacement proceeds directly to legality
and apply handling. Do not perform the state mutation in the interceptor.

Only effective payloads run assertions: a retained payload runs its matching assertions, suppression runs neither
assertions nor apply methods, and a replacement runs only assertions matching the replacement. An assertion that
matches only the original type does not run after rewriting. Put an invariant that must survive transformation on
the replacement or in shared/entity-side assertion logic that also matches it.

`@AssertLegal(afterHandler = true)` keeps its deferred handler-completion timing. Do not reinterpret it as an immediate
assertion merely because interception occurred.

## Expand one accepted update

```java
record ApplyLabels(ItemId itemId, List<String> labels) {
    @InterceptApply
    List<RecordLabelChange> expand() {
        return labels.stream()
                .map(String::strip)
                .distinct()
                .map(label -> new RecordLabelChange(itemId, label))
                .toList();
    }
}
```

Expanded updates run their matching immediate assertions and apply methods sequentially in encounter order in the
same loaded aggregate/member context; later updates observe state
produced by earlier ones. This gives one aggregate update batch, not an atomic transaction across aggregate roots.

## Parameter and side-effect rules

Interceptor parameters can include current member/root/ancestor entities, the update when declared on the entity side,
metadata, message, time, and user context. A non-null current-entity parameter means the interceptor is skipped when
that entity is absent; mark it nullable only when create/upsert behavior intentionally handles absence.

Interceptors may load/query to decide how to rewrite an update, but must not dispatch updates or external effects.
Use an ordinary handler or post-commit consumer for those effects. Prefer `EventPublication.IF_MODIFIED` over a custom
no-change interceptor when equality-based event suppression is sufficient.

Test the original input through the command/update boundary and assert the exact final events and state. A direct call
to the interceptor does not prove recursive transformation, legality ordering, member routing, or persistence.
