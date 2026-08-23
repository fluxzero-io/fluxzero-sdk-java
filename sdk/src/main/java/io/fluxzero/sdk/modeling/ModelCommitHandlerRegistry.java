/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;

import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Registration and dispatch facade for independent-model handlers.
 *
 * <p>This type registers models and delegates application-bound definition lookup to {@link MutationPlan}. It owns no
 * evaluation, commit, retry, batching or completion state; every invocation delegates to the single
 * {@link ModelPipeline} lifecycle.</p>
 */
public final class ModelCommitHandlerRegistry implements HandlerRegistry, HandlerFactory, AutoCloseable {
    private final DefaultModelRepository repository;
    private final MutationPlan.Catalog definitions;
    private final ModelPipeline pipeline;
    private final Handler<DeserializingMessage> decoratedHandler;
    private final HandlerDecorator handlerDecorator;
    private volatile boolean localHandlingEnabled;

    /** Creates the automatic model registration facade and its single execution pipeline. */
    public ModelCommitHandlerRegistry(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            Serializer serializer,
            Serializer snapshotSerializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor eventDispatchInterceptor,
            String source,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerDecorator handlerDecorator,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxConflictRetries,
            AutomaticModelHandling automaticHandling,
            GraphProjectionCompletion graphProjectionCompletion) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.handlerDecorator = Objects.requireNonNull(handlerDecorator, "handlerDecorator");
        MutationPlan.Compiler shared = repository.modelDefinitionCompiler();
        this.definitions = new MutationPlan.Catalog(
                shared == null ? new MutationPlan.Compiler(parameterResolvers) : shared,
                automaticHandling);
        this.pipeline = new ModelPipeline(
                repository, eventStoreClient, serializer, snapshotSerializer,
                documentSerializer, eventDispatchInterceptor, source,
                conflictPolicy, conflictResolver, maxConflictRetries,
                graphProjectionCompletion, definitions::get,
                () -> localHandlingEnabled);
        this.decoratedHandler = handlerDecorator.wrap(pipeline.handler(null));
    }

    /** Returns the repository shared by automatic handling and public model loads. */
    public DefaultModelRepository repository() {
        return repository;
    }

    /** Returns the model types registered as handlers in this application. */
    public List<Class<?>> registeredModelTypes() {
        return definitions.registeredModelTypes();
    }

    /** Returns registered or structurally referenced concrete model types. */
    public List<Class<?>> knownModelTypes() {
        return definitions.knownModelTypes();
    }

    /** Registers Model definitions for migration without enabling command tracking or Graph projections. */
    public Registration registerMigrationTypes(
            Collection<Class<?>> modelTypes) {
        List<Class<?>> validated = List.copyOf(new LinkedHashSet<>(
                Objects.requireNonNull(modelTypes, "Model types")));
        validated.forEach(type -> {
            if (!EntityMetadata.of(type).isModel()) {
                throw new IllegalArgumentException(
                        type.getName() + " is not a Model root");
            }
        });
        validated.forEach(definitions::register);
        return () -> validated.forEach(definitions::unregister);
    }

    /** Executes one explicit update through the model pipeline. */
    public CompletableFuture<Void> assertAndApply(Message update) {
        return pipeline.assertAndApply(update);
    }

    /** Executes one explicit update against the selected persisted model. */
    public CompletableFuture<Void> assertAndApply(Message update, String modelId, Class<?> modelType) {
        return pipeline.assertAndApply(update, modelId, modelType);
    }

    /** Executes independent explicit updates with shared transport batching. */
    public CompletableFuture<Void> assertAndApplyAll(List<Message> updates) {
        return pipeline.assertAndApplyAll(updates);
    }

    /** Runs interceptors and immediate assertions without applying or committing. */
    public CompletableFuture<Void> assertLegal(Message update) {
        return pipeline.assertLegal(update);
    }

    /** Replays one already accepted event without command assertions or interception. */
    public CompletableFuture<Void> applyStoredEvent(Message event) {
        return pipeline.applyStoredEvent(event);
    }

    /** Applies one existing globally published event without republishing it. */
    public CompletableFuture<Void> migratePublishedEvent(
            Message event, long eventIndex) {
        return pipeline.migratePublishedEvent(event, eventIndex);
    }

    @Override
    public Optional<CompletableFuture<Object>> handle(DeserializingMessage message) {
        if (!localHandlingEnabled) {
            return Optional.empty();
        }
        HandlerInvoker invoker = decoratedHandler.getInvokerOrNull(message);
        if (invoker == null) {
            return Optional.empty();
        }
        try {
            Object result = invoker.invoke();
            if (result instanceof CompletableFuture<?> future) {
                return Optional.of(future.thenApply(value -> value));
            }
            return Optional.of(CompletableFuture.completedFuture(result));
        } catch (Throwable failure) {
            return Optional.of(CompletableFuture.failedFuture(failure));
        }
    }

    @Override
    public boolean canHandle(DeserializingMessage message) {
        return localHandlingEnabled
               && message.getMessageType() == MessageType.COMMAND
               && definitions.get(message.getPayloadClass()).automatic();
    }

    ModelCommitPolicy commitPolicyFor(Class<?> payloadType) {
        return definitions.get(payloadType).commitPolicy();
    }

    ModelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        if (!EntityMetadata.of(targetType).isModel()) {
            return Registration.noOp();
        }
        definitions.register(targetType);
        EntityMetadata.graphProjectionRoots(targetType)
                .forEach(root -> repository.registerGraphProjection(
                        root.modelType(), false));
        return () -> definitions.unregister(targetType);
    }

    @Override
    public List<?> trackingTargets(Object target, HandlerFilter handlerFilter) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        if (!EntityMetadata.of(targetType).isModel()) {
            return List.of(target);
        }
        LinkedHashSet<Class<?>> payloadTypes = EntityMetadata.of(targetType)
                .handlerMethods().stream()
                .filter(handler -> handler.kind() != EntityMetadata.HandlerKind.ASSERT_LEGAL)
                .filter(handler -> handlerFilter.test(
                        handler.executable().getDeclaringClass(), handler.executable()))
                .flatMap(handler -> commandPayloadTypes(handler).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return payloadTypes.isEmpty() ? List.of(target) : List.copyOf(payloadTypes);
    }

    private static List<Class<?>> commandPayloadTypes(EntityMetadata.HandlerMethod handler) {
        return Stream.of(handler.executable().getParameters())
                .filter(parameter -> handler.modelParameters().stream()
                        .noneMatch(model -> model.parameter().equals(parameter)))
                .map(Parameter::getType)
                .filter(type -> !isFrameworkParameter(type))
                .toList();
    }

    @Override
    public Optional<Handler<DeserializingMessage>> createHandler(
            Object target,
            HandlerFilter handlerFilter,
            List<HandlerInterceptor> extraInterceptors) {
        Class<?> targetType = ReflectionUtils.asClass(target);
        if (EntityMetadata.of(targetType).isModel()) {
            return Optional.empty();
        }
        MutationPlan definition = definitions.get(targetType);
        boolean selected = definition.automatic() && definition.reducer().methods().stream()
                .anyMatch(handler -> handlerFilter.test(
                        handler.executable().getDeclaringClass(), handler.executable()));
        if (!selected) {
            return Optional.empty();
        }
        HandlerDecorator decorator = Stream.concat(extraInterceptors.stream(), Stream.of(handlerDecorator))
                .reduce(HandlerDecorator::andThen).orElseThrow();
        return Optional.of(decorator.wrap(pipeline.handler(targetType)));
    }

    @Override
    public boolean hasLocalHandlers() {
        return localHandlingEnabled;
    }

    @Override
    public boolean canSkipLocalHandling(MessageType messageType, Class<?> payloadType) {
        return !localHandlingEnabled;
    }

    @Override
    public void setSelfHandlerFilter(HandlerFilter selfHandlerFilter) {
        localHandlingEnabled = selfHandlerFilter == HandlerFilter.ALWAYS_HANDLE;
    }

    private static boolean isFrameworkParameter(Class<?> type) {
        return type.equals(Instant.class)
               || type.equals(io.fluxzero.common.api.Metadata.class)
               || type.equals(Message.class)
               || type.equals(DeserializingMessage.class);
    }

    @Override
    public void close() {
    }
}
