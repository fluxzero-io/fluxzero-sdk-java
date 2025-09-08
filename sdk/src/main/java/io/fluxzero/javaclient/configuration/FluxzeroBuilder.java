/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.javaclient.configuration;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.javaclient.Fluxzero;
import io.fluxzero.javaclient.common.IdentityProvider;
import io.fluxzero.javaclient.common.serialization.DeserializingMessage;
import io.fluxzero.javaclient.common.serialization.Serializer;
import io.fluxzero.javaclient.configuration.client.Client;
import io.fluxzero.javaclient.persisting.caching.Cache;
import io.fluxzero.javaclient.persisting.search.DocumentSerializer;
import io.fluxzero.javaclient.publishing.DispatchInterceptor;
import io.fluxzero.javaclient.publishing.ErrorGateway;
import io.fluxzero.javaclient.publishing.correlation.CorrelationDataProvider;
import io.fluxzero.javaclient.tracking.BatchInterceptor;
import io.fluxzero.javaclient.tracking.ConsumerConfiguration;
import io.fluxzero.javaclient.tracking.handling.HandlerDecorator;
import io.fluxzero.javaclient.tracking.handling.HandlerInterceptor;
import io.fluxzero.javaclient.tracking.handling.ResponseMapper;
import io.fluxzero.javaclient.tracking.handling.authentication.User;
import io.fluxzero.javaclient.tracking.handling.authentication.UserProvider;
import io.fluxzero.javaclient.web.LocalServerConfig;
import io.fluxzero.javaclient.web.WebResponseMapper;

import java.time.Clock;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Builder interface for constructing a {@link Fluxzero} instance.
 * <p>
 * This interface exposes advanced configuration hooks for customizing message handling, dispatch behavior,
 * serialization, user resolution, correlation tracking, and many other aspects of a Fluxzero client. It is
 * primarily used via {@link io.fluxzero.javaclient.configuration.DefaultFluxzero} but can be extended or
 * wrapped for deeper integrations.
 */
public interface FluxzeroBuilder extends FluxzeroConfiguration {

    /**
     * Update the default consumer configuration for the specified message type.
     */
    FluxzeroBuilder configureDefaultConsumer(MessageType messageType,
                                                  UnaryOperator<ConsumerConfiguration> updateFunction);

    /**
     * Adds a specific consumer configuration for one or more message types.
     */
    FluxzeroBuilder addConsumerConfiguration(ConsumerConfiguration consumerConfiguration,
                                                  MessageType... messageTypes);

    /**
     * Registers a {@link BatchInterceptor} that applies to the given message types.
     */
    FluxzeroBuilder addBatchInterceptor(BatchInterceptor interceptor, MessageType... forTypes);

    /**
     * Adds a {@link DispatchInterceptor} that modifies or monitors message dispatch. Shortcut for highPriority =
     * false.
     */
    default FluxzeroBuilder addDispatchInterceptor(DispatchInterceptor interceptor, MessageType... forTypes) {
        return addDispatchInterceptor(interceptor, false, forTypes);
    }

    /**
     * Adds a {@link DispatchInterceptor} for specified message types with optional priority.
     */
    FluxzeroBuilder addDispatchInterceptor(DispatchInterceptor interceptor, boolean highPriority,
                                                MessageType... forTypes);

    /**
     * Adds a {@link HandlerInterceptor} for given message types.
     */
    default FluxzeroBuilder addHandlerInterceptor(HandlerInterceptor interceptor, MessageType... forTypes) {
        return addHandlerDecorator(interceptor, forTypes);
    }

    /**
     * Adds a {@link HandlerInterceptor} with specified priority.
     */
    default FluxzeroBuilder addHandlerInterceptor(HandlerInterceptor interceptor, boolean highPriority,
                                                       MessageType... forTypes) {
        return addHandlerDecorator(interceptor, highPriority, forTypes);
    }

    /**
     * Adds a {@link HandlerDecorator} for the given message types.
     */
    default FluxzeroBuilder addHandlerDecorator(HandlerDecorator decorator, MessageType... forTypes) {
        return addHandlerDecorator(decorator, false, forTypes);
    }

    /**
     * Adds a {@link HandlerDecorator} with control over priority.
     */
    FluxzeroBuilder addHandlerDecorator(HandlerDecorator decorator, boolean highPriority, MessageType... forTypes);

    /**
     * Replaces the default routing interceptor used for message dispatch.
     */
    FluxzeroBuilder replaceMessageRoutingInterceptor(DispatchInterceptor messageRoutingInterceptor);

    /**
     * Replaces the default cache implementation.
     */
    FluxzeroBuilder replaceCache(Cache cache);

    /**
     * Forwards incoming {@link io.fluxzero.common.MessageType#WEBREQUEST} messages to a locally running HTTP
     * server on the specified port.
     * <p>
     * This allows applications to handle web requests using their own HTTP server rather than Fluxzero’s
     * message-based {@code @HandleWeb} infrastructure.
     * <p>
     * <strong>Note:</strong> This feature pushes requests to the local server and bypasses Flux’s pull-based
     * dispatch model. Its use is discouraged unless integration with an existing HTTP stack is required.
     *
     * @param port the port on which the local HTTP server is listening
     * @return this builder instance
     * @see #forwardWebRequestsToLocalServer(LocalServerConfig, UnaryOperator)
     * @see io.fluxzero.javaclient.web.ForwardingWebConsumer
     */
    default FluxzeroBuilder forwardWebRequestsToLocalServer(int port) {
        return forwardWebRequestsToLocalServer(LocalServerConfig.builder().port(port).build(),
                                               UnaryOperator.identity());
    }

