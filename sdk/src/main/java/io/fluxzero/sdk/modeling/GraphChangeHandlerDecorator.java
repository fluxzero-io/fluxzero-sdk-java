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
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
import io.fluxzero.sdk.persisting.repository.ModelReadBoundary;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import lombok.SneakyThrows;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
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
 * Decorates event and notification handlers that observe complete graph changes.
 * Model parameter discovery and resolution belong to {@link EntityParameterResolver}; this decorator only expands one
 * committed change into invocations for the affected current/previous graph pairs.
 */
public final class GraphChangeHandlerDecorator {
    private static final ThreadLocal<GraphArgument> graphArgument =
            ThreadLocalContext.create();

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
        for (MutationPlan.ResolvedModel referenced :
                MutationPlan.resolveReferencedModels(message.getPayload())) {
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
                    ModelReadBoundary.state(currentState, false)));
            if (previousState >= -1L) {
                addRoots(roots, ancestors.loadAncestorGraphs(
                        target.getModelId(), targetType, rootType,
                        ModelReadBoundary.state(previousState, false)));
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

    static Graph<?> suppliedGraph(Parameter parameter) {
        GraphArgument value = graphArgument.get();
        return value != null && value.parameter().equals(parameter) ? value.graph() : null;
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
            EntityMetadata.ModelParameter model = EntityMetadata
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

}
