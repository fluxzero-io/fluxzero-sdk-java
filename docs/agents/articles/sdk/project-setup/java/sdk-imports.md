Use this reference when Java compilation cannot resolve a Fluxzero symbol or when several packages expose similarly named types. Do not guess an import from the simple class name.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

Use the exact `io.fluxzero:fluxzero-bom` version resolved by the project and its matching documentation. Use the exact imports below for the APIs shown. If a resolved artifact does not contain a listed type, inspect that artifact before changing an import; do not copy an older package from an example or local source checkout and do not guess from the simple class name.

## Core application imports

| Purpose | Exact Java type |
| --- | --- |
| static SDK facade | `io.fluxzero.sdk.Fluxzero` |
| aggregate annotation | `io.fluxzero.sdk.modeling.Aggregate` |
| aggregate published-event routing policy | `io.fluxzero.sdk.modeling.AggregateEventRouting` |
| aggregate/entity ID | `io.fluxzero.sdk.modeling.EntityId` |
| alternate persisted ID | `io.fluxzero.sdk.modeling.Alias` |
| loaded aggregate/entity wrapper | `io.fluxzero.sdk.modeling.Entity` |
| aggregate event publication policy | `io.fluxzero.sdk.modeling.EventPublication` |
| state transition method | `io.fluxzero.sdk.persisting.eventsourcing.Apply` |
| transition interceptor | `io.fluxzero.sdk.persisting.eventsourcing.InterceptApply` |
| command/query/event handlers | `io.fluxzero.sdk.tracking.handling.HandleCommand`, `HandleQuery`, `HandleEvent` |
| domain request contract | `io.fluxzero.sdk.tracking.handling.Request` |
| stateful workflow | `io.fluxzero.sdk.tracking.handling.Stateful` |
| consumer configuration | `io.fluxzero.sdk.tracking.Consumer` |
| self-tracked payload | `io.fluxzero.sdk.tracking.TrackSelf` |
| message routing annotation | `io.fluxzero.sdk.publishing.routing.RoutingKey` |
| message metadata | `io.fluxzero.common.api.Metadata` |
| delivery guarantee | `io.fluxzero.common.Guarantee` |
| scheduled payload | `io.fluxzero.sdk.scheduling.Schedule` |
| outbound HTTP message | `io.fluxzero.sdk.web.WebRequest` |
| outbound HTTP gateway | `io.fluxzero.sdk.publishing.WebRequestGateway` |
| application properties | `io.fluxzero.sdk.configuration.ApplicationProperties` |
| fail-closed local dispatch | `io.fluxzero.sdk.publishing.LocalOnly` |
| unhandled local-only request | `io.fluxzero.sdk.publishing.LocalOnlyDispatchException` |
| registered wire/JSON type names | `io.fluxzero.common.serialization.RegisterType` |

The most common wrong guesses are important: `Apply` is not in `io.fluxzero.sdk.modeling`; `RoutingKey` is not in `io.fluxzero.sdk.tracking`; and `Metadata` is in the `common` artifact's `io.fluxzero.common.api` package. `AggregateEventRouting` is a modeling enum; use `AGGREGATE_ID` when every event published from an aggregate must share its aggregate-ID message segment. A domain query implements `io.fluxzero.sdk.tracking.handling.Request<R>`, not the runtime transport DTO `io.fluxzero.common.api.Request`.

For the correlation-to-primary-command pattern, the complete sensitive import set is:

```java
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
```

## TestFixture imports

```java
import io.fluxzero.sdk.test.Given;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.test.Then;
```

`when...` methods return `Then<R>` (implemented by `ResultValidator<R>`). `Then.andThen()` returns the next `Given<?>` phase. `andThen()` is not a method on `TestFixture`, so keep helper parameters and return types at the phase interfaces instead of casting them to the fixture implementation.

If a symbol remains uncertain, read the resolved Maven repository location from `settings.localRepository`, then use `jar tf` or `javap` against the exact resolved SDK/common/test JAR. Do not assume that repository is `~/.m2/repository`.

## Model imports

Use `io.fluxzero.sdk.modeling.Model`, `Graph`, `Parent` and `EntityId` for independent Models and their graph. The shared `@Apply` annotation is `io.fluxzero.sdk.persisting.eventsourcing.Apply`; `ModelConflictPolicy` is in `io.fluxzero.common.api.modeling`. The Aggregate imports above support existing persisted Aggregate code.

`ModelPersistence` and `DocumentProjection` are in `io.fluxzero.sdk.modeling`. The current API takes a non-empty persistence set: use `{ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT}` in Java or `[ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT]` in Kotlin when both representations are needed.
