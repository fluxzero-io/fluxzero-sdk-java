Use JSON fixture resources when historical serialized shape, a large immutable setup, or several related payload
variants would obscure the behavior test in Java. Prefer constructors for small ordinary current-domain inputs.

## Store complete type information

Put resources under `src/test/resources`. For a type indexed by `@RegisterType`, prefer its unique simple name or a
distinguishing suffix in `@class`. Use the fully qualified name for an unregistered type:

```json
{
  "@class": "com.example.widget.api.ConfigureWidget",
  "widgetId": "widget-42",
  "label": "Primary"
}
```

An FQN remains valid and makes the target explicit. Registered names must be unambiguous. Historical FQNs may be kept
after a class/package move when the corresponding type alias is configured. A compatible rename uses an alias; an
actual schema migration uses a revision upcaster. Root and nested `@class` values use the configured resolver.

Add root-level `@revision` beside `@class` for historical serialized data. The fixture runs the revision upcaster chain
and then resolves aliases. The field `revision` without `@` is ordinary payload data. Follow the revisioned-JSON article
for the exact resource contract and the registered-type-names article for annotation processing and packaging.

## Derive variants with `@extends`

```json
{
  "@extends": "/widget/configure-valid.json",
  "label": ""
}
```

An absolute resource path begins at the test classpath root. Keep the base valid and change one cause per negative
variant so the test identifies the intended validation or domain failure. Avoid inheritance chains so deep that an
agent cannot see the final payload contract.

## Seed large preconditions deliberately

`givenCommands("/setup/baseline.json")` can load a resource containing a command or command array. A `.ndjson` or
`.jsonl` resource can supply one command per record. Each command needs its own `@class`, may carry its own
`@revision`, and executes in source order. `givenCommandsByUser(...)` applies the selected user to every command.
All Given commands
and their registered side effects run fully, then their outputs are cleared before the When/Then assertion window.
Use direct durable reconstruction methods instead when the objective is behavior from supplied stored artifacts rather
than setup behavior. That is synthetic reconstruction; persistence-backed restart needs a retained external runtime and
a fresh application without manual reseeding.

```java
fixture.givenCommands("/setup/widget-baseline.json")
        .whenCommand(new RenameWidget(widgetId, "Updated"))
        .expectOnlyEvents(WidgetRenamed.class);
```

## Carry generated results into routed requests

`asWebParameter(name)` maps the current result to a named `{placeholder}` in later web paths:

```java
fixture.whenPost("/api/widgets", "/widget/create-request.json")
        .asWebParameter("widgetId")
        .andThen()
        .whenGet("/api/widgets/{widgetId}")
        .expectResult(WidgetView.class);
```

Use explicit names when multiple results are chained. `asWebParameter` does not advance the phase; call `andThen()`
before the next action.

## Deterministic generated IDs

Prefer caller-supplied stable IDs in behavior tests. When generation itself is part of the contract, configure a test
`IdentityProvider` through `FluxzeroBuilder.replaceIdentityProvider(...)` and give it deterministic functional and
technical sequences. Do not rely on an undocumented fixture default counter. Keep this provider local to the fixture so
it cannot affect production or parallel tests.
