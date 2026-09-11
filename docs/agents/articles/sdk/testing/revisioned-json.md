Use root-level `@class` and `@revision` when a fixture resource represents historical serialized data that must pass

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
through the normal upcaster chain. `@Revision` is the Java/Kotlin annotation declaring a type's revision; `@revision`
is the case-sensitive JSON serialization field.

```json
{
  "@class": "com.example.legacy.LabelChanged",
  "@revision": 0,
  "revision": 42,
  "label": "Primary"
}
```

The fixture uses `@class` as `Data.type` and `@revision` as `Data.revision`, removes both markers from the payload,
runs the revision upcasters, then resolves historical type aliases. The plain `revision: 42` remains application data.
Do not use a field named `revision` as a substitute for `@revision`.

Test a caster directly with `TestFixture.whenUpcasting(...)`. For a production behavior claim, also pass the historical
resource through the relevant fixture command/event/aggregate boundary. Follow the split-upcaster article when one
historical event expands into several events; asserting only the direct caster result does not prove reconstruction.

## Untyped versus typed JSON reads

Untyped `JsonUtils.fromFile(...)` and `JsonUtils.fromJson(...)` interpret a revisioned object as `Data<JsonNode>`.
Explicitly typed overloads retain their declared return type. Non-revisioned fixture objects can use registered
simple names or distinguishing suffixes; historical data keeps the source identity needed by its upcaster.
Direct untyped reads outside a fixture need an explicit type mapper when application aliases or registered-name
resolution are required.

## Arrays and newline-delimited resources

Untyped root arrays and multiple root JSON values return an `ArrayList`. Each element/record is interpreted
independently with the same `@class` and `@revision` rules. A `.ndjson` or `.jsonl` resource always returns an
`ArrayList`, even when it contains just one record.

Use an array or NDJSON resource for ordered command setup:

```java
fixture.givenCommands("/labels/baseline.ndjson");
```

Each command needs its own `@class` and may carry its own `@revision`. Commands execute in source order;
`givenCommandsByUser(...)` applies the selected user to each command. Array failures identify the zero-based
element index; NDJSON failures identify the source line. These resource rules are the same for Java and Kotlin tests.

Cover a revisioned object with an ordinary `revision` payload field, mixed current/historical array entries,
multiple NDJSON records, a one-record NDJSON file and invalid metadata. Assert the resulting behavior or normalized
payload, not only that reading the file returned a collection.
