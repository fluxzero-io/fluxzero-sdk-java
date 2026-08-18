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
import io.fluxzero.common.api.modeling.GetModelChange;
import io.fluxzero.common.api.modeling.GetModelChangeResult;
import io.fluxzero.common.api.modeling.ModelChangeTarget;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import lombok.SneakyThrows;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.isNullable;
import static io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace;

/**
 * Injects independent models into selected message handlers.
 * <p>
 * Every handler kind can address direct models through canonical or typed IDs. A parameter-level
 * {@link io.fluxzero.sdk.tracking.handling.Association @Association} can select another payload or metadata property;
 * when no such direct value exists it qualifies a reachable parent edge. Parents, grandparents, and further
 * ancestors are loaded with the directly addressed descendants in one handler-level context.
 * <p>
 * Handler matching is structural and never performs repository I/O. The complete model plan is loaded only when the
 * selected handler is invoked. Events and notifications require persisted model-commit metadata and are reconstructed
 * at that exact commit boundary; other message types use the repository's current boundary.
 */
public class ModelEntityParameterResolver
        implements PreparedParameterResolver<Object> {
    private static final ThreadLocal<GraphArgument> graphArgument =
            ThreadLocalContext.create();

    @Override
    public boolean mayApply(
            Executable method, Class<?> targetClass) {
        HandlerPlan plan = plan(method);
        return plan.hasModels()
               && (ReflectionUtils.getMethodAnnotation(
                        method, HandleMessage.class).isPresent()
                   || ModelMetadata.of(method.getDeclaringClass())
                        .handlerMethods().stream()
                        .anyMatch(handler ->
                                handler.executable().equals(method)));
    }

    @Override
    public Function<Object, Object> resolve(
            Parameter parameter,
            Annotation methodAnnotation) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        return modelParameter == null ? null
                : input -> argument(parameter, modelParameter, input, plan);
    }

    @Override
    public boolean matches(
            Parameter parameter,
            Annotation methodAnnotation,
            Object input) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        if (modelParameter == null) {
            return false;
        }
        return canResolve(parameter, modelParameter, input, plan);
    }

    @Override
    public Function<Object, Object>
            resolveIfPossible(
                    Parameter parameter,
                    Annotation methodAnnotation,
                    Object input) {
        HandlerPlan plan =
                plan(parameter.getDeclaringExecutable());
        ModelMetadata.ModelParameter modelParameter =
                plan.parameters().get(parameter);
        if (modelParameter == null) {
            return null;
        }
        if (!canResolve(parameter, modelParameter, input, plan)) {
            return null;
        }
        return invocation -> argument(
                parameter, modelParameter, invocation, plan);
    }

    private static boolean canResolve(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Object input,
            HandlerPlan plan) {
        if (suppliesGraph(parameter)) {
            return true;
        }
        if (modelParameter.collectionWrapped()) {
            ModelTargetResolver.DirectModelReferences references =
                    directReferences(input, modelParameter);
            if (!references.present()) {
                return false;
            }
            if (references.modelIds().isEmpty()) {
                return true;
            }
            Optional<ModelCommitContext> context = commitContext(input);
            return context.map(value -> references.modelIds().stream()
                            .allMatch(id -> value.entry(id) != null))
                    .orElseGet(() -> input instanceof DeserializingMessage message
                            && resolvedPlan(message, plan).isPresent());
        }
        if (nullDirectReference(input, modelParameter)) {
            return isNullable(parameter);
        }
        Optional<ModelCommitContext> context = commitContext(input);
        if (context.isEmpty()) {
            return input instanceof DeserializingMessage message
                   && resolvedPlan(message, plan).isPresent();
        }
        Entity<?> entity = context.get().resolve(
                modelParameter.modelType(),
                modelParameter.associationProperty());
        return entity != null
               && (modelParameter.entityWrapped()
                   || modelParameter.graphWrapped()
                   || entity.isPresent()
                   || isNullable(parameter));
    }

    private static Object argument(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Object input,
            HandlerPlan plan) {
        if (suppliesGraph(parameter)) {
            return graphArgument.get().graph();
        }
        if (modelParameter.collectionWrapped()) {
            return collectionValue(parameter, modelParameter, input, plan);
        }
        return nullDirectReference(input, modelParameter) && isNullable(parameter)
                ? null
                : value(parameter, modelParameter,
                        resolveEntity(input, plan, modelParameter), input, plan);
    }

    @Override
    public boolean determinesSpecificity() {
        return true;
    }

    /** Wraps event and notification handlers whose sole argument observes complete graph changes. */
    public static Handler<DeserializingMessage> wrapGraphChanges(
            Handler<DeserializingMessage> handler,
            MessageType messageType) {
        List<GraphPlan> plans = graphPlans(
                handler.getTargetClass(), messageType);
        if (plans.isEmpty()) {
            return handler;
        }
        return new Handler.DelegatingHandler<>(handler) {
            @Override
            public Optional<HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(
                    DeserializingMessage message) {
                HandlerInvoker selected = handler.getInvokerOrNull(message);
                Executable selectedMethod = selected == null
                        ? null : selected.getMethod();
                GraphPlan plan = selectedMethod == null ? null : plans.stream()
                        .filter(candidate -> candidate.matches(selectedMethod))
                        .findFirst().orElse(null);
                if (selected == null) {
                    for (GraphPlan candidate : plans) {
                        HandlerInvoker resolved = selectGraphHandler(
                                handler, message, candidate);
                        if (resolved != null
                            && candidate.matches(resolved.getMethod())) {
                            selected = resolved;
                            plan = candidate;
                            break;
                        }
                    }
                }
                if (selected == null || plan == null) {
                    return selected;
                }
                if (message.getMetadataValue(ModelEventMetadata.COMMIT_ID) == null
                    || message.getMetadataValue(ModelEventMetadata.SUBSTEP) == null) {
                    return null;
                }
                HandlerInvoker invoker = selected;
                GraphPlan selectedPlan = plan;
                return new HandlerInvoker.DelegatingHandlerInvoker(invoker) {
                    @Override
                    @SneakyThrows
                    public Object invoke(
                            BiFunction<Object, Object, Object> combiner) {
                        Object result = null;
                        boolean first = true;
                        for (Graph<?> graph : changedGraphs(
                                message, selectedPlan.typedModelType())) {
                            Object next = withGraph(
                                    selectedPlan.parameter(), graph,
                                    () -> {
                                        HandlerInvoker actual =
                                                handler.getInvokerOrNull(message);
                                        if (actual == null
                                            || !actual.getMethod().equals(
                                                delegate.getMethod())) {
                                            throw new IllegalStateException(
                                                    "Graph-change handler selection changed while supplying "
                                                    + graph.id());
                                        }
                                        return actual.invoke(combiner);
                                    });
                            result = first ? next : combiner.apply(result, next);
                            first = false;
                        }
                        return result;
                    }
                };
            }
        };
    }

    private static List<GraphPlan> graphPlans(
            Class<?> targetType,
            MessageType messageType) {
        if (messageType != MessageType.EVENT
            && messageType != MessageType.NOTIFICATION) {
            return List.of();
        }
        Class<? extends Annotation> annotation = messageType == MessageType.EVENT
                ? HandleEvent.class : HandleNotification.class;
        return ReflectionUtils.getAllMethods(targetType).stream()
                .filter(method -> ReflectionUtils.getMethodAnnotation(
                        method, annotation).isPresent())
                .map(GraphPlan::inspect)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @SneakyThrows
    private static HandlerInvoker selectGraphHandler(
            Handler<DeserializingMessage> handler,
            DeserializingMessage message,
            GraphPlan plan) {
        return withGraph(
                plan.parameter(), null,
                () -> handler.getInvokerOrNull(message));
    }

    private static <T> List<Graph<T>> changedGraphs(
            DeserializingMessage message,
            Class<T> rootType) {
        Fluxzero fluxzero = Fluxzero.get();
        String namespace = getConsumerNamespace(message);
        String commitId = message.getMetadataValue(
                ModelEventMetadata.COMMIT_ID);
        int substep = parseSubstep(message.getMetadataValue(
                ModelEventMetadata.SUBSTEP));
        GetModelChangeResult change = fluxzero.client()
                .forNamespace(namespace).getEventStoreClient()
                .getModelChange(new GetModelChange(commitId, substep));
        if (message.getIndex() != null
            && change.getEventIndex() != null
            && !message.getIndex().equals(change.getEventIndex())) {
            throw new IllegalStateException(
                    "Model change %s[%d] belongs to event %d instead of handled event %d"
                            .formatted(commitId, substep, change.getEventIndex(), message.getIndex()));
        }
        ModelRepository repository = fluxzero.modelRepository()
                .forNamespace(namespace);
        if (!(repository instanceof ModelAncestorResolver ancestors)) {
            throw new UnsupportedOperationException(
                    "Graph-change handlers require a model repository that resolves ancestors");
        }
        Map<String, Class<?>> payloadTypes = new LinkedHashMap<>();
        for (ModelTargetResolver.ResolvedModel referenced :
                ModelTargetResolver.resolveReferencedModels(message.getPayload())) {
            payloadTypes.put(referenced.modelId(), referenced.modelType());
        }
        List<ModelChangeTarget> targets = change.getTargets().isEmpty()
                ? payloadTypes.entrySet().stream()
                        .map(entry -> new ModelChangeTarget(
                                entry.getKey(), entry.getValue().getName()))
                        .toList()
                : change.getTargets();
        long currentState = change.getStateIndex();
        long previousState = currentState - 1L;
        LinkedHashMap<String, Class<? extends T>> roots =
                new LinkedHashMap<>();
        for (ModelChangeTarget target : targets) {
            Class<?> targetType = targetType(
                    target, payloadTypes, fluxzero);
            if (targetType == null) {
                continue;
            }
            if (rootType.isAssignableFrom(targetType)) {
                roots.putIfAbsent(
                        target.getModelId(), targetType.asSubclass(rootType));
                continue;
            }
            addRoots(roots, ancestors.loadAncestorGraphs(
                    target.getModelId(), targetType, rootType,
                    ModelAncestorResolver.Boundary.state(currentState, false)));
            if (previousState >= -1L) {
                addRoots(roots, ancestors.loadAncestorGraphs(
                        target.getModelId(), targetType, rootType,
                        ModelAncestorResolver.Boundary.state(previousState, false)));
            }
        }
        List<Graph<T>> result = new ArrayList<>(roots.size());
        roots.forEach((rootId, concreteType) -> {
            Graph<T> current = cast(repository.loadGraphAt(
                    rootId, concreteType, currentState,
                    Graph.Options.DEFAULT));
            Graph<T> previous = previousState < -1L ? null
                    : cast(repository.loadGraphAt(
                            rootId, concreteType, previousState,
                            Graph.Options.DEFAULT));
            if (previous != null
                && previous.isEmpty()
                && current.isPresent()) {
                previous = null;
            }
            result.add(Graphs.withPrevious(current, previous));
        });
        return List.copyOf(result);
    }

    private static Class<?> targetType(
            ModelChangeTarget target,
            Map<String, Class<?>> payloadTypes,
            Fluxzero fluxzero) {
        if (target.getModelType() == null
            || target.getModelType().isBlank()) {
            return payloadTypes.get(target.getModelId());
        }
        return ReflectionUtils.classForName(
                fluxzero.serializer().upcastType(target.getModelType()), null);
    }

    private static <T> void addRoots(
            Map<String, Class<? extends T>> roots,
            List<Graph<T>> additions) {
        additions.forEach(graph -> roots.putIfAbsent(
                graph.id().toString(), graph.type()));
    }

    @SuppressWarnings("unchecked")
    private static <T> Graph<T> cast(Graph<? extends T> graph) {
        return (Graph<T>) graph;
    }

    private static int parseSubstep(String value) {
        try {
            int result = Integer.parseInt(value);
            if (result < 0) {
                throw new NumberFormatException("negative");
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid model commit substep " + value, e);
        }
    }

    static boolean suppliesGraph(Parameter parameter) {
        GraphArgument value = graphArgument.get();
        return value != null && value.parameter().equals(parameter);
    }

    private static <T> T withGraph(
            Parameter parameter,
            Graph<?> graph,
            Callable<T> task) throws Exception {
        GraphArgument previous = graphArgument.get();
        graphArgument.set(new GraphArgument(parameter, graph));
        try {
            return task.call();
        } finally {
            if (previous == null) {
                graphArgument.remove();
            } else {
                graphArgument.set(previous);
            }
        }
    }

    private record GraphPlan(
            Parameter parameter,
            Class<?> modelType) {
        private static GraphPlan inspect(Executable method) {
            if (method.getParameterCount() != 1) {
                return null;
            }
            Parameter parameter = method.getParameters()[0];
            ModelMetadata.ModelParameter model = ModelMetadata
                    .inspectModelParameter(parameter).orElse(null);
            return model != null
                   && model.graphWrapped()
                   && model.associationProperty() == null
                    ? new GraphPlan(parameter, model.modelType()) : null;
        }

        private boolean matches(Executable method) {
            return parameter.getDeclaringExecutable().equals(method);
        }

        @SuppressWarnings("unchecked")
        private <T> Class<T> typedModelType() {
            return (Class<T>) modelType;
        }
    }

    private record GraphArgument(
            Parameter parameter,
            Graph<?> graph) {
    }

    private static Object value(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Entity<?> entity,
            Object input,
            HandlerPlan plan) {
        if (modelParameter.entityWrapped()) {
            return entity;
        }
        if (modelParameter.graphWrapped()) {
            if (entity == null) {
                return null;
            }
            ModelCommitContext context = commitContext(input)
                    .orElseGet(() -> input instanceof DeserializingMessage message
                            ? context(message, plan) : null);
            DeserializingMessage message = input instanceof DeserializingMessage deserializingMessage
                    ? deserializingMessage : DeserializingMessage.getOptionally().orElse(null);
            ModelRepository repository = message == null
                    ? Fluxzero.get().modelRepository() : currentRepository(message);
            if (context != null) {
                return Graphs.lazy(entity, context, repository);
            }
            long stateIndex = entity instanceof ModelRoot<?> root ? root.stateIndex() : -1L;
            return Graphs.lazy(entity, stateIndex, repository);
        }
        if (entity == null || !entity.isPresent()) {
            if (isNullable(parameter)) {
                return null;
            }
            throw new IllegalStateException(
                    "Model parameter %s in %s resolved to a missing or deleted %s model"
                            .formatted(
                                    parameter,
                                    parameter
                                            .getDeclaringExecutable()
                                            .toGenericString(),
                                    modelParameter.modelType()
                                            .getName()));
        }
        return entity.get();
    }

    private static List<Graph<?>> collectionValue(
            Parameter parameter,
            ModelMetadata.ModelParameter modelParameter,
            Object input,
            HandlerPlan plan) {
        ModelTargetResolver.DirectModelReferences references = directReferences(input, modelParameter);
        if (!references.present()) {
            throw new IllegalStateException(
                    "Graph collection parameter %s in %s has no payload property '%s'"
                            .formatted(parameter, parameter.getDeclaringExecutable().toGenericString(),
                                       modelParameter.associationProperty()));
        }
        if (references.modelIds().isEmpty()) {
            return List.of();
        }
        ModelCommitContext context = commitContext(input)
                .orElseGet(() -> input instanceof DeserializingMessage message
                        ? context(message, plan) : null);
        if (context == null) {
            throw new IllegalStateException(
                    "No coherent model context is available for graph collection parameter " + parameter);
        }
        DeserializingMessage message = input instanceof DeserializingMessage deserializingMessage
                ? deserializingMessage : DeserializingMessage.getOptionally().orElse(null);
        ModelRepository repository = message == null
                ? Fluxzero.get().modelRepository() : currentRepository(message);
        List<Graph<?>> result = new java.util.ArrayList<>(references.modelIds().size());
        for (String modelId : references.modelIds()) {
            ModelCommitContext.Entry entry = context.entry(modelId);
            if (entry == null) {
                throw new IllegalStateException(
                        "Model context does not contain '%s' required by graph collection parameter %s"
                                .formatted(modelId, parameter));
            }
            result.add(Graphs.lazy(entry.entity(), context, repository));
        }
        return List.copyOf(result);
    }

    private static Entity<?> resolveEntity(
            Object input,
            HandlerPlan plan,
            ModelMetadata.ModelParameter parameter) {
        Optional<ModelCommitContext> commitContext =
                commitContext(input);
        if (commitContext.isPresent()) {
            return commitContext.get().resolve(
                    parameter.modelType(),
                    parameter.associationProperty());
        }
        return input instanceof DeserializingMessage message
                ? context(message, plan).resolve(
                        parameter.modelType(),
                        parameter.associationProperty())
                : null;
    }

    private static boolean nullDirectReference(
            Object input,
            ModelMetadata.ModelParameter parameter) {
        if (parameter.collectionWrapped()) {
            return false;
        }
        DeserializingMessage message = input instanceof DeserializingMessage direct
                ? direct : DeserializingMessage.getOptionally().orElse(null);
        if (message == null) {
            return false;
        }
        ModelTargetResolver.DirectModelReference reference =
                directReference(message, parameter);
        return reference.present() && reference.modelId() == null;
    }

    private static ModelTargetResolver.DirectModelReferences directReferences(
            Object input,
            ModelMetadata.ModelParameter parameter) {
        DeserializingMessage message = input instanceof DeserializingMessage direct
                ? direct : DeserializingMessage.getOptionally().orElse(null);
        if (message == null) {
            return new ModelTargetResolver.DirectModelReferences(false, List.of());
        }
        return cache(message).collectionReferences.computeIfAbsent(
                parameter, ignored -> computeDirectReferences(message, parameter));
    }

    private static ModelTargetResolver.DirectModelReferences computeDirectReferences(
            DeserializingMessage message,
            ModelMetadata.ModelParameter parameter) {
        return ModelTargetResolver.directReferences(message, parameter);
    }

    private static Optional<ModelCommitContext>
            commitContext(Object input) {
        if (input instanceof DeserializingMessage message) {
            Optional<ModelCommitContext> direct =
                    message.getContext(ModelCommitContext.class);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return DeserializingMessage.getOptionally()
                .flatMap(message -> message.getContext(
                        ModelCommitContext.class));
    }

    private static ModelCommitContext context(
            DeserializingMessage message,
            HandlerPlan plan) {
        return resolvedPlan(message, plan).orElseThrow()
                .context(message);
    }

    private static Optional<ResolvedHandlerPlan>
            resolvedPlan(
                    DeserializingMessage message,
                    HandlerPlan plan) {
        if (!supportsBoundary(message)) {
            return Optional.empty();
        }
        return cache(message).plans
                .computeIfAbsent(
                        plan.executable(),
                        ignored ->
                                plan.resolve(message));
    }

    private static boolean supportsBoundary(
            DeserializingMessage message) {
        if (message.getMessageType()
            != MessageType.EVENT
            && message.getMessageType()
               != MessageType.NOTIFICATION) {
            return true;
        }
        return message.getIndex() != null
               || message.getMetadata() != null
               && message.getMetadata().containsKey(
                ModelEventMetadata.COMMIT_ID)
               && message.getMetadata().containsKey(
                ModelEventMetadata.SUBSTEP);
    }

    private static ResolutionCache cache(
            DeserializingMessage message) {
        return message.computeContextIfAbsent(
                ResolutionCache.class,
                ignored -> new ResolutionCache());
    }

    private static ModelRepository currentRepository(
            DeserializingMessage message) {
        return Fluxzero.get().modelRepository()
                .forNamespace(
                        getConsumerNamespace(message));
    }

    private static HandlerPlan plan(
            Executable executable) {
        return ReflectionUtils.getTypeMetadata(
                        executable.getDeclaringClass())
                .specializedMetadata(
                        HandlerPlans.class,
                        HandlerPlans::new)
                .get(executable);
    }

    private record HandlerPlan(
            Executable executable,
            Map<Parameter, ModelMetadata.ModelParameter>
                    parameters) {

        private static HandlerPlan inspect(
                Executable executable) {
            LinkedHashMap<Parameter,
                    ModelMetadata.ModelParameter> parameters =
                    new LinkedHashMap<>();
            for (Parameter parameter :
                    executable.getParameters()) {
                ModelMetadata
                        .inspectModelParameter(parameter)
                        .ifPresent(modelParameter ->
                                           parameters.put(
                                                   parameter,
                                                   modelParameter));
            }
            return new HandlerPlan(
                    executable,
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    parameters)));
        }

        private boolean hasModels() {
            return !parameters.isEmpty();
        }

        private Optional<ResolvedHandlerPlan> resolve(
                DeserializingMessage message) {
            return ModelTargetResolver.resolveDependencies(
                            message, executable, parameters.values())
                    .map(ResolvedHandlerPlan::new);
        }

    }

    private static ModelTargetResolver.DirectModelReference directReference(
            DeserializingMessage message,
            ModelMetadata.ModelParameter parameter) {
        return ModelTargetResolver.directReference(message, parameter);
    }

    private static final class ResolvedHandlerPlan {
        private final ModelTargetResolver.Resolution resolution;
        private volatile ModelCommitContext context;

        private ResolvedHandlerPlan(
                ModelTargetResolver.Resolution resolution) {
            this.resolution = resolution;
        }

        private ModelCommitContext context(
                DeserializingMessage message) {
            ModelCommitContext result = context;
            if (result == null) {
                synchronized (this) {
                    result = context;
                    if (result == null) {
                        context = result =
                                currentRepository(message)
                                        .loadContext(resolution);
                    }
                }
            }
            return result;
        }
    }

    private static final class HandlerPlans {
        private final Map<Executable, HandlerPlan> plans =
                new ConcurrentHashMap<>();

        @SuppressWarnings("unused")
        private HandlerPlans(Class<?> ignored) {
        }

        private HandlerPlan get(
                Executable executable) {
            return plans.computeIfAbsent(
                    executable,
                    HandlerPlan::inspect);
        }
    }

    private static final class ResolutionCache {
        private final Map<Executable,
                Optional<ResolvedHandlerPlan>> plans =
                new ConcurrentHashMap<>();
        private final Map<ModelMetadata.ModelParameter,
                ModelTargetResolver.DirectModelReferences> collectionReferences =
                new ConcurrentHashMap<>();
    }
}
