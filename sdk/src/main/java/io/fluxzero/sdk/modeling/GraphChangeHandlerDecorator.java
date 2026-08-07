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
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import lombok.SneakyThrows;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static io.fluxzero.common.api.modeling.ModelEventMetadata.COMMIT_ID;
import static io.fluxzero.common.api.modeling.ModelEventMetadata.SUBSTEP;

/**
 * Expands a payload-free event or notification handler with one {@link Graph} parameter to every root changed by the
 * exact durable model event. Ordinary handlers stay on their existing path.
 */
public final class GraphChangeHandlerDecorator {
    private GraphChangeHandlerDecorator() {
    }

    /** Returns the parameter resolver used solely by graph-change handler methods. */
    public static ParameterResolver<? super DeserializingMessage>
            parameterResolver() {
        return new GraphChangeParameterResolver();
    }

    /** Wraps event and notification handlers with graph-change fan-out support. */
    public static Handler<DeserializingMessage> wrap(
            Handler<DeserializingMessage> handler,
            MessageType messageType) {
        List<Plan> graphChangePlans = graphChangePlans(
                handler.getTargetClass(), messageType);
        if (graphChangePlans.isEmpty()) {
            return handler;
        }
        return new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return handler.getTargetClass();
            }

            @Override
            public java.util.Optional<HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return java.util.Optional.ofNullable(
                        getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(
                    DeserializingMessage message) {
                HandlerInvoker selected =
                        handler.getInvokerOrNull(message);
                Executable selectedMethod = selected == null
                        ? null : selected.getMethod();
                Plan plan = selected == null ? null
                        : graphChangePlans.stream()
                                .filter(candidate -> candidate.matches(
                                        selectedMethod))
                                .findFirst().orElse(null);
                if (selected == null) {
                    for (Plan candidate : graphChangePlans) {
                        HandlerInvoker resolved = select(
                                handler, message, candidate);
                        if (resolved != null
                            && candidate.matches(resolved.getMethod())) {
                            selected = resolved;
                            plan = candidate;
                            break;
                        }
                    }
                }
                if (selected == null) {
                    return null;
                }
                if (plan == null) {
                    return selected;
                }
                if (message.getMetadataValue(COMMIT_ID) == null
                    || message.getMetadataValue(SUBSTEP) == null) {
                    return null;
                }
                HandlerInvoker selectedInvoker = selected;
                Plan selectedPlan = plan;
                return new HandlerInvoker.DelegatingHandlerInvoker(
                        selectedInvoker) {
                    @Override
                    @SneakyThrows
                    public Object invoke(
                            BiFunction<Object, Object, Object> combiner) {
                        List<? extends Graph<?>> graphs =
                                changedGraphs(message, selectedPlan.typedModelType());
                        Object result = null;
                        boolean first = true;
                        for (Graph<?> graph : graphs) {
                            Object next = GraphChangeInvocation.call(
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
                            result = first ? next
                                    : combiner.apply(result, next);
                            first = false;
                        }
                        return result;
                    }
                };
            }
        };
    }

    private static List<Plan> graphChangePlans(
            Class<?> targetType,
            MessageType messageType) {
        if (messageType != MessageType.EVENT
            && messageType != MessageType.NOTIFICATION) {
            return List.of();
        }
        Class<? extends java.lang.annotation.Annotation> annotation =
                messageType == MessageType.EVENT
                        ? HandleEvent.class
                        : HandleNotification.class;
        return ReflectionUtils.getAllMethods(targetType).stream()
                .filter(method -> ReflectionUtils.getMethodAnnotation(
                        method, annotation).isPresent())
                .map(Plan::inspect)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @SneakyThrows
    private static HandlerInvoker select(
            Handler<DeserializingMessage> handler,
            DeserializingMessage message,
            Plan plan) {
        return GraphChangeInvocation.call(
                plan.parameter(), null,
                () -> handler.getInvokerOrNull(message));
    }

    private static <T> List<Graph<T>> changedGraphs(
            DeserializingMessage message,
            Class<T> rootType) {
        Fluxzero fluxzero = Fluxzero.get();
        String namespace = ClientUtils.getConsumerNamespace(message);
        String commitId = message.getMetadataValue(COMMIT_ID);
        int substep = parseSubstep(message.getMetadataValue(SUBSTEP));
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
        List<ModelChangeTarget> targets = change.getTargets();
        if (targets.isEmpty()) {
            targets = payloadTypes.entrySet().stream()
                    .map(entry -> new ModelChangeTarget(
                            entry.getKey(), entry.getValue().getName()))
                    .toList();
        }

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
            addRoots(
                    roots,
                    ancestors.loadAncestorGraphs(
                            target.getModelId(), targetType, rootType,
                            ModelAncestorResolver.Boundary.state(
                                    currentState, false)));
            if (previousState >= -1L) {
                addRoots(
                        roots,
                        ancestors.loadAncestorGraphs(
                                target.getModelId(), targetType, rootType,
                                ModelAncestorResolver.Boundary.state(
                                        previousState, false)));
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
                fluxzero.serializer().upcastType(
                        target.getModelType()), null);
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

    private record Plan(Parameter parameter, Class<?> modelType) {
        private static Plan inspect(Executable method) {
            if (!isGraphChangeMethod(method)) {
                return null;
            }
            Parameter graphParameter = method.getParameters()[0];
            ModelMetadata.ModelParameter model = ModelMetadata
                    .inspectModelParameter(graphParameter).orElseThrow();
            return new Plan(graphParameter, model.modelType());
        }

        private boolean matches(Executable method) {
            return parameter.getDeclaringExecutable().equals(method);
        }

        private static boolean isGraphChangeMethod(
                Executable method) {
            return GraphChangeParameterResolver
                    .isGraphChangeMethod(method);
        }

        @SuppressWarnings("unchecked")
        private <T> Class<T> typedModelType() {
            return (Class<T>) modelType;
        }
    }
}
