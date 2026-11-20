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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.GetModelChange;
import io.fluxzero.common.api.modeling.GetModelChangeResult;
import io.fluxzero.common.api.modeling.ModelChangeTarget;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelRelationshipRead;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.persisting.repository.ModelGraphResolver;
import io.fluxzero.sdk.persisting.repository.ModelTypeResolver;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import lombok.SneakyThrows;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
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

    private static final ThreadLocal<Set<Parameter>> payloadSelectionExclusions = ThreadLocalContext.create();

    /** Wraps event and notification handlers that observe complete graph changes with optional Message/Metadata context. */
    public static Handler<DeserializingMessage> wrapGraphChanges(
            Handler<DeserializingMessage> handler,
            MessageType messageType) {
        return wrapGraphChanges(handler, messageType, null);
    }

    /**
     * Expands Graph subscriptions using the factory's undecorated resolver source for parameter classification.
     * User decorators remain responsible for selection and invocation, and need not expose resolver-role metadata.
     * A null source retains the legacy Graph/Message/Metadata signatures for custom handlers.
     */
    public static Handler<DeserializingMessage> wrapGraphChanges(
            Handler<DeserializingMessage> handler, MessageType messageType,
            Handler<DeserializingMessage> resolverSource) {
        boolean describedRoles = resolverSource != null;
        List<GraphHandler> graphHandlers = graphChangeHandlers(handler.getTargetClass(), messageType, describedRoles);
        boolean dynamicRoles = describedRoles && graphHandlers.stream()
                .anyMatch(h -> h.contextParameters() != null);
        if (graphHandlers.isEmpty()) {
            return handler;
        }
        Set<Parameter> subscriptionParameters = graphHandlers.stream().map(GraphHandler::parameter)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (dynamicRoles) {
            return new Handler.DelegatingHandler<>(handler) {
                @Override
                public ConsumerFilter getConsumerFilter() {
                    return delegate.getConsumerFilter();
                }

                @Override
                public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                    return Optional.ofNullable(getInvokerOrNull(message));
                }

                @Override
                @SneakyThrows
                public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                    List<GraphHandler> applicable = contextHandlers(resolverSource, message, graphHandlers);
                    Set<Parameter> exclusions = applicable.stream().map(GraphHandler::parameter)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    return selectGraphInvoker(handler, message, applicable, exclusions);
                }
            };
        }
        return new Handler.DelegatingHandler<>(handler) {
            @Override
            public ConsumerFilter getConsumerFilter() {
                return delegate.getConsumerFilter();
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            @SneakyThrows
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                return selectGraphInvoker(handler, message, graphHandlers, subscriptionParameters);
            }
        };
    }

    private static HandlerInvoker selectGraphInvoker(
            Handler<DeserializingMessage> handler, DeserializingMessage message,
            List<GraphHandler> graphHandlers, Set<Parameter> subscriptionParameters) throws Exception {
        HandlerInvoker selected = selectPayloadHandler(handler, message, subscriptionParameters);
        Parameter parameter = selected == null ? null : graphParameter(selected.getMethod());
        if (parameter == null && selected != null) {
            return selected;
        }
        if (!modelChange(message)) {
            return null;
        }
        HandlerInvoker representative = representativeInvoker(
                handler, message, graphHandlers);
        if (representative == null) {
            return null;
        }
        return new HandlerInvoker.DelegatingHandlerInvoker(representative) {
            @Override
            @SneakyThrows
            public Object invoke(
                    BiFunction<Object, Object, Object> combiner) {
                Object result = null;
                boolean first = true;
                LinkedHashSet<GraphKey> handled = new LinkedHashSet<>();
                GraphChanges changes = loadGraphChanges(message);
                for (GraphHandler graphHandler : graphHandlers) {
                    for (Graph<?> graph : changes.graphs(graphHandler.modelType())) {
                        if (!handled.add(new GraphKey(
                                graph.id().toString(), graph.type(), graph.stateIndex()))) {
                            continue;
                        }
                        HandlerInvoker invoker = withGraph(
                                graphHandler.parameter(), graph, true,
                                () -> handler.getInvokerOrNull(message));
                        if (invoker == null
                            || !graphHandler.executable().equals(invoker.getMethod())) {
                            throw new IllegalStateException(
                                    "Could not select graph-change handler %s for %s"
                                            .formatted(graphHandler.executable(), graph.type()));
                        }
                        Object next = withGraph(
                                graphHandler.parameter(), graph, false,
                                () -> invoker.invoke(combiner));
                        result = first ? next : combiner.apply(result, next);
                        first = false;
                    }
                }
                return result;
            }
        };
    }

    private static List<GraphHandler> contextHandlers(Handler<DeserializingMessage> handler,
            DeserializingMessage message, List<GraphHandler> candidates) throws Exception {
        List<GraphHandler> result = new ArrayList<>();
        for (GraphHandler candidate : candidates) {
            if (candidate.contextParameters() == null) {
                result.add(candidate);
                continue;
            }
            HandlerInvoker invoker = withGraph(candidate.parameter(), null, true,
                    () -> handler.getInvokerOrNull(message));
            if (invoker != null && candidate.executable().equals(invoker.getMethod())) {
                Set<Parameter> context = invoker.getContextParameters();
                if (context != null && context.containsAll(candidate.contextParameters())) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    /** Whether this method needs resolver-role capture for complete Graph-change selection. */
    public static boolean describesParameters(Executable method) {
        return graphParameter(method) == null && structuralGraphParameter(method) != null;
    }

    private static Parameter structuralGraphParameter(Executable method) {
        Parameter graph = null;
        for (Parameter parameter : method.getParameters()) {
            var model = EntityMetadata.inspectModelParameter(parameter).orElse(null);
            if (model != null) {
                if (!model.graphWrapped() || model.associationProperty() != null || graph != null) {
                    return null;
                }
                graph = parameter;
            }
        }
        return graph;
    }

    private static HandlerInvoker selectPayloadHandler(
            Handler<DeserializingMessage> handler, DeserializingMessage message, Set<Parameter> exclusions) {
        // Complete subscriptions bind from durable changed targets, never from payload ID candidates.
        Set<Parameter> previous = payloadSelectionExclusions.get();
        payloadSelectionExclusions.set(exclusions);
        try {
            return handler.getInvokerOrNull(message);
        } finally {
            if (previous == null) {
                payloadSelectionExclusions.remove();
            } else {
                payloadSelectionExclusions.set(previous);
            }
        }
    }

    static boolean excludedFromPayloadSelection(Parameter parameter) {
        Set<Parameter> exclusions = payloadSelectionExclusions.get();
        return exclusions != null && exclusions.contains(parameter);
    }

    private static HandlerInvoker representativeInvoker(
            Handler<DeserializingMessage> handler,
            DeserializingMessage message,
            List<GraphHandler> graphHandlers) throws Exception {
        for (GraphHandler graphHandler : graphHandlers) {
            HandlerInvoker invoker = withGraph(
                    graphHandler.parameter(), null, true,
                    () -> handler.getInvokerOrNull(message));
            if (invoker != null
                && graphHandler.executable().equals(invoker.getMethod())) {
                return invoker;
            }
        }
        return null;
    }

    private static List<GraphHandler> graphChangeHandlers(
            Class<?> targetType,
            MessageType messageType, boolean describedRoles) {
        if (messageType != MessageType.EVENT
            && messageType != MessageType.NOTIFICATION) {
            return List.of();
        }
        Class<? extends Annotation> annotation = messageType == MessageType.EVENT
                ? HandleEvent.class : HandleNotification.class;
        return ReflectionUtils.getTypeMetadata(targetType).methods().stream()
                .filter(method -> ReflectionUtils.getMethodAnnotation(
                        method, annotation).isPresent())
                .map(method -> {
                    Parameter parameter = describedRoles ? structuralGraphParameter(method) : graphParameter(method);
                    if (parameter == null) {
                        return null;
                    }
                    Class<?> modelType = EntityMetadata.inspectModelParameter(
                            parameter).orElseThrow().modelType();
                    return new GraphHandler(method, parameter, modelType,
                            describedRoles && describesParameters(method)
                                    ? java.util.Arrays.stream(method.getParameters())
                                            .filter(p -> !p.equals(parameter)).toList() : null);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        GraphHandler::modelType,
                        ReflectionUtils.getClassSpecificityComparator()))
                .toList();
    }

    private static boolean modelChange(DeserializingMessage message) {
        return ModelEventMetadata.readBoundary(message.getMetadata()) != null;
    }

    private static GraphChanges loadGraphChanges(DeserializingMessage message) {
        Fluxzero fluxzero = Fluxzero.get();
        String namespace = getConsumerNamespace(message);
        ModelReadBoundary boundary = ModelEventMetadata.readBoundary(message.getMetadata());
        String commitId = boundary.commitId();
        int substep = boundary.substep();
        var eventStore = fluxzero.client().forNamespace(namespace).getEventStoreClient();
        GetModelChangeResult change = eventStore.getModelChange(new GetModelChange(commitId, substep));
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
        List<GetModelChangeResult> cascades = new ArrayList<>();
        for (int cascade : ModelEventMetadata.cascadeSubsteps(message.getMetadata())) {
            GetModelChangeResult deletion = eventStore.getModelChange(new GetModelChange(commitId, cascade));
            if (deletion.getEventIndex() != null || deletion.getStateIndex() <= change.getStateIndex()) {
                throw new IllegalStateException("Model cascade reference does not identify a later internal change");
            }
            cascades.add(deletion);
        }
        return new GraphChanges(repository, ancestors, payloadTypes, change, cascades, new LinkedHashMap<>());
    }

    private record GraphChanges(ModelRepository repository, ModelAncestorResolver ancestors,
                                Map<String, Class<?>> payloadTypes, GetModelChangeResult change,
                                List<GetModelChangeResult> cascades, Map<AncestryKey, List<GraphRoot>> ancestry) {
        private <T> List<Graph<T>> graphs(Class<T> rootType) {
            List<Graph<T>> result = graphsForChange(change, rootType, this, false);
            if (cascades.isEmpty()) { return result; }
            result = new ArrayList<>(result);
            for (GetModelChangeResult deletion : cascades) {
                // Keep the cascade's own boundary, including surviving ancestors in shared graphs.
                result.addAll(graphsForChange(deletion, rootType, this, true));
            }
            return List.copyOf(result);
        }

        private <T> void addMetadataAncestors(Map<String, Class<? extends T>> roots, String targetId,
                                              Class<T> rootType, long stateIndex) {
            for (GraphRoot root : ancestry.computeIfAbsent(new AncestryKey(targetId, stateIndex),
                    key -> loadMetadataAncestors(repository, key))) {
                if (rootType.isAssignableFrom(root.type())) {
                    roots.putIfAbsent(root.id(), root.type().asSubclass(rootType));
                }
            }
        }
    }

    private static <T> List<Graph<T>> graphsForChange(GetModelChangeResult change, Class<T> rootType,
                                                     GraphChanges changes, boolean cascade) {
        ModelRepository repository = changes.repository();
        Map<String, Class<?>> payloadTypes = changes.payloadTypes();
        List<ModelChangeTarget> targets = change.getTargets().isEmpty()
                ? payloadTypes.entrySet().stream()
                        .map(entry -> new ModelChangeTarget(
                                entry.getKey(), repository instanceof ModelTypeResolver resolver
                                        ? resolver.modelName(entry.getValue())
                                        : ModelNames.name(entry.getValue())))
                        .toList()
                : change.getTargets();
        long currentState = change.getStateIndex();
        long previousState = currentState - 1L;
        LinkedHashMap<String, Class<? extends T>> roots = new LinkedHashMap<>();
        for (ModelChangeTarget target : targets) {
            Class<?> targetType = targetType(
                    target, payloadTypes, repository);
            if (targetType == null) {
                changes.addMetadataAncestors(roots, target.getModelId(), rootType, currentState);
                if (previousState >= -1L) {
                    changes.addMetadataAncestors(roots, target.getModelId(), rootType, previousState);
                }
                continue;
            }
            if (rootType.isAssignableFrom(targetType)) {
                roots.putIfAbsent(
                        target.getModelId(), targetType.asSubclass(rootType));
                continue;
            }
            if (repository instanceof ModelGraphResolver resolver && resolver.documentFallbackEnabled()) {
                // Discover affected roots without materializing unavailable historical document values.
                changes.addMetadataAncestors(roots, target.getModelId(), rootType, currentState);
                if (previousState >= -1L) {
                    changes.addMetadataAncestors(roots, target.getModelId(), rootType, previousState);
                }
                continue;
            }
            addRoots(roots, changes.ancestors().loadAncestorGraphs(
                    target.getModelId(), targetType, rootType,
                    ModelReadBoundary.state(currentState, false)));
            if (previousState >= -1L) {
                addRoots(roots, changes.ancestors().loadAncestorGraphs(
                        target.getModelId(), targetType, rootType,
                        ModelReadBoundary.state(previousState, false)));
            }
        }
        List<Graph<T>> result = new ArrayList<>(roots.size());
        Set<String> cascadeTargetIds = cascade ? new LinkedHashSet<>() : Set.of();
        if (cascade) {
            targets.forEach(target -> cascadeTargetIds.add(target.getModelId()));
        }
        roots.forEach((rootId, concreteType) -> {
            Graph<T> current = cast(Graphs.changedGraph(rootId, concreteType, currentState, repository));
            Graph<T> previous = previousState < -1L ? null
                    : cast(Graphs.changedGraph(rootId, concreteType, previousState, repository));
            if (cascade && !cascadeTargetIds.contains(rootId)
                && current.isEmpty() && (previous == null || previous.isEmpty())) {
                return; // The ordinary deletion already notified an explicitly deleted ancestor.
            }
            if (previous != null
                && previous.isEmpty()
                && current.isPresent()) {
                previous = null;
            }
            result.add(Graphs.withPrevious(current, previous));
        });
        return List.copyOf(result);
    }

    private static List<GraphRoot> loadMetadataAncestors(ModelRepository repository, AncestryKey key) {
        if (!(repository instanceof ModelGraphResolver resolver)) {
            throw new UnsupportedOperationException("Unknown Graph-change targets require metadata-only ancestor resolution");
        }
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        LinkedHashSet<GraphRoot> roots = new LinkedHashSet<>();
        List<String> frontier = List.of(key.modelId());
        var boundary = ModelReadBoundary.state(key.stateIndex(), false);
        while (!frontier.isEmpty()) {
            visited.addAll(frontier);
            ModelGraphResolver.Relations relations = resolver.loadGraphRelations(frontier,
                    ModelRelationshipRead.Direction.PARENTS, boundary, Map.of(), true);
            if (!Objects.equals(relations.boundary().stateIndex(), key.stateIndex())) {
                throw new IllegalStateException("Graph-change ancestors moved their pinned boundary");
            }
            // A resolver may return the full closure or only the requested direct collections.
            visited.addAll(relations.completeCollections());
            relations.models().values().forEach(node -> {
                if (!node.id().equals(key.modelId()) && node.knownType() != null) {
                    roots.add(new GraphRoot(node.id(), node.knownType()));
                }
            });
            frontier = relations.edges().stream().map(edge -> edge.getParentId())
                    .filter(id -> !visited.contains(id)).distinct().toList();
        }
        return List.copyOf(roots);
    }

    private record AncestryKey(String modelId, long stateIndex) {}

    private record GraphRoot(String id, Class<?> type) {}

    private static Class<?> targetType(
            ModelChangeTarget target,
            Map<String, Class<?>> payloadTypes,
            ModelRepository repository) {
        if (target.getModelType() == null
            || target.getModelType().isBlank()) {
            return payloadTypes.get(target.getModelId());
        }
        if (repository instanceof ModelTypeResolver resolver) {
            return resolver.knownModelType(target.getModelType(), target.getModelId()).orElse(null);
        }
        throw new UnsupportedOperationException(
                "Graph-change handlers require a Model repository with a logical type catalog");
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

    static boolean resolvingGraphChange() {
        GraphArgument value = graphArgument.get();
        return value != null && value.selecting();
    }

    static boolean suppliesGraph(Parameter parameter) {
        GraphArgument value = graphArgument.get();
        return value != null && (value.parameter() == null
                                 ? parameter.equals(graphParameter(parameter.getDeclaringExecutable()))
                                 : value.parameter().equals(parameter));
    }

    static Graph<?> suppliedGraph(Parameter parameter) {
        GraphArgument value = graphArgument.get();
        return value != null && (value.parameter() == null || value.parameter().equals(parameter))
                ? value.graph() : null;
    }

    private static <T> T withGraph(
            Parameter parameter,
            Graph<?> graph,
            boolean selecting,
            Callable<T> task) throws Exception {
        GraphArgument previous = graphArgument.get();
        graphArgument.set(new GraphArgument(parameter, graph, selecting));
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

    private static Parameter graphParameter(Executable method) {
        Parameter graphParameter = null;
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getType() == Message.class || parameter.getType() == Metadata.class) {
                continue;
            }
            // Keep payload, qualified, and multiple-Graph signatures on ordinary handler selection.
            if (!Graph.class.isAssignableFrom(parameter.getType()) || graphParameter != null) {
                return null;
            }
            graphParameter = parameter;
        }
        if (graphParameter == null) {
            return null;
        }
        EntityMetadata.ModelParameter model = EntityMetadata.inspectModelParameter(graphParameter).orElse(null);
        return model != null && model.graphWrapped() && model.associationProperty() == null ? graphParameter : null;
    }

    private record GraphArgument(
            Parameter parameter,
            Graph<?> graph,
            boolean selecting) {
    }

    private record GraphHandler(
            Executable executable,
            Parameter parameter,
            Class<?> modelType,
            List<Parameter> contextParameters) {
    }

    private record GraphKey(
            String modelId,
            Class<?> modelType,
            long stateIndex) {
    }

}
