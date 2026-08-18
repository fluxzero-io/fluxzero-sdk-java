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
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.LocalHandlerResult;

import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Registration and dispatch facade for independent-model handlers.
 *
 * <p>This type discovers reachable handlers and caches one immutable {@link ModelExecutionPlan} per payload. It owns no
 * evaluation, commit, retry, batching or completion state; every invocation delegates to the single
 * {@link ModelPipeline} lifecycle.</p>
 */
public final class ModelCommitHandlerRegistry implements HandlerRegistry, HandlerFactory, AutoCloseable {
    private final DefaultModelRepository repository;
    private final ModelExecutionPlan.Compiler compiler;
    private final ModelPipeline pipeline;
    private final Handler<DeserializingMessage> decoratedHandler;
    private final HandlerDecorator handlerDecorator;
    private final AutomaticModelHandling automaticHandling;
    private final CopyOnWriteArrayList<Class<?>> registeredModelTypes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Class<?>> knownModelTypes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Class<?>, ModelExecutionPlan> plans = new ConcurrentHashMap<>();
    private volatile CachedExecutionPlan recentPlan;
    private volatile boolean registeredModelTypesDiscovered;
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
        this.automaticHandling = Objects.requireNonNull(automaticHandling, "automaticHandling");
        ModelExecutionPlan.Compiler shared = repository.modelExecution();
        this.compiler = shared == null ? new ModelExecutionPlan.Compiler(parameterResolvers) : shared;
        this.pipeline = new ModelPipeline(
                repository, eventStoreClient, serializer, snapshotSerializer,
                documentSerializer, eventDispatchInterceptor, source,
                conflictPolicy, conflictResolver, maxConflictRetries,
                graphProjectionCompletion, this::planFor,
                () -> localHandlingEnabled);
        this.decoratedHandler = handlerDecorator.wrap(pipeline.handler(null));
    }

    /** Returns the repository shared by automatic handling and public model loads. */
    public DefaultModelRepository repository() {
        return repository;
    }

    /** Returns the model types registered as handlers in this application. */
    public List<Class<?>> registeredModelTypes() {
        return List.copyOf(registeredModelTypes);
    }

    /** Returns registered or structurally referenced concrete model types. */
    public List<Class<?>> knownModelTypes() {
        discoverRegisteredModelTypes();
        return List.copyOf(knownModelTypes);
    }

    private void discoverRegisteredModelTypes() {
        if (registeredModelTypesDiscovered) {
            return;
        }
        synchronized (knownModelTypes) {
            if (registeredModelTypesDiscovered) {
                return;
            }
            ReflectionUtils.getRegisteredTypes().stream()
                    .filter(type -> ReflectionUtils.getTypeMetadata(type).typeAnnotation(Model.class) != null)
                    .forEach(knownModelTypes::addIfAbsent);
            registeredModelTypesDiscovered = true;
        }
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
    public LocalHandlerResult handleResult(DeserializingMessage message) {
        return handle(message).map(LocalHandlerResult::asynchronous)
                .orElseGet(LocalHandlerResult::notHandled);
    }

    @Override
    public boolean canHandle(DeserializingMessage message) {
        return localHandlingEnabled
               && message.getMessageType() == MessageType.COMMAND
               && planFor(message.getPayloadClass()).automatic();
    }

    ModelCommitPolicy commitPolicyFor(Class<?> payloadType) {
        return planFor(payloadType).commitPolicy();
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
        registeredModelTypes.addIfAbsent(targetType);
        knownModelTypes.addIfAbsent(targetType);
        ModelGraphProjections.roots(targetType).forEach(pipeline::registerGraphProjection);
        clearPlans();
        return () -> {
            registeredModelTypes.remove(targetType);
            clearPlans();
        };
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
        ModelExecutionPlan plan = planFor(targetType);
        boolean selected = plan.automatic() && plan.handlers().methods().stream()
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

    private ModelExecutionPlan planFor(Class<?> payloadType) {
        CachedExecutionPlan recent = recentPlan;
        if (recent != null && recent.payloadType() == payloadType) {
            return recent.plan();
        }
        ModelExecutionPlan plan = plans.computeIfAbsent(payloadType, this::compilePlan);
        recentPlan = new CachedExecutionPlan(payloadType, plan);
        return plan;
    }

    private ModelExecutionPlan compilePlan(Class<?> payloadType) {
        List<EntityMetadata.HandlerMethod> handlers = inspectHandlers(payloadType);
        List<EntityMetadata.HandlerMethod> applies = handlers.stream()
                .filter(handler -> handler.kind() == EntityMetadata.HandlerKind.APPLY).toList();
        applies.stream().flatMap(handler -> handler.targetModelTypes().stream())
                .forEach(knownModelTypes::addIfAbsent);
        ModelExecutionPlan.DirectSingleTargetApply direct =
                handlers.size() == 1 && applies.size() == 1
                        ? ModelExecutionPlan.Compiler.directSingleTargetApply(applies.getFirst(), payloadType)
                        : null;
        PlanTraits traits = inspectPlanTraits(payloadType, new LinkedHashSet<>());
        return new ModelExecutionPlan(
                compiler, compiler.compileHandlers(handlers),
                ModelTargetResolver.compile(payloadType, handlers),
                direct,
                ModelCommitPolicy.merge(traits.policies()),
                traits.commit(),
                traits.commit() && traits.automatic());
    }

    private void clearPlans() {
        plans.clear();
        recentPlan = null;
    }

    private List<EntityMetadata.HandlerMethod> inspectHandlers(Class<?> payloadType) {
        LinkedHashSet<EntityMetadata.HandlerMethod> result =
                new LinkedHashSet<>(EntityMetadata.of(payloadType).handlerMethods());
        LinkedHashSet<Class<?>> receiverTypes =
                new LinkedHashSet<>(ModelTargetResolver.referencedModelTypes(payloadType));
        receiverTypes.addAll(registeredModelTypes);
        for (Class<?> receiverType : receiverTypes) {
            EntityMetadata.of(receiverType).handlerMethods().stream()
                    .filter(handler -> EntityMetadata.acceptsPayload(handler, payloadType))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private PlanTraits inspectPlanTraits(Class<?> payloadType, Set<Class<?>> visiting) {
        if (!visiting.add(payloadType)) {
            return PlanTraits.NEUTRAL;
        }
        try {
            boolean commit = false;
            boolean automatic = true;
            LinkedHashSet<ModelCommitPolicy> policies = new LinkedHashSet<>();
            for (EntityMetadata.HandlerMethod handler : inspectHandlers(payloadType)) {
                if (handler.kind() == EntityMetadata.HandlerKind.APPLY) {
                    commit |= handler.hasApplyResult();
                    if (handler.hasApplyResult()) {
                        automatic &= automaticHandlingEnabled(handler);
                    }
                    if (handler.dynamicApplyResult()) {
                        policies.add(ModelCommitPolicy.SYNC_AFTER_HANDLER);
                    }
                    handler.targetModelTypes().stream()
                            .map(EntityMetadata::of).map(EntityMetadata::model).flatMap(Optional::stream)
                            .map(Model::commitPolicy).map(ModelCommitPolicy::resolve)
                            .forEach(policies::add);
                } else if (handler.kind() == EntityMetadata.HandlerKind.INTERCEPT_APPLY) {
                    commit |= handler.emittedPayloadTypes().isEmpty();
                    for (Class<?> emitted : handler.emittedPayloadTypes()) {
                        PlanTraits nested = inspectPlanTraits(emitted, visiting);
                        commit |= nested.commit();
                        automatic &= nested.automatic();
                        policies.addAll(nested.policies());
                    }
                }
            }
            return new PlanTraits(commit, automatic, policies);
        } finally {
            visiting.remove(payloadType);
        }
    }

    private boolean automaticHandlingEnabled(EntityMetadata.HandlerMethod handler) {
        Apply apply = handler.executable().getAnnotation(Apply.class);
        AutomaticModelHandling policy =
                apply == null ? AutomaticModelHandling.DEFAULT : apply.automaticHandling();
        if (policy == AutomaticModelHandling.DEFAULT) {
            policy = handler.targetModelTypes().stream()
                    .map(type -> type.getAnnotation(Model.class)).filter(Objects::nonNull)
                    .map(Model::automaticHandling)
                    .filter(value -> value != AutomaticModelHandling.DEFAULT)
                    .findFirst().orElse(AutomaticModelHandling.DEFAULT);
        }
        return (policy == AutomaticModelHandling.DEFAULT ? automaticHandling : policy)
               != AutomaticModelHandling.DISABLED;
    }

    private static boolean isFrameworkParameter(Class<?> type) {
        return type.equals(Instant.class)
               || type.equals(io.fluxzero.common.api.Metadata.class)
               || type.equals(Message.class)
               || type.equals(DeserializingMessage.class);
    }

    private record CachedExecutionPlan(Class<?> payloadType, ModelExecutionPlan plan) {
    }

    private record PlanTraits(boolean commit, boolean automatic, Set<ModelCommitPolicy> policies) {
        private static final PlanTraits NEUTRAL = new PlanTraits(false, true, Set.of());

        private PlanTraits {
            policies = Set.copyOf(policies);
        }
    }

    @Override
    public void close() {
    }
}