    /**
     * Configures forwarding of {@link io.fluxzero.common.MessageType#WEBREQUEST} messages to a local HTTP server
     * using the specified {@link LocalServerConfig} and custom consumer configuration.
     * <p>
     * This mechanism is useful for advanced integration scenarios but bypasses Flux's pull-based message tracking.
     * Prefer native {@code @HandleWeb} handlers when possible.
     *
     * @param localServerConfig    configuration for the local server (e.g., port, error behavior)
     * @param consumerConfigurator function to customize the underlying
     *                             {@link io.fluxzero.javaclient.tracking.ConsumerConfiguration}
     * @return this builder instance
     * @see io.fluxzero.javaclient.web.ForwardingWebConsumer
     */
    FluxzeroBuilder forwardWebRequestsToLocalServer(LocalServerConfig localServerConfig,
                                                         UnaryOperator<ConsumerConfiguration> consumerConfigurator);

    /**
     * Replaces the default response mapper used for generic result mapping.
     */
    FluxzeroBuilder replaceDefaultResponseMapper(ResponseMapper responseMapper);

    /**
     * Replaces the {@link WebResponseMapper} used for handling web responses.
     */
    FluxzeroBuilder replaceWebResponseMapper(WebResponseMapper webResponseMapper);

    /**
     * Replaces the default {@link TaskScheduler} implementation.
     */
    FluxzeroBuilder replaceTaskScheduler(Function<Clock, TaskScheduler> function);

    /**
     * Configures a dedicated cache for a specific aggregate type.
     */
    FluxzeroBuilder withAggregateCache(Class<?> aggregateType, Cache cache);

    /**
     * Replaces the internal relationships cache with a new implementation.
     */
    FluxzeroBuilder replaceRelationshipsCache(UnaryOperator<Cache> replaceFunction);

    /**
     * Replaces the identity provider used to generate message and entity identifiers.
     */
    FluxzeroBuilder replaceIdentityProvider(UnaryOperator<IdentityProvider> replaceFunction);

    /**
     * Registers a {@link ParameterResolver} to support injection of method arguments in handlers.
     */
    FluxzeroBuilder addParameterResolver(ParameterResolver<? super DeserializingMessage> parameterResolver);

    /**
     * Replaces the default serializer used for events, commands, snapshots, and documents.
     */
    FluxzeroBuilder replaceSerializer(Serializer serializer);

    /**
     * Replaces the {@link CorrelationDataProvider} used to attach correlation data to messages.
     */
    FluxzeroBuilder replaceCorrelationDataProvider(UnaryOperator<CorrelationDataProvider> correlationDataProvider);

    /**
     * Overrides the serializer used specifically for snapshot serialization.
     */
    FluxzeroBuilder replaceSnapshotSerializer(Serializer serializer);

    /**
     * Replaces the document serializer for search indexing.
     */
    FluxzeroBuilder replaceDocumentSerializer(DocumentSerializer documentSerializer);

    /**
     * Registers a user provider used for resolving and authenticating {@link User} instances.
     */
    FluxzeroBuilder registerUserProvider(UserProvider userProvider);

    /**
     * Adds a {@link PropertySource} to the configuration chain.
     */
    default FluxzeroBuilder addPropertySource(PropertySource propertySource) {
        return replacePropertySource(existing -> existing.andThen(propertySource));
    }

    /**
     * Replaces the existing property source.
     */
    FluxzeroBuilder replacePropertySource(UnaryOperator<PropertySource> replacer);

    /**
     * Disables automatic error reporting (e.g., via {@link ErrorGateway}).
     */
    FluxzeroBuilder disableErrorReporting();

    /**
     * Prevents registration of a shutdown hook.
     */
    FluxzeroBuilder disableShutdownHook();

    /**
     * Disables automatic message correlation.
     */
    FluxzeroBuilder disableMessageCorrelation();

    /**
     * Disables payload validation.
     */
    FluxzeroBuilder disablePayloadValidation();

    /**
     * Disables security filtering based on {@code @FilterContent}.
     */
    FluxzeroBuilder disableDataProtection();

    /**
     * Disables automatic caching of aggregates.
     */
    FluxzeroBuilder disableAutomaticAggregateCaching();

    /**
     * Prevents installation of the default scheduled command handler.
     */
    FluxzeroBuilder disableScheduledCommandHandler();

    /**
     * Disables tracking of processing metrics.
     */
    FluxzeroBuilder disableTrackingMetrics();

    /**
     * Disables metrics related to cache eviction.
     */
    FluxzeroBuilder disableCacheEvictionMetrics();

    /**
     * Disables compression for web responses.
     */
    FluxzeroBuilder disableWebResponseCompression();

    /**
     * Disables support for dynamically injected dispatch interceptors.
     */
    FluxzeroBuilder disableAdhocDispatchInterceptor();

    /**
     * Marks the built instance as the global (application-level) {@link Fluxzero}.
     */
    FluxzeroBuilder makeApplicationInstance(boolean makeApplicationInstance);

    /**
     * Builds the Fluxzero instance using the provided low-level {@link Client}.
     */
    Fluxzero build(Client client);
}
