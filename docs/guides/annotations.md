# Fluxzero Annotations Reference

This document provides a comprehensive list of all custom annotations available in the Fluxzero Java client library.

## Authentication & Authorization

- **`io.fluxzero.sdk.tracking.handling.authentication.RequiresUser`** - Indicates that a handler or message requires the presence of an authenticated user, implemented as a meta-annotation over RequiresAnyRole.

- **`io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole`** - Restricts message handlers or payloads to users with at least one of the specified roles, with configurable exception throwing behavior.

- **`io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired`** - Allows a message handler or payload to be invoked even if no user is authenticated, overriding broader authentication requirements.

- **`io.fluxzero.sdk.tracking.handling.authentication.ForbidsUser`** - Prevents handler invocation or message processing when a user is currently authenticated, useful for anonymous-only flows.

- **`io.fluxzero.sdk.tracking.handling.authentication.ForbidsAnyRole`** - Excludes handlers or payloads from being processed if the current user has any of the specified roles.

## Web Framework

- **`io.fluxzero.sdk.web.WebParam`** - Meta-annotation for parameter annotations used to inject values from HTTP requests based on the specified WebParameterSource.

- **`io.fluxzero.sdk.web.ServeStatic`** - Declares a static file handler that serves files from a resource or file system location at specified web paths.

- **`io.fluxzero.sdk.web.QueryParam`** - Injects an HTTP query parameter into a handler method parameter.

- **`io.fluxzero.sdk.web.PathParam`** - Injects a path variable from the URI into a handler method parameter.

- **`io.fluxzero.sdk.web.Path`** - Declares a path prefix that contributes to the final URI of a web handler, supporting hierarchical path composition.

- **`io.fluxzero.sdk.web.HeaderParam`** - Injects an HTTP request header into a handler method parameter.

- **`io.fluxzero.sdk.web.FormParam`** - Injects form data parameters from HTTP requests into handler method parameters.

- **`io.fluxzero.sdk.web.CookieParam`** - Injects HTTP cookie values into handler method parameters.

- **`io.fluxzero.sdk.web.HandleWebResponse`** - Marks a method as a handler for WebResponse messages, typically used to inspect response messages from web request handlers.

- **`io.fluxzero.sdk.web.HandleWeb`** - Marks a method as a handler for incoming web requests with configurable path patterns, HTTP methods, and response publishing behavior.

- **`io.fluxzero.sdk.web.HandleGet`** - Handles incoming HTTP GET requests for specified paths, a specialization of HandleWeb.

- **`io.fluxzero.sdk.web.HandlePost`** - Handles incoming HTTP POST requests for specified paths, a specialization of HandleWeb.

## Message Handling

- **`io.fluxzero.sdk.tracking.handling.HandleMessage`** - Meta-annotation that declares an annotation marks a method as a message handler for a specific MessageType.

- **`io.fluxzero.sdk.tracking.handling.HandleCommand`** - Marks a method as a handler for command messages, used for operations that change system state.

- **`io.fluxzero.sdk.tracking.handling.HandleQuery`** - Marks a method as a handler for query messages, responsible for answering questions by returning computed or retrieved values.

- **`io.fluxzero.sdk.tracking.handling.HandleEvent`** - Marks a method as a handler for event messages, typically used for reacting to state changes.

- **`io.fluxzero.sdk.tracking.handling.HandleSchedule`** - Marks a method as a handler for scheduled messages, enabling time-based or periodic processing.

- **`io.fluxzero.sdk.tracking.handling.HandleResult`** - Marks a method as a handler for result messages from previously executed requests.

- **`io.fluxzero.sdk.tracking.handling.HandleNotification`** - Marks a method as a handler for notification messages.

- **`io.fluxzero.sdk.tracking.handling.HandleMetrics`** - Marks a method as a handler for metrics messages.

- **`io.fluxzero.sdk.tracking.handling.HandleError`** - Marks a method as a handler for error messages.

- **`io.fluxzero.sdk.tracking.handling.HandleDocument`** - Marks a method as a handler for document messages.

- **`io.fluxzero.sdk.tracking.handling.HandleCustom`** - Marks a method as a handler for custom message types.

## Handler Configuration

- **`io.fluxzero.sdk.tracking.handling.Stateful`** - Declares a class as a stateful message handler whose state is persisted and can receive messages via Association.

- **`io.fluxzero.sdk.tracking.handling.LocalHandler`** - Marks a handler as local, meaning it's invoked immediately in the publishing thread rather than asynchronously through tracking.

- **`io.fluxzero.sdk.tracking.handling.Association`** - Declares how a message should be routed to a stateful handler instance by matching message properties with handler state.

