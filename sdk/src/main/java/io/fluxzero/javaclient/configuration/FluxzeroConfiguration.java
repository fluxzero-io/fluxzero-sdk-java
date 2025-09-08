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

import io.fluxzero.common.DelegatingClock;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.javaclient.Fluxzero;
import io.fluxzero.javaclient.common.IdentityProvider;
import io.fluxzero.javaclient.common.serialization.DeserializingMessage;
import io.fluxzero.javaclient.common.serialization.Serializer;
import io.fluxzero.javaclient.persisting.caching.Cache;
import io.fluxzero.javaclient.persisting.search.DocumentSerializer;
import io.fluxzero.javaclient.publishing.DispatchInterceptor;
import io.fluxzero.javaclient.publishing.correlation.CorrelationDataProvider;
import io.fluxzero.javaclient.scheduling.SchedulingInterceptor;
import io.fluxzero.javaclient.tracking.BatchInterceptor;
import io.fluxzero.javaclient.tracking.ConsumerConfiguration;
import io.fluxzero.javaclient.tracking.handling.HandlerDecorator;
import io.fluxzero.javaclient.tracking.handling.ResponseMapper;
import io.fluxzero.javaclient.tracking.handling.authentication.UserProvider;
import io.fluxzero.javaclient.web.ForwardingWebConsumer;
import io.fluxzero.javaclient.web.WebResponseMapper;

import java.util.List;
import java.util.Map;

/**
 * Central configuration interface for a Fluxzero client instance.
 * <p>
 * This interface exposes all essential configuration components and extension points that influence
 * message serialization, handler invocation, scheduling, caching, dispatching, user context, and more.
 * <p>
 * Implementations of this interface are typically created via {@link FluxzeroBuilder} and can be
 * accessed through {@link Fluxzero#configuration()} at runtime.
 * </p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Provide serializers for messages, snapshots, and documents.</li>
 *   <li>Define consumer tracking and dispatch configuration per {@link MessageType}.</li>
 *   <li>Register interceptors and decorators for dispatching, batching, and handling messages.</li>
 *   <li>Expose services like clock, caching, scheduling, and task execution.</li>
 *   <li>Supply user and identity resolution logic for context-aware operations.</li>
 * </ul>
 *
 * <h2>Custom configuration</h2>
 * Many of the returned values (e.g. interceptors, decorators, resolvers) are lists or maps that
 * can be extended to modify the behavior of message handling and dispatch. Lists are ordered by priority
 * (high or low), and are evaluated in that order during processing.
 *
 * @see Fluxzero
 * @see FluxzeroBuilder
 */
public interface FluxzeroConfiguration {

    /** Returns the primary serializer for serializing and deserializing message payloads. */
    Serializer serializer();

    /** Returns the serializer used for serializing and deserializing snapshots of stateful entities. */
    Serializer snapshotSerializer();

    /** Returns the provider responsible for adding correlation data to outgoing messages. */
    CorrelationDataProvider correlationDataProvider();

    /** Returns the serializer used to store and retrieve documents in the {@code DocumentStore}. */
    DocumentSerializer documentSerializer();

    /** Provides the default consumer configuration per message type. */
    Map<MessageType, ConsumerConfiguration> defaultConsumerConfigurations();

    /** Provides custom consumer configurations per message type. */
    Map<MessageType, List<ConsumerConfiguration>> customConsumerConfigurations();

    /** Returns additional resolvers for injecting parameters into message handler methods. */
    List<ParameterResolver<? super DeserializingMessage>> customParameterResolvers();

    /** Dispatch interceptors applied after high-priority interceptors. */
    Map<MessageType, List<DispatchInterceptor>> lowPrioDispatchInterceptors();

    /** Dispatch interceptors applied before low-priority interceptors. */
    Map<MessageType, List<DispatchInterceptor>> highPrioDispatchInterceptors();

    /** Decorators applied to handlers after high-priority decorators. */
    Map<MessageType, List<HandlerDecorator>> lowPrioHandlerDecorators();

    /** Decorators applied to handlers before low-priority decorators. */
    Map<MessageType, List<HandlerDecorator>> highPrioHandlerDecorators();

    /** Interceptors applied to message batches during tracking and dispatch. */
    Map<MessageType, List<BatchInterceptor>> generalBatchInterceptors();

    /** Provides a central clock used throughout the system for timestamps and scheduling. */
    DelegatingClock clock();

    /** Special interceptor used to determine routing of dispatched messages (e.g. for multitenancy). */
    DispatchInterceptor messageRoutingInterceptor();

    /** Interceptor applied to scheduled messages, such as time-based commands or events. */
    SchedulingInterceptor schedulingInterceptor();

    /** Task scheduler used for asynchronous background task execution. */
    TaskScheduler taskScheduler();

    /**
     * Internal web consumer that can forward incoming {@code WebRequest} messages
     * to a local HTTP server. Typically configured via {@link FluxzeroBuilder#forwardWebRequestsToLocalServer}.
     */
    ForwardingWebConsumer forwardingWebConsumer();

    /** Default cache used for internal stateful optimizations (e.g. handler state, snapshots). */
    Cache cache();

    /** Dedicated cache used to store and lookup relationships between entities. */
    Cache relationshipsCache();

    /** Default response mapper used for converting handler return values into generic responses. */
    ResponseMapper defaultResponseMapper();

    /** Mapper used for converting web handler return values into {@code WebResponse} objects. */
    WebResponseMapper webResponseMapper();

    /** Whether the application instance should be created automatically on first use. */
    boolean makeApplicationInstance();

    /** Returns the provider used to determine the current authenticated or active user. */
    UserProvider userProvider();

    /** Returns the provider used to generate or resolve application-specific identities. */
    IdentityProvider identityProvider();

    /** Provides access to configuration properties (typically loaded from the environment). */
    PropertySource propertySource();
}
