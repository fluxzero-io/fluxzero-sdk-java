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

## Validate the current interceptor input

Use `@InterceptApply(assertCurrent = AssertCurrent.ENABLED)` to retain legality checks for the input of that
interceptor. `AssertCurrent` is in `io.fluxzero.sdk.persisting.eventsourcing`.
`DEFAULT` follows `fluxzero.interceptApply.assertCurrent` (`FLUXZERO_INTERCEPT_APPLY_ASSERT_CURRENT`): when absent,
it is enabled from `fluxzero.defaults.version=2026.10.04` and disabled for older or absent defaults versions.
An explicit annotation choice wins over the application property, which wins over the defaults version.
Use `DISABLED`, or property `false` for unconfigured interceptors, when rewriting is deliberately allowed before
checking legality. Configuration is resolved for the owning application when its helpers/plans are created.

Current immediate assertions run before the selected interceptor, inside the same commit attempt and against its
current state. The input is checked once even when it splits into several outputs or is suppressed. Every replacement
keeps its normal checks. A bare unchanged input is not checked twice in the same scope; a new instance or message
envelope receives its own checks. In A → B → C, each interceptor controls its own current input, not always A.
Payload, Message, metadata, user and custom parameter injection use that input's context. There is no combined
original/replacement parameter. Existing nested legality checks also participate.

Retained current `afterHandler=true` assertions keep the input context and run against the final composed state:
for Models this is the end of the atomic Model operation, including automatic child deletions; for Aggregate/Entity
it is the existing handler-completion phase. Existing effective-update assertions keep their established timing.
Immediate-only Model `assertLegal` does not run after-handler assertions. Apply-only legacy paths and replay do not
start running assertions. Interceptors and assertions must remain free of external side effects.

Model assertion reads participate in the pinned commit and conflict checks. RETRY evaluates them again; an attempt
that validates a current input upgrades configured ACCEPT to FAIL, so concurrent changes cannot retain stale
permission. Other ACCEPT operations retain their behavior. S285 rules still guard effective Model mutations;
an output's Apply exception does not undo current-input checks. This requires no new Runtime protocol or Runtime
upgrade. With current-input checks disabled, no validation history or additional Model reads are retained.

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
same loaded Model/member context; later updates observe state
produced by earlier ones. This gives one Model update batch, part of one atomic Model operation when they remain payload updates; separately routed Messages and external effects have their own boundaries.

## Parameter and side-effect rules

Interceptor parameters can include current member/root/ancestor entities, the update when declared on the entity side,
metadata, message, time, and user context. A non-null current-entity parameter means the interceptor is skipped when
that entity is absent; mark it nullable only when create/upsert behavior intentionally handles absence.

Interceptors may load/query to decide how to rewrite an update, but must not dispatch updates or external effects.
Use an ordinary handler or post-commit consumer for those effects. Prefer `EventPublication.IF_MODIFIED` over a custom
no-change interceptor when equality-based event suppression is sufficient.

Test the original input through the command/update boundary and assert the exact final events and state. A direct call
to the interceptor does not prove recursive transformation, legality ordering, member routing, or persistence.