- **`io.fluxzero.sdk.tracking.Consumer`** - Configures tracking behavior for message handlers including threading, error handling, and filtering options.

- **`io.fluxzero.sdk.tracking.TrackSelf`** - Marks a class to track itself as a Spring component with prototype scope.

- **`io.fluxzero.sdk.tracking.handling.Trigger`** - Declares a parameter or method that can trigger message publication or handling behavior.

## Domain Modeling

- **`io.fluxzero.sdk.modeling.Aggregate`** - Marks a class as the root of an aggregate in the domain model with extensive event sourcing and persistence configuration.

- **`io.fluxzero.sdk.modeling.EntityId`** - Marks a property as the unique identifier of an entity within an aggregate structure for automatic routing.

- **`io.fluxzero.sdk.modeling.Member`** - Declares child, grandchild, or descendant entities within an aggregate.

- **`io.fluxzero.sdk.modeling.AssertLegal`** - Defines legality checks that must pass before entity updates are applied.

- **`io.fluxzero.sdk.modeling.Alias`** - Provides alternative names for types during serialization and deserialization.

## Event Sourcing

- **`io.fluxzero.sdk.persisting.eventsourcing.Apply`** - Indicates that a method or constructor applies an update to an entity or creates/deletes an entity in event sourcing.

- **`io.fluxzero.sdk.persisting.eventsourcing.InterceptApply`** - Intercepts and potentially modifies entity updates before they are applied in event sourcing.

## Scheduling

- **`io.fluxzero.sdk.scheduling.Periodic`** - Declares a message or handler method as part of a periodic schedule with cron expression or fixed delay support.

## Publishing & Routing

- **`io.fluxzero.sdk.publishing.Timeout`** - Configures timeout behavior for requests sent using sendAndWait-like methods.

- **`io.fluxzero.sdk.publishing.routing.RoutingKey`** - Specifies routing keys for message distribution.

- **`io.fluxzero.sdk.publishing.dataprotection.ProtectData`** - Marks data fields for protection during serialization.

- **`io.fluxzero.sdk.publishing.dataprotection.DropProtectedData`** - Configures automatic removal of protected data fields.

## Search & Indexing

- **`io.fluxzero.sdk.persisting.search.Searchable`** - Marks classes for automatic indexing in the document store with search capabilities.

- **`io.fluxzero.common.search.Sortable`** - Marks fields or properties as sortable in search indexes with optional custom property names.

- **`io.fluxzero.common.search.Facet`** - Declares fields for faceted search capabilities with customizable facet names.

- **`io.fluxzero.common.search.SearchInclude`** - Explicitly includes fields in search indexing, overriding exclusion rules.

- **`io.fluxzero.common.search.SearchExclude`** - Excludes fields from search indexing with configurable activation behavior.

## Serialization & Casting

- **`io.fluxzero.sdk.common.serialization.casting.Cast`** - Meta-annotation indicating methods that perform revision-based type transformations.

- **`io.fluxzero.sdk.common.serialization.casting.Upcast`** - Marks methods that transform objects to newer revisions during deserialization.

- **`io.fluxzero.sdk.common.serialization.casting.Downcast`** - Marks methods that transform objects to older revisions during serialization.

- **`io.fluxzero.sdk.common.serialization.FilterContent`** - Controls content filtering during serialization processes.

- **`io.fluxzero.common.serialization.Revision`** - Declares the revision number of a class for versioning and backward compatibility.

- **`io.fluxzero.common.serialization.RegisterType`** - Automatically registers types for serialization with optional filtering by class name patterns.

## Validation

- **`io.fluxzero.sdk.tracking.handling.validation.ValidateWith`** - Specifies validation groups to include when validating a class using Bean Validation.

## Spring Integration

- **`io.fluxzero.sdk.configuration.spring.ConditionalOnProperty`** - Conditionally enables beans based on property values in Spring configuration.

- **`io.fluxzero.sdk.configuration.spring.ConditionalOnMissingProperty`** - Conditionally enables beans when specific properties are missing in Spring configuration.

- **`io.fluxzero.sdk.configuration.spring.ConditionalOnBean`** - Conditionally enables beans based on the presence of other beans in Spring context.

- **`io.fluxzero.sdk.configuration.spring.ConditionalOnMissingBean`** - Conditionally enables beans when specific beans are missing from Spring context.

## Testing & Utilities

- **`io.fluxzero.sdk.common.Nullable`** - Test annotation indicating nullable parameters or return values.

- **`io.fluxzero.testserver.websocket.Handle`** - Test server annotation for handling websocket messages.

- **`io.fluxzero.common.handling.Handle`** - Test annotation for generic message handling.