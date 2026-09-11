This is a curated application-facing symbol map, not a dump of every class shipped in the SDK/common artifacts. Read

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
the linked task article before using advanced symbols.

## Application, configuration, and messages

| Objective | Public symbol |
| --- | --- |
| high-level SDK access | `io.fluxzero.sdk.Fluxzero` |
| application implementation | `io.fluxzero.sdk.configuration.DefaultFluxzero` |
| builder contract | `io.fluxzero.sdk.configuration.FluxzeroBuilder` |
| application properties | `io.fluxzero.sdk.configuration.ApplicationProperties` |
| property value encryption | `io.fluxzero.common.encryption.DefaultEncryption` |
| Spring customization | `io.fluxzero.sdk.configuration.spring.FluxzeroCustomizer` |
| client contract | `io.fluxzero.sdk.configuration.client.Client` |
| local/direct clients | `io.fluxzero.sdk.configuration.client.LocalClient`, `io.fluxzero.sdk.configuration.client.WebSocketClient` |
| message envelope/context | `io.fluxzero.sdk.common.Message`, `io.fluxzero.common.api.Metadata` |
| delivery guarantee/type | `io.fluxzero.common.Guarantee`, `io.fluxzero.common.MessageType` |
| typed application request | `io.fluxzero.sdk.tracking.handling.Request` |
| injected handler argument extension | `io.fluxzero.common.handling.ParameterResolver` |
| stable SDK diagnostics | `io.fluxzero.sdk.common.exception.FluxzeroErrorCode`, `FluxzeroErrors` |

Do not import `io.fluxzero.common.api.Request` for a domain query; that is a runtime transport DTO.

## Modeling and persistence

| Objective | Public symbol |
| --- | --- |
| aggregate/entity annotations | `io.fluxzero.sdk.modeling.Aggregate`, `EntityId`, `Member`, `Alias`; `io.fluxzero.sdk.tracking.handling.Association` |
| entity wrapper | `io.fluxzero.sdk.modeling.Entity` |
| legality/apply | `io.fluxzero.sdk.modeling.AssertLegal`, `io.fluxzero.sdk.persisting.eventsourcing.Apply` |
| update interception | `io.fluxzero.sdk.persisting.eventsourcing.InterceptApply` |
| event publication/routing | `io.fluxzero.sdk.modeling.EventPublication`, `EventPublicationStrategy`, `AggregateEventRouting` |
| aggregate repository | `io.fluxzero.sdk.persisting.repository.AggregateRepository` |
| event store | `io.fluxzero.sdk.persisting.eventsourcing.EventStore` |
| search document store | `io.fluxzero.sdk.persisting.search.DocumentStore` |
| search builder | `io.fluxzero.sdk.persisting.search.Search` |
| searchable/index annotations | `io.fluxzero.sdk.persisting.search.Searchable`; `io.fluxzero.common.search.Sortable`, `Facet`, `SearchInclude`, `SearchExclude` |

## Handling, tracking, and scheduling

| Objective | Public symbol |
| --- | --- |
| tracked consumer | `io.fluxzero.sdk.tracking.Consumer` |
| self tracking/local handler | `io.fluxzero.sdk.tracking.TrackSelf`, `io.fluxzero.sdk.tracking.handling.LocalHandler` |
| common handlers | `io.fluxzero.sdk.tracking.handling.HandleCommand`, `HandleQuery`, `HandleEvent`, `HandleNotification` |
| specialized handlers | `io.fluxzero.sdk.tracking.handling.HandleCustom`, `HandleDocument`, `HandleResult`, `HandleError`, `HandleMetrics` |
| stateful workflow | `io.fluxzero.sdk.tracking.handling.Stateful` |
| trigger correlation | `io.fluxzero.sdk.tracking.handling.Trigger` |
| routing key | `io.fluxzero.sdk.publishing.routing.RoutingKey` |
| message gateways | `io.fluxzero.sdk.publishing.CommandGateway`, `QueryGateway`, `EventGateway`, `GenericGateway`, `WebRequestGateway` |
| request timeout | `io.fluxzero.sdk.publishing.Timeout` |
| tracking operations | `io.fluxzero.sdk.tracking.client.TrackingClient` |
| consumer builder | `io.fluxzero.sdk.tracking.ConsumerConfiguration` |
| consumer error policies | `io.fluxzero.sdk.tracking.ErrorHandler`, `LoggingErrorHandler`, `ThrowingErrorHandler`, `RetryingErrorHandler`, `ForeverRetryingErrorHandler`, `SilentErrorHandler` |
| interceptors | `io.fluxzero.sdk.publishing.DispatchInterceptor`, `io.fluxzero.sdk.tracking.handling.HandlerInterceptor`, `io.fluxzero.sdk.tracking.BatchInterceptor` |
| scheduling | `io.fluxzero.sdk.scheduling.Periodic`, `CancelPeriodic`, `MessageScheduler` |

## Web, validation, serialization, and testing

| Objective | Public symbol |
| --- | --- |
| web request/response | `io.fluxzero.sdk.web.WebRequest`, `WebResponse` |
| web response mapping/form parts | `io.fluxzero.sdk.web.WebResponseMapper`, `DefaultWebResponseMapper`, `WebFormPart` |
| web handlers and path | `io.fluxzero.sdk.web.HandleGet`, `HandlePost`, `HandleWeb`, `Path` |
| parameter binding | `io.fluxzero.sdk.web.PathParam`, `QueryParam`, `HeaderParam`, `CookieParam`, `FormParam`, `BodyParam` |
| sockets/static | `io.fluxzero.sdk.web.SocketEndpoint`, `SocketSession`, `ServeStatic` |
| API discovery | `io.fluxzero.sdk.web.ApiDoc`, `ApiDocInfo`, `ApiDocComponent`, `ApiDocResponse` |
| advanced API discovery | `io.fluxzero.sdk.web.ApiDocExclude`, `ApiDocCatalog`, `ApiDocExtractor`, `OpenApiOptions`, `OpenApiRenderer`, `ApiReferenceRenderer` |
| authentication/authorization | `io.fluxzero.sdk.tracking.handling.authentication.User`, `UserProvider`, `RequiresUser`, `RequiresAnyRole`, `NoUserRequired` |
| identity provider | `io.fluxzero.sdk.common.IdentityProvider` |
| validation | `io.fluxzero.sdk.tracking.handling.validation.ValidateWith`, `ValidationUtils`, `ValidationException` |
| content/protected data | `io.fluxzero.sdk.common.serialization.FilterContent`, `io.fluxzero.sdk.publishing.dataprotection.ProtectData`, `DropProtectedData` |
| schema evolution | `io.fluxzero.common.serialization.Revision`, `io.fluxzero.sdk.common.serialization.casting.Upcast`, `Downcast` |
| fixture phases | `io.fluxzero.sdk.test.TestFixture`, `Given`, `Then` |

For exact fixture phase generics, query/search result witnesses, schedule constructors, and direct durable Given methods,
read the focused test-fixture signature map. Keep this table as symbol routing rather than inferring overloads from it.

Confirm nested/simple-name spellings through MCP symbol lookup or the built artifact before importing. Do not use raw
`io.fluxzero.common.api.*` operation commands such as `ResetPosition`, `DeleteEvents`, or `DeleteCollection` in normal
application code; use the documented SDK facade/client that owns the operation.
