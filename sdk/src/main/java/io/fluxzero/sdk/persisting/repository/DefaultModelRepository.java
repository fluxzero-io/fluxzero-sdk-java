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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetModelAncestors;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.caching.NoOpCache;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelActionContext;
import io.fluxzero.sdk.modeling.ModelEventReplayer;
import io.fluxzero.sdk.modeling.ModelGraph;
import io.fluxzero.sdk.modeling.ModelGraphProjections;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.modeling.ModelTargetResolver;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import lombok.NonNull;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.NOTIFICATION;
import static io.fluxzero.common.reflection.ReflectionUtils.classForName;

/**
 * Default repository for independently stored models.
 * <p>
 * Current document-based models use their synchronously maintained direct document. Event-sourced and historical
 * loads use the model-stream protocol and reconstruct every selected stream at one pinned {@code stateIndex}.
 */
public class DefaultModelRepository extends AbstractNamespaced<ModelRepository> implements ModelRepository {
    private static final int ACTION_ANCESTOR_MAX_DEPTH = 64;
    private static final int ACTION_ANCESTOR_MAX_MODELS = 10_000;
    private static final int MAX_PARALLEL_GRAPH_RECONSTRUCTIONS = 8;

    private final Client client;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final EntityHelper entityHelper;
    private final ModelEventReplayer eventReplayer;
    private final ModelEventBatchLoader eventLoader;
    private final Cache cacheSource;
    private final Cache modelCache;
    private final Serializer snapshotSerializer;
    private final ModelSnapshotStore snapshotStore;
    private final ModelCacheTracker modelCacheTracker;

    /**
     * Compatibility constructor for document-only repository use.
     */
    public DefaultModelRepository(Client client, DocumentStore documentStore) {
        this(client, documentStore, null, null, null, NoOpCache.INSTANCE,
             (ModelEventReplayer) null);
    }

    public DefaultModelRepository(
            Client client,
            DocumentStore documentStore,
            Serializer serializer,
            EntityHelper entityHelper,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        this(client, documentStore, serializer, entityHelper, serializer, NoOpCache.INSTANCE,
             parameterResolvers == null ? null : new ModelEventReplayer(parameterResolvers));
    }

    public DefaultModelRepository(
            Client client,
            DocumentStore documentStore,
            Serializer serializer,
            EntityHelper entityHelper,
            Serializer snapshotSerializer,
            Cache cache,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        this(client, documentStore, serializer, entityHelper, snapshotSerializer, cache,
             parameterResolvers == null ? null : new ModelEventReplayer(parameterResolvers));
    }

    private DefaultModelRepository(
            Client client,
            DocumentStore documentStore,
            Serializer serializer,
            EntityHelper entityHelper,
            Serializer snapshotSerializer,
            Cache cache,
            ModelEventReplayer eventReplayer) {
        this.client = Objects.requireNonNull(client, "client");
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
        this.serializer = serializer;
        this.entityHelper = entityHelper;
        this.snapshotSerializer = snapshotSerializer;
        this.eventReplayer = eventReplayer;
        this.eventLoader = client.getEventStoreClient() == null
                ? null : new ModelEventBatchLoader(client.getEventStoreClient());
        this.cacheSource = Objects.requireNonNull(cache, "cache");
        this.modelCache = cache == NoOpCache.INSTANCE
                ? cache : new RepositoryCache(cache, "$Model", client.namespace());
        this.snapshotStore = snapshotSerializer == null
                ? null : new ModelSnapshotStore(documentStore, snapshotSerializer);
        this.modelCacheTracker =
                eventLoader == null
                || cache == NoOpCache.INSTANCE
                        ? null
                        : new ModelCacheTracker(
                                client.getEventStoreClient(),
                                modelCache,
                                this::refreshCurrentModels);
        if (modelCacheTracker != null) {
            client.beforeShutdown(
                    modelCacheTracker::close);
        }
    }

    @Override
    protected ModelRepository createForNamespace(String namespace) {
        Client namespacedClient = client.forNamespace(namespace);
        DocumentStore namespacedDocumentStore = documentStore.forNamespace(namespace);
        return new DefaultModelRepository(
                namespacedClient, namespacedDocumentStore, serializer, entityHelper,
                snapshotSerializer, cacheSource, eventReplayer);
    }

    @Override
    public ModelDeletionPlan planDeletion(
            @NonNull Object modelId,
            @NonNull ModelDeletionCascade cascade) {
        return client.getEventStoreClient()
                .planModelDeletion(
                        new PlanModelDeletion(
                                modelId.toString(),
                                cascade));
    }

    @Override
    public CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull Object modelId,
            @NonNull ModelDeletionCascade cascade) {
        return deleteModel(
                UUID.randomUUID().toString(),
                modelId, cascade);
    }

    @Override
    public CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull String deletionId,
            @NonNull Object modelId,
            @NonNull ModelDeletionCascade cascade) {
        if (cascade
            != ModelDeletionCascade.NONE) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "DESCENDANTS hard deletion requires a confirmed plan from planDeletion"));
        }
        String exactId =
                modelId.toString();
        return executeDeletion(
                DeleteModel.builder()
                        .deletionId(
                                deletionId)
                        .modelId(exactId)
                        .cascade(cascade)
                        .maxDepth(0)
                        .maxModels(1)
                        .build());
    }

    @Override
    public CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull ModelDeletionPlan plan) {
        return deleteModel(
                UUID.randomUUID().toString(),
                plan);
    }

    @Override
    public CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull String deletionId,
            @NonNull ModelDeletionPlan plan) {
        return executeDeletion(
                DeleteModel.builder()
                        .deletionId(deletionId)
                        .modelId(plan.getModelId())
                        .cascade(plan.getCascade())
                        .planFingerprint(
                                plan.getCascade()
                                == ModelDeletionCascade.DESCENDANTS
                                        ? plan.getFingerprint()
                                        : null)
                        .maxDepth(
                                plan.getMaxDepth())
                        .maxModels(
                                plan.getMaxModels())
                        .build());
    }

    private CompletableFuture<ModelDeletionResult>
            executeDeletion(
                    DeleteModel request) {
        return client.getEventStoreClient()
                .deleteModel(request)
                .thenApply(result -> {
                    if (result.getCascade()
                        == ModelDeletionCascade.DESCENDANTS) {
                        modelCache.clear();
                        if (modelCacheTracker != null) {
                            modelCacheTracker.forgetAll();
                        }
                    } else {
                        modelCache.remove(
                                request.getModelId());
                        if (modelCacheTracker != null) {
                            modelCacheTracker.forget(
                                    request.getModelId());
                        }
                    }
                    return result;
                });
    }

    @Override
    public CompletableFuture<ModelGraphProjectionStatus>
            registerGraphProjection(
                    @NonNull Class<?> modelType,
                    boolean rebuild) {
        ModelGraphProjectionConfiguration configuration =
                ModelGraphProjections.configuration(
                                modelType)
                        .orElseThrow(() ->
                                             new IllegalArgumentException(
                                                     modelType.getName()
                                                     + " does not enable a graph projection"));
        return client.getEventStoreClient()
                .registerModelGraphProjection(
                        new RegisterModelGraphProjection(
                                configuration,
                                rebuild));
    }

    @Override
    public ModelGraphProjectionStatus
            graphProjectionStatus(
                    @NonNull Class<?> modelType) {
        String collection =
                ModelGraphProjections.configuration(
                                modelType)
                        .orElseThrow(() ->
                                             new IllegalArgumentException(
                                                     modelType.getName()
                                                     + " does not enable a graph projection"))
                        .getCollection();
        return client.getEventStoreClient()
                .getModelGraphProjectionStatus(
                        new GetModelGraphProjectionStatus(
                                collection));
    }

    @Override
    public <T> Entity<T> load(@NonNull String modelId, @NonNull Class<T> modelType) {
        ModelEventStateBoundary handlerBoundary =
                handlerBoundary();
        if (Object.class.equals(modelType)) {
            return cast(load(
                    modelId,
                    resolveUntypedType(
                            modelId, handlerBoundary)));
        }
        ModelMetadata metadata = ModelMetadata.validate(modelType);
        Model annotation = metadata.model().orElseThrow(() -> new IllegalArgumentException(
                modelType.getName() + " is not annotated with @Model"));
        if (handlerBoundary == null
            && annotation.cached()
            && modelCacheTracker != null) {
            Entity<?> cached =
                    modelCacheTracker.current(
                            modelId, modelType);
            if (cached != null) {
                return cast(cached);
            }
        }
        if (!annotation.eventSourced()
            && handlerBoundary == null) {
            if (!annotation.cached()
                || modelCacheTracker == null) {
                return loadDocument(
                        modelId, modelType,
                        metadata, annotation);
            }
            Long readStateIndex =
                    modelCacheTracker
                            .safeDocumentBoundary();
            Entity<?> entity =
                    loadDocumentUnchecked(
                            modelId, modelType,
                            metadata, annotation);
            if (readStateIndex != null) {
                modelCache.put(modelId, entity);
                modelCacheTracker.loaded(
                        modelId, modelType,
                        readStateIndex);
            }
            return cast(entity);
        }
        requireEventReconstruction();
        ModelTargetResolver.ResolvedModel target = new ModelTargetResolver.ResolvedModel(
                modelId, modelType, ModelTargetResolver.Access.READ_ONLY,
                List.of(metadata.entityId().orElseThrow().name()));
        ModelActionContext context = loadContext(
                new ModelTargetResolver.Resolution(
                        List.of(target), List.of()),
                stateIndex(handlerBoundary),
                actionId(handlerBoundary),
                actionSubstep(handlerBoundary),
                Map.of());
        pin(handlerBoundary, context.readStateIndex());
        if (handlerBoundary == null
            && annotation.cached()
            && modelCacheTracker != null) {
            modelCacheTracker.loaded(
                    modelId, modelType,
                    context.readStateIndex());
        }
        return cast(context.entries().getFirst().entity());
    }

    @Override
    public <T> ModelGraph<T> loadGraph(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            @NonNull ModelGraph.Options options) {
        requireEventReconstruction();
        ModelMetadata.validate(rootType);
        ModelEventStateBoundary handlerBoundary =
                handlerBoundary();
        return loadGraph(
                rootId, rootType, options,
                stateIndex(handlerBoundary),
                actionId(handlerBoundary),
                actionSubstep(handlerBoundary),
                handlerBoundary);
    }

    @Override
    public <T> ModelGraph<T> loadGraphAt(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long stateIndex,
            @NonNull ModelGraph.Options options) {
        if (stateIndex < -1L) {
            throw new IllegalArgumentException(
                    "Model graph stateIndex must be at least -1");
        }
        requireEventReconstruction();
        ModelMetadata.validate(rootType);
        return loadGraph(
                rootId, rootType, options,
                stateIndex, null, null, null);
    }

    private <T> ModelGraph<T> loadGraph(
            String rootId,
            Class<T> rootType,
            ModelGraph.Options options,
            Long maxStateIndex,
            String boundaryActionId,
            Integer boundarySubstep,
            ModelEventStateBoundary handlerBoundary) {
        GetModelGraphResult graph = client.getEventStoreClient().getModelGraph(
                new GetModelGraph(
                        rootId, maxStateIndex,
                        boundaryActionId,
                        boundarySubstep,
                        options.maxDepth(), options.maxModels(),
                        0, 0L, true));
        pin(handlerBoundary, graph.getStateIndex());
        List<ModelTargetResolver.ResolvedModel> targets =
                new ArrayList<>(graph.getStreams().size());
        for (ModelEventStream stream : graph.getStreams()) {
            Class<?> modelType = graphModelType(
                    stream, rootId, rootType);
            targets.add(new ModelTargetResolver.ResolvedModel(
                    stream.getModelId(), modelType,
                    ModelTargetResolver.Access.READ_ONLY,
                    List.of(ModelMetadata.validate(modelType)
                                    .entityId().orElseThrow().name())));
        }
        ReconstructionBatch reconstructed =
                reconstructGraph(
                        targets, graph.getStateIndex(),
                        maxStateIndex == null
                        && boundaryActionId == null);
        if (reconstructed.stateIndex() != graph.getStateIndex()) {
            throw new EventSourcingException(
                    "Model graph moved from state index %d to %d during reconstruction"
                            .formatted(graph.getStateIndex(), reconstructed.stateIndex()));
        }
        return composeGraph(
                rootId, graph.getStateIndex(),
                reconstructed.entities(), graph.getEdges());
    }

    /**
     * Reconstructs independent graph streams concurrently at the already pinned graph boundary. Every batch owns its
     * reconstruction context, so cross-model historical dependencies still resolve through the normal exact-boundary
     * path. The fixed upper bound prevents a large graph from turning into an unbounded number of store requests.
     */
    private ReconstructionBatch reconstructGraph(
            List<ModelTargetResolver.ResolvedModel> targets,
            long stateIndex,
            boolean cacheAtBoundary) {
        int batchCount = Math.min(
                MAX_PARALLEL_GRAPH_RECONSTRUCTIONS,
                targets.size());
        if (batchCount < 2) {
            return new ReconstructionSession().reconstruct(
                    targets, stateIndex,
                    null, null,
                    cacheAtBoundary);
        }
        List<List<ModelTargetResolver.ResolvedModel>> batches =
                new ArrayList<>(batchCount);
        for (int i = 0; i < batchCount; i++) {
            batches.add(new ArrayList<>());
        }
        for (int i = 0; i < targets.size(); i++) {
            batches.get(i % batchCount)
                    .add(targets.get(i));
        }

        Map<String, Entity<?>> reconstructed =
                new HashMap<>(targets.size());
        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ReconstructionBatch>> futures =
                    batches.stream()
                            .map(batch ->
                                         CompletableFuture.supplyAsync(
                                                 () -> new ReconstructionSession()
                                                         .reconstruct(
                                                                 batch, stateIndex,
                                                                 null, null,
                                                                 cacheAtBoundary),
                                                 executor))
                            .toList();
            for (CompletableFuture<ReconstructionBatch> future : futures) {
                ReconstructionBatch result;
                try {
                    result = future.join();
                } catch (CompletionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new EventSourcingException(
                            "Failed to reconstruct model graph", cause);
                }
                if (result.stateIndex() != stateIndex) {
                    throw new EventSourcingException(
                            "Model graph batch moved from state index %d to %d during reconstruction"
                                    .formatted(
                                            stateIndex,
                                            result.stateIndex()));
                }
                reconstructed.putAll(
                        result.entities());
            }
        }
        LinkedHashMap<String, Entity<?>> ordered =
                new LinkedHashMap<>(targets.size());
        for (ModelTargetResolver.ResolvedModel target : targets) {
            Entity<?> entity =
                    reconstructed.get(
                            target.modelId());
            if (entity == null) {
                throw new EventSourcingException(
                        "Model graph reconstruction omitted "
                        + target.modelId());
            }
            ordered.put(
                    target.modelId(), entity);
        }
        return new ReconstructionBatch(
                stateIndex, ordered);
    }

    private Class<?> resolveUntypedType(
            String modelId,
            ModelEventStateBoundary handlerBoundary) {
        Class<?> payloadType = resolvePayloadFactoryType(
                modelId, handlerBoundary);
        return payloadType == null
                ? resolveStoredType(modelId, handlerBoundary)
                : payloadType;
    }

    private Class<?> resolvePayloadFactoryType(
            String modelId,
            ModelEventStateBoundary handlerBoundary) {
        if (client.getEventStoreClient() == null
            || serializer == null) {
            return null;
        }
        GetModelEventsResult result =
                client.getEventStoreClient().getModelEvents(
                        new GetModelEvents(
                                List.of(
                                        new ModelEventStreamRequest(
                                                modelId, -1L, 1)),
                                stateIndex(handlerBoundary),
                                actionId(handlerBoundary),
                                actionSubstep(handlerBoundary),
                                ModelEventBatchLoader.DEFAULT_SETTINGS
                                        .maxPayloadBytes()));
        pin(handlerBoundary, result.getStateIndex());
        if (result.getStreams().size() != 1) {
            return null;
        }
        ModelEventStream stream =
                result.getStreams().getFirst();
        if (!modelId.equals(stream.getModelId())
            || stream.getMemberships().isEmpty()) {
            return null;
        }
        long firstStateIndex = stream.getMemberships()
                .getFirst().getStateIndex();
        ModelEventPayload firstPayload =
                result.getPayloads().stream()
                        .filter(payload -> payload.getStateIndex()
                                == firstStateIndex)
                        .findFirst().orElse(null);
        if (firstPayload == null) {
            return null;
        }
        LinkedHashSet<Class<?>> candidates =
                new LinkedHashSet<>();
        try {
            serializer.deserializeMessages(
                            Stream.of(firstPayload.getEvent()),
                            EVENT, UnknownTypeStrategy.FAIL)
                    .map(DeserializingMessage::getPayload)
                    .forEach(payload -> payloadFactoryTarget(
                                    modelId, payload)
                            .ifPresent(candidates::add));
        } catch (RuntimeException ignored) {
            return null;
        }
        return candidates.size() == 1
                ? candidates.getFirst() : null;
    }

    private Optional<Class<?>> payloadFactoryTarget(
            String modelId, Object payload) {
        try {
            List<ModelMetadata.HandlerMethod> handlers =
                    ModelMetadata.of(payload.getClass())
                            .applyMethods();
            if (handlers.isEmpty()) {
                return Optional.empty();
            }
            return ModelTargetResolver.resolve(
                            payload, handlers)
                    .models().stream()
                    .filter(target -> target.access().writes())
                    .filter(target -> modelId.equals(
                            target.modelId()))
                    .map(ModelTargetResolver.ResolvedModel::modelType)
                    .filter(type -> ModelMetadata.of(type)
                            .isModel())
                    .findFirst();
        } catch (IllegalArgumentException
                 | IllegalStateException ignored) {
            return Optional.empty();
        }
    }

    private Class<?> resolveStoredType(
            String modelId,
            ModelEventStateBoundary handlerBoundary) {
        if (client.getEventStoreClient() == null) {
            throw new EventSourcingException(
                    "Loading an independent model by untyped ID requires model-head type metadata");
        }
        GetModelGraphResult graph = client.getEventStoreClient().getModelGraph(
                new GetModelGraph(
                        modelId, stateIndex(handlerBoundary),
                        actionId(handlerBoundary),
                        actionSubstep(handlerBoundary), 0, 1,
                        0, 0L, true));
        pin(handlerBoundary, graph.getStateIndex());
        ModelEventStream stream = graph.getStreams().getFirst();
        if (stream.getHead() == null || stream.getHead().getModelType() == null) {
            throw new EventSourcingException(
                    "Model '%s' has no stored type metadata".formatted(modelId));
        }
        return classForName(serializer.upcastType(
                stream.getHead().getModelType()));
    }

    private ModelEventStateBoundary handlerBoundary() {
        DeserializingMessage current =
                DeserializingMessage.getCurrent();
        if (current == null
            || current.getMessageType() != EVENT
               && current.getMessageType() != NOTIFICATION
            || current.getMetadata() == null
            || !current.getMetadata().containsKey(
                    ModelEventMetadata.ACTION_ID)) {
            return null;
        }
        return current.computeContextIfAbsent(
                        ModelEventStateBoundary.class,
                        message -> {
                            Object actionId = message.getMetadata().get(
                                    ModelEventMetadata.ACTION_ID);
                            Object substep = message.getMetadata().get(
                                    ModelEventMetadata.SUBSTEP);
                            if (!(actionId instanceof String id)
                                || id.isBlank()
                                || substep == null) {
                                throw new EventSourcingException(
                                        "Published model event has no valid action boundary metadata");
                            }
                            return new ModelEventStateBoundary(
                                    id, parseSubstep(substep));
                        });
    }

    private static int parseSubstep(Object value) {
        int result;
        if (value instanceof Number number) {
            result = number.intValue();
        } else {
            try {
                result = Integer.parseInt(value.toString());
            } catch (NumberFormatException failure) {
                throw new EventSourcingException(
                        "Published model event has an invalid action substep",
                        failure);
            }
        }
        if (result < 0) {
            throw new EventSourcingException(
                    "Published model event has a negative action substep");
        }
        return result;
    }

    private static Long stateIndex(
            ModelEventStateBoundary boundary) {
        return boundary == null
                ? null : boundary.stateIndex();
    }

    private static String actionId(
            ModelEventStateBoundary boundary) {
        return boundary == null
                ? null : boundary.actionId();
    }

    private static Integer actionSubstep(
            ModelEventStateBoundary boundary) {
        return boundary == null
                ? null : boundary.substep();
    }

    private static void pin(
            ModelEventStateBoundary boundary,
            long stateIndex) {
        if (boundary != null) {
            boundary.pin(stateIndex);
        }
    }

    private Class<?> graphModelType(
            ModelEventStream stream, String rootId, Class<?> rootType) {
        ModelHeadState head = stream.getHead();
        String storedType = head == null ? null : head.getModelType();
        if (storedType == null) {
            if (stream.getModelId().equals(rootId)) {
                return rootType;
            }
            throw new EventSourcingException(
                    "Graph child '%s' has no stored model type"
                            .formatted(stream.getModelId()));
        }
        Class<?> result;
        try {
            result = classForName(serializer.upcastType(storedType));
        } catch (Throwable failure) {
            throw new EventSourcingException(
                    "Could not resolve stored model type '%s' for %s"
                            .formatted(storedType, stream.getModelId()),
                    failure);
        }
        if (stream.getModelId().equals(rootId)
            && !rootType.isAssignableFrom(result)) {
            throw new EventSourcingException(
                    "Graph root '%s' has stored type %s instead of %s"
                            .formatted(rootId, result.getName(), rootType.getName()));
        }
        ModelMetadata.validate(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> ModelGraph<T> composeGraph(
            String rootId,
            long stateIndex,
            Map<String, Entity<?>> models,
            List<ModelGraphEdge> edges) {
        GraphComposer composer = new GraphComposer(models, edges);
        ModelGraph.Node<T> root =
                (ModelGraph.Node<T>) composer.node(rootId);
        return new ModelGraph<>(
                stateIndex, root,
                Collections.unmodifiableMap(new LinkedHashMap<>(models)),
                edges);
    }

    private static final class GraphComposer {
        private final Map<String, Entity<?>> models;
        private final Map<String, List<ModelGraphEdge>> edgesByParent =
                new LinkedHashMap<>();
        private final Map<String, ModelGraph.Node<?>> nodes =
                new LinkedHashMap<>();
        private final LinkedHashSet<String> visiting = new LinkedHashSet<>();

        private GraphComposer(
                Map<String, Entity<?>> models,
                List<ModelGraphEdge> edges) {
            this.models = models;
            for (ModelGraphEdge edge : edges) {
                if (edge.getPath() == null) {
                    continue;
                }
                edgesByParent.computeIfAbsent(
                        edge.getParentId(), ignored -> new ArrayList<>()).add(edge);
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private ModelGraph.Node<?> node(String modelId) {
            ModelGraph.Node<?> known = nodes.get(modelId);
            if (known != null) {
                return known;
            }
            Entity<?> model = models.get(modelId);
            if (model == null) {
                throw new EventSourcingException(
                        "Model graph contains edge to unloaded node " + modelId);
            }
            if (!visiting.add(modelId)) {
                throw new EventSourcingException(
                        "Model graph contains a cycle through " + modelId);
            }
            LinkedHashMap<String, List<ModelGraph.Node<?>>> children =
                    new LinkedHashMap<>();
            for (ModelGraphEdge edge :
                    edgesByParent.getOrDefault(modelId, List.of())) {
                children.computeIfAbsent(
                                edge.getPath(), ignored -> new ArrayList<>())
                        .add(node(edge.getChildId()));
            }
            visiting.remove(modelId);
            LinkedHashMap<String, List<ModelGraph.Node<?>>> immutable =
                    new LinkedHashMap<>();
            children.forEach((path, values) ->
                                     immutable.put(path, List.copyOf(values)));
            ModelGraph.Node<?> result = new ModelGraph.Node(
                    model, Collections.unmodifiableMap(immutable));
            nodes.put(modelId, result);
            return result;
        }
    }

    /**
     * Loads all direct action targets at one state boundary.
     * <p>
     * A {@code null} boundary pins the current event-store state once. Historical document-model dependencies are
     * reconstructed from stored model events; current document-model targets retain their direct-document load path.
     */
    @Override
    public ModelActionContext loadContext(
            ModelTargetResolver.Resolution resolution) {
        ModelEventStateBoundary handlerBoundary =
                handlerBoundary();
        ModelActionContext context = loadContext(
                resolution,
                stateIndex(handlerBoundary),
                actionId(handlerBoundary),
                actionSubstep(handlerBoundary),
                Map.of());
        pin(handlerBoundary, context.readStateIndex());
        return context;
    }

    /**
     * Loads all direct action targets at one explicit state boundary.
     * <p>
     * A {@code null} boundary pins the current event-store state once. Historical document-model dependencies are
     * reconstructed from stored model events; current document-model targets retain their direct-document load path.
     */
    public ModelActionContext loadContext(
            ModelTargetResolver.Resolution resolution, Long maxStateIndex) {
        return loadContext(
                resolution, maxStateIndex,
                null, null, Map.of());
    }

    /**
     * Loads an action context and overlays relationships declared by model values staged in earlier substeps.
     */
    public ModelActionContext loadContext(
            ModelTargetResolver.Resolution resolution,
            Long maxStateIndex,
            Map<String, Object> stagedValues) {
        return loadContext(
                resolution, maxStateIndex,
                null, null, stagedValues);
    }

    private ModelActionContext loadContext(
            ModelTargetResolver.Resolution resolution,
            Long maxStateIndex,
            String boundaryActionId,
            Integer boundarySubstep,
            Map<String, Object> stagedValues) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(stagedValues, "stagedValues");
        requireEventReconstruction();
        boolean historicalBoundary =
                maxStateIndex != null || boundaryActionId != null;
        Long ancestorStateIndex = null;
        if (resolution.hasAncestorDependencies()) {
            AncestorResolution ancestors = resolveAncestors(
                    resolution, maxStateIndex,
                    boundaryActionId, boundarySubstep,
                    stagedValues);
            resolution = ancestors.resolution();
            ancestorStateIndex = ancestors.stateIndex();
            maxStateIndex = ancestorStateIndex;
            boundaryActionId = null;
            boundarySubstep = null;
        }
        ReconstructionSession session = new ReconstructionSession();
        List<ModelTargetResolver.ResolvedModel> eventTargets = new ArrayList<>();
        List<ModelTargetResolver.ResolvedModel> documentTargets = new ArrayList<>();
        for (ModelTargetResolver.ResolvedModel target : resolution.models()) {
            Model model = ModelMetadata.validate(target.modelType()).model().orElseThrow();
            if (model.eventSourced() || historicalBoundary) {
                eventTargets.add(target);
            } else {
                documentTargets.add(target);
            }
        }

        Map<String, Entity<?>> loaded = new LinkedHashMap<>();
        long stateIndex;
        if (eventTargets.isEmpty()) {
            stateIndex = ancestorStateIndex == null
                    ? eventLoader.load(
                            Map.of(), maxStateIndex,
                            boundaryActionId, boundarySubstep,
                            ignored -> {
                            }).stateIndex()
                    : ancestorStateIndex;
        } else {
            ReconstructionBatch batch = session.reconstruct(
                    eventTargets, maxStateIndex,
                    boundaryActionId, boundarySubstep);
            stateIndex = batch.stateIndex();
            loaded.putAll(batch.entities());
        }
        boolean writesEventSourcedModel =
                resolution.models().stream().anyMatch(
                        target -> target.access().writes()
                                  && ModelMetadata.validate(
                                          target.modelType())
                                          .model().orElseThrow()
                                          .eventSourced());
        List<String> documentDependencies =
                documentTargets.stream()
                        .filter(target -> target.access().reads())
                        .map(ModelTargetResolver.ResolvedModel::modelId)
                        .toList();
        if (writesEventSourcedModel
            && !documentDependencies.isEmpty()) {
            eventLoader.loadHeads(
                    documentDependencies,
                    stateIndex, null, null);
        }
        Long documentCacheBoundary =
                !historicalBoundary
                && modelCacheTracker != null
                && documentTargets.stream()
                        .anyMatch(target ->
                                          ModelMetadata.validate(
                                                          target.modelType())
                                                  .model().orElseThrow()
                                                  .cached())
                        ? modelCacheTracker
                                .safeDocumentBoundary()
                        : null;
        for (ModelTargetResolver.ResolvedModel target : documentTargets) {
            ModelMetadata metadata = ModelMetadata.validate(target.modelType());
            Model model = metadata.model().orElseThrow();
            Entity<?> entity = loadDocumentUnchecked(
                    target.modelId(), target.modelType(), metadata, model);
            loaded.put(target.modelId(), entity);
            if (!historicalBoundary
                && model.cached()
                && documentCacheBoundary != null) {
                modelCache.put(
                        target.modelId(), entity);
            }
        }
        if (!historicalBoundary
            && modelCacheTracker != null) {
            for (ModelTargetResolver.ResolvedModel target :
                    resolution.models()) {
                Model model =
                        ModelMetadata.validate(
                                        target.modelType())
                                .model().orElseThrow();
                if (!model.cached()) {
                    continue;
                }
                if (model.eventSourced()) {
                    modelCacheTracker.loaded(
                            target.modelId(),
                            target.modelType(),
                            stateIndex);
                } else if (documentCacheBoundary
                           != null) {
                    modelCacheTracker.loaded(
                            target.modelId(),
                            target.modelType(),
                            documentCacheBoundary);
                }
            }
        }
        return ModelActionContext.create(stateIndex, resolution, loaded);
    }

    private AncestorResolution resolveAncestors(
            ModelTargetResolver.Resolution resolution,
            Long maxStateIndex,
            String boundaryActionId,
            Integer boundarySubstep,
            Map<String, Object> stagedValues) {
        if (resolution.models().isEmpty()) {
            throw new IllegalStateException(
                    "Ancestor injection requires at least one direct model target from which to traverse");
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        resolution.models().forEach(target -> roots.add(target.modelId()));
        LinkedHashSet<String> requestRoots = new LinkedHashSet<>(roots);
        Map<String, Class<?>> stagedTypes = new LinkedHashMap<>();
        List<ModelGraphEdge> stagedEdges = new ArrayList<>();
        for (Map.Entry<String, Object> entry : stagedValues.entrySet()) {
            requestRoots.add(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            ModelMetadata metadata = ModelMetadata.validate(value.getClass());
            stagedTypes.put(entry.getKey(), value.getClass());
            for (ModelMetadata.ParentReference parent : metadata.parentReferences()) {
                Object parentId = parent.read(value);
                if (parentId == null) {
                    continue;
                }
                String parentIdString = Objects.requireNonNull(
                        parentId.toString(),
                        () -> "@ParentId " + parent.property().name()
                              + " returned a null ID string");
                requestRoots.add(parentIdString);
                stagedEdges.add(new ModelGraphEdge(
                        entry.getKey(), parentIdString,
                        parent.parentModelType() == null
                                ? null : parent.parentModelType().getName(),
                        parent.path().isEmpty() ? null : parent.path(),
                        -1L, null));
            }
        }
        if (requestRoots.size() > ACTION_ANCESTOR_MAX_MODELS) {
            throw new IllegalStateException(
                    "Model action requires more than %d ancestor traversal roots"
                            .formatted(ACTION_ANCESTOR_MAX_MODELS));
        }

        GetModelGraphResult graph = client.getEventStoreClient().getModelAncestors(
                new GetModelAncestors(
                        List.copyOf(requestRoots),
                        maxStateIndex,
                        boundaryActionId,
                        boundarySubstep,
                        ACTION_ANCESTOR_MAX_DEPTH,
                        ACTION_ANCESTOR_MAX_MODELS,
                        0, 0L));
        List<ModelGraphEdge> edges = new ArrayList<>(graph.getEdges());
        if (!stagedValues.isEmpty()) {
            edges.removeIf(edge -> stagedValues.containsKey(edge.getChildId()));
            edges.addAll(stagedEdges);
        }
        GraphReachability reachable = reachableAncestors(
                roots, edges, ACTION_ANCESTOR_MAX_DEPTH,
                ACTION_ANCESTOR_MAX_MODELS);

        Map<String, ModelHeadState> heads = new LinkedHashMap<>();
        graph.getStreams().forEach(stream ->
                                           heads.put(stream.getModelId(),
                                                     stream.getHead()));
        Map<String, Class<?>> knownTypes = new LinkedHashMap<>();
        resolution.models().forEach(target ->
                                            knownTypes.put(
                                                    target.modelId(),
                                                    target.modelType()));
        knownTypes.putAll(stagedTypes);
        for (String modelId : reachable.ancestorIds()) {
            Class<?> storedType = resolveAncestorType(
                    modelId, heads.get(modelId),
                    reachable.incoming().getOrDefault(modelId, List.of()));
            if (storedType != null) {
                knownTypes.merge(
                        modelId, storedType,
                        (left, right) -> compatible(left, right)
                                ? left.isAssignableFrom(right) ? right : left
                                : incompatibleStoredTypes(modelId, left, right));
            }
        }

        LinkedHashMap<String, ModelTargetResolver.ResolvedModel> selected =
                new LinkedHashMap<>();
        resolution.models().forEach(target ->
                                            selected.put(
                                                    target.modelId(), target));
        for (ModelTargetResolver.AncestorDependency dependency :
                resolution.ancestorDependencies()) {
            List<String> candidates = reachable.ancestorIds().stream()
                    .filter(modelId -> {
                        Class<?> actualType = knownTypes.get(modelId);
                        return actualType == null
                               || compatible(dependency.modelType(), actualType);
                    })
                    .filter(modelId -> dependency.association() == null
                                       || reachable.incoming()
                                               .getOrDefault(modelId, List.of())
                                               .stream()
                                               .anyMatch(edge -> dependency.association()
                                                       .equals(edge.getPath())))
                    .toList();
            if (candidates.isEmpty()) {
                throw new IllegalStateException(
                        "No reachable ancestor of type %s%s was found for %s from model roots %s"
                                .formatted(
                                        dependency.modelType().getName(),
                                        dependency.association() == null
                                                ? ""
                                                : " at @ParentId path '"
                                                  + dependency.association() + "'",
                                        dependency.handler(), roots));
            }
            if (candidates.size() > 1) {
                throw new IllegalStateException(
                        "Multiple reachable ancestors of type %s%s were found for %s: %s. "
                                .formatted(
                                        dependency.modelType().getName(),
                                        dependency.association() == null
                                                ? ""
                                                : " at @ParentId path '"
                                                  + dependency.association() + "'",
                                        dependency.handler(), candidates)
                        + "Qualify the parameter with @Association(\"parentPath\").");
            }
            String modelId = candidates.getFirst();
            Class<?> modelType = knownTypes.get(modelId);
            if (modelType == null) {
                modelType = dependency.modelType();
            }
            ModelMetadata.validate(modelType);
            String sourceProperty = dependency.association() == null
                    ? ModelMetadata.validate(modelType)
                            .entityId().orElseThrow().name()
                    : dependency.association();
            mergeResolvedTarget(
                    selected,
                    new ModelTargetResolver.ResolvedModel(
                            modelId, modelType,
                            ModelTargetResolver.Access.READ_ONLY,
                            List.of(sourceProperty)));
        }
        return new AncestorResolution(
                graph.getStateIndex(),
                resolution.withResolvedModels(
                        List.copyOf(selected.values())));
    }

    private GraphReachability reachableAncestors(
            LinkedHashSet<String> roots,
            List<ModelGraphEdge> edges,
            int maxDepth,
            int maxModels) {
        LinkedHashMap<String, List<ModelGraphEdge>> outgoing =
                new LinkedHashMap<>();
        edges.forEach(edge -> outgoing.computeIfAbsent(
                edge.getChildId(), ignored -> new ArrayList<>()).add(edge));
        LinkedHashSet<String> visited = new LinkedHashSet<>(roots);
        LinkedHashSet<String> ancestors = new LinkedHashSet<>();
        LinkedHashMap<String, List<ModelGraphEdge>> incoming =
                new LinkedHashMap<>();
        LinkedHashMap<String, List<ModelGraphEdge>> reachableOutgoing =
                new LinkedHashMap<>();
        List<String> frontier = List.copyOf(roots);
        for (int depth = 0; !frontier.isEmpty(); depth++) {
            if (depth >= maxDepth) {
                boolean hasMore = frontier.stream()
                        .anyMatch(id -> !outgoing
                                .getOrDefault(id, List.of()).isEmpty());
                if (hasMore) {
                    throw new IllegalStateException(
                            "Model ancestor graph exceeds maximum depth "
                            + maxDepth);
                }
                break;
            }
            List<String> next = new ArrayList<>();
            for (String child : frontier) {
                for (ModelGraphEdge edge :
                        outgoing.getOrDefault(child, List.of())) {
                    String parent = edge.getParentId();
                    reachableOutgoing.computeIfAbsent(
                            child, ignored -> new ArrayList<>())
                            .add(edge);
                    incoming.computeIfAbsent(
                            parent, ignored -> new ArrayList<>()).add(edge);
                    ancestors.add(parent);
                    if (visited.add(parent)) {
                        if (visited.size() > maxModels) {
                            throw new IllegalStateException(
                                    "Model ancestor graph exceeds maxModels "
                                    + maxModels);
                        }
                        next.add(parent);
                    }
                }
            }
            frontier = next;
        }
        assertAcyclic(roots, reachableOutgoing);
        return new GraphReachability(
                List.copyOf(ancestors),
                Collections.unmodifiableMap(incoming));
    }

    private void assertAcyclic(
            Iterable<String> roots,
            Map<String, List<ModelGraphEdge>> outgoing,
            Map<String, Boolean> completed) {
        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        for (String root : roots) {
            assertAcyclic(root, outgoing, visiting, completed);
        }
    }

    private void assertAcyclic(
            Iterable<String> roots,
            Map<String, List<ModelGraphEdge>> outgoing) {
        assertAcyclic(roots, outgoing, new HashMap<>());
    }

    private void assertAcyclic(
            String modelId,
            Map<String, List<ModelGraphEdge>> outgoing,
            LinkedHashSet<String> visiting,
            Map<String, Boolean> completed) {
        if (completed.containsKey(modelId)) {
            return;
        }
        if (!visiting.add(modelId)) {
            throw new EventSourcingException(
                    "Model ancestor graph contains a cycle through "
                    + modelId);
        }
        for (ModelGraphEdge edge :
                outgoing.getOrDefault(modelId, List.of())) {
            assertAcyclic(
                    edge.getParentId(), outgoing,
                    visiting, completed);
        }
        visiting.remove(modelId);
        completed.put(modelId, Boolean.TRUE);
    }

    private Class<?> resolveAncestorType(
            String modelId,
            ModelHeadState head,
            List<ModelGraphEdge> incoming) {
        LinkedHashSet<String> storedTypes = new LinkedHashSet<>();
        if (head != null && head.getModelType() != null) {
            storedTypes.add(head.getModelType());
        }
        incoming.stream().map(ModelGraphEdge::getParentType)
                .filter(Objects::nonNull).forEach(storedTypes::add);
        Class<?> result = null;
        for (String storedType : storedTypes) {
            Class<?> candidate;
            try {
                candidate = classForName(serializer.upcastType(storedType));
            } catch (Throwable failure) {
                throw new EventSourcingException(
                        "Could not resolve stored model type '%s' for ancestor %s"
                                .formatted(storedType, modelId),
                        failure);
            }
            ModelMetadata.validate(candidate);
            result = result == null
                    ? candidate
                    : compatible(result, candidate)
                            ? result.isAssignableFrom(candidate)
                                    ? candidate : result
                            : incompatibleStoredTypes(
                                    modelId, result, candidate);
        }
        return result;
    }

    private static Class<?> incompatibleStoredTypes(
            String modelId, Class<?> left, Class<?> right) {
        throw new EventSourcingException(
                "Model ancestor '%s' is described by incompatible types %s and %s"
                        .formatted(
                                modelId, left.getName(),
                                right.getName()));
    }

    private static void mergeResolvedTarget(
            LinkedHashMap<String, ModelTargetResolver.ResolvedModel> targets,
            ModelTargetResolver.ResolvedModel addition) {
        ModelTargetResolver.ResolvedModel current =
                targets.get(addition.modelId());
        if (current == null) {
            targets.put(addition.modelId(), addition);
            return;
        }
        if (!compatible(current.modelType(), addition.modelType())) {
            throw new IllegalStateException(
                    "Model ID '%s' is requested as incompatible types %s and %s"
                            .formatted(
                                    addition.modelId(),
                                    current.modelType().getName(),
                                    addition.modelType().getName()));
        }
        Class<?> modelType = current.modelType()
                .isAssignableFrom(addition.modelType())
                ? addition.modelType() : current.modelType();
        ModelTargetResolver.Access access =
                current.access().writes()
                        ? current.access().reads()
                                || addition.access().reads()
                                ? ModelTargetResolver.Access.READ_WRITE
                                : ModelTargetResolver.Access.WRITE_ONLY
                        : addition.access().writes()
                                ? addition.access().reads()
                                        || current.access().reads()
                                        ? ModelTargetResolver.Access.READ_WRITE
                                        : ModelTargetResolver.Access.WRITE_ONLY
                                : ModelTargetResolver.Access.READ_ONLY;
        LinkedHashSet<String> sourceProperties =
                new LinkedHashSet<>(current.sourceProperties());
        sourceProperties.addAll(addition.sourceProperties());
        targets.put(
                addition.modelId(),
                new ModelTargetResolver.ResolvedModel(
                        addition.modelId(), modelType, access,
                        List.copyOf(sourceProperties)));
    }

    /**
     * Makes accepted local model transitions immediately visible through this repository and stores any due snapshots.
     */
    public CompletableFuture<Void> updateAfterCommit(
            List<CommittedModel> committedModels) {
        List<CompletableFuture<Void>> snapshots = new ArrayList<>();
        for (CommittedModel committed : committedModels) {
            ModelMetadata metadata = ModelMetadata.validate(committed.modelType());
            Model model = metadata.model().orElseThrow();
            if (!committed.historyComplete()) {
                modelCache.<Entity<?>>compute(
                        committed.modelId(),
                        (ignored, current) ->
                                current != null
                                && stateIndex(current)
                                   > committed.stateIndex()
                                        ? current : null);
                if (modelCacheTracker != null) {
                    modelCacheTracker.forget(
                            committed.modelId());
                }
                continue;
            }
            AtomicReference<Entity<?>> accepted =
                    new AtomicReference<>();
            Entity<?> entity;
            if (model.cached()) {
                entity = modelCache.compute(
                        committed.modelId(),
                        (ignored, current) -> {
                            if (current != null
                                && stateIndex(current)
                                   >= committed.stateIndex()) {
                                return current;
                            }
                            Entity<?> updated =
                                    committedEntity(
                                            committed, metadata,
                                            model, current);
                            accepted.set(updated);
                            return updated;
                        });
            } else {
                modelCache.remove(committed.modelId());
                entity = committedEntity(
                        committed, metadata, model,
                        null);
                accepted.set(entity);
            }
            if (accepted.get() != null
                && committed.snapshotDue()
                && model.eventSourced()
                && model.snapshotPeriod() > 0
                && snapshotStore != null
                && entity.isPresent()) {
                snapshots.add(snapshotStore.storeSnapshot(
                        committed.modelId(), entity.get(),
                        committed.sequenceNumber(), committed.stateIndex(),
                        entity.timestamp(), model.maxSnapshotCount()));
            }
            if (model.cached()
                && modelCacheTracker != null) {
                modelCacheTracker.committed(
                        committed.modelId(),
                        committed.modelType(),
                        committed.stateIndex());
            }
        }
        return snapshots.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(
                        snapshots.toArray(CompletableFuture[]::new));
    }

    /**
     * Removes action-scoped entries before a strict-policy retry reload.
     */
    public void invalidateModels(Iterable<String> modelIds) {
        modelIds.forEach(modelId -> {
            modelCache.remove(modelId);
            if (modelCacheTracker != null) {
                modelCacheTracker.forget(modelId);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Entity<?> committedEntity(
            CommittedModel committed,
            ModelMetadata metadata,
            Model model,
            Entity<?> previous) {
        Entity<?> result = previous;
        for (CommittedRevision revision : committed.revisions()) {
            validateValueId(
                    committed.modelId(), metadata, revision.value());
            result = ImmutableModelRoot.<Object>builder()
                    .id(committed.modelId())
                    .type((Class<Object>) committed.modelType())
                    .idProperty(metadata.entityId().orElseThrow().name())
                    .value(revision.value())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(revision.sequenceNumber())
                    .stateIndex(revision.stateIndex())
                    .lastEventId(revision.lastEventId())
                    .lastEventIndex(revision.lastEventIndex())
                    .timestamp(revision.timestamp())
                    .previous(castPrevious(retainPrevious(
                            result, model)))
                    .build();
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Committed model has no revisions: "
                    + committed.modelId());
        }
        return result;
    }

    private Entity<?> retainPrevious(
            Entity<?> previous, Model model) {
        if (previous == null || !model.cached()
            || !model.eventSourced() || model.cachingDepth() == 0) {
            return null;
        }
        if (model.cachingDepth() < 0) {
            return previous;
        }
        return truncatePrevious(previous, model.cachingDepth() - 1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity<?> truncatePrevious(
            Entity<?> revision, int remainingDepth) {
        if (!(revision instanceof ImmutableModelRoot root)) {
            return revision;
        }
        Entity<?> previous = remainingDepth <= 0
                ? null
                : truncatePrevious(
                        root.previous(), remainingDepth - 1);
        return root.withPrevious((Entity) previous);
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> castPrevious(Entity<?> entity) {
        return (Entity<T>) entity;
    }

    private void requireEventReconstruction() {
        if (serializer == null || entityHelper == null || eventReplayer == null || eventLoader == null) {
            throw new EventSourcingException(
                    "Event-sourced model reconstruction requires a configured serializer and model entity helper");
        }
    }

    private <T> Entity<T> loadDocument(
            String modelId, Class<T> modelType, ModelMetadata metadata, Model annotation) {
        return cast(loadDocumentUnchecked(modelId, modelType, metadata, annotation));
    }

    @SuppressWarnings("unchecked")
    private Entity<?> loadDocumentUnchecked(
            String modelId, Class<?> modelType, ModelMetadata metadata, Model annotation) {
        String collection = Optional.of(annotation.collection())
                .filter(value -> !value.isEmpty())
                .map(ApplicationProperties::substituteProperties)
                .orElse(modelType.getSimpleName());
        Object value = documentStore.fetchDocument(modelId, collection, modelType).orElse(null);
        String idProperty = metadata.entityId().orElseThrow().name();
        validateValueId(modelId, metadata, value);
        return ImmutableModelRoot.<Object>builder()
                .id(modelId)
                .type((Class<Object>) modelType)
                .idProperty(idProperty)
                .value(value)
                .entityHelper(entityHelper)
                .serializer(serializer)
                .build();
    }

    /**
     * Advances stale cached models in one bounded store round trip for event-sourced targets and direct document
     * reads for document-based targets.
     */
    private ModelCacheTracker.RefreshedBatch
            refreshCurrentModels(
                    Map<String, Class<?>> targets,
                    long safeStateIndex) {
        List<ModelTargetResolver.ResolvedModel>
                eventTargets = new ArrayList<>();
        List<ModelTargetResolver.ResolvedModel>
                documentTargets = new ArrayList<>();
        targets.forEach((modelId, modelType) -> {
            ModelMetadata metadata =
                    ModelMetadata.validate(modelType);
            Model model =
                    metadata.model().orElseThrow();
            ModelTargetResolver.ResolvedModel target =
                    new ModelTargetResolver.ResolvedModel(
                            modelId, modelType,
                            ModelTargetResolver.Access.READ_ONLY,
                            List.of(
                                    metadata.entityId()
                                            .orElseThrow()
                                            .name()));
            if (model.eventSourced()) {
                eventTargets.add(target);
            } else {
                documentTargets.add(target);
            }
        });
        if (!eventTargets.isEmpty()) {
            long reconstructedStateIndex =
                    new ReconstructionSession()
                            .reconstruct(
                                    eventTargets,
                                    null)
                            .stateIndex();
            if (reconstructedStateIndex
                < safeStateIndex) {
                throw new EventSourcingException(
                        "Model reconstruction stopped at state index %d before safe cache boundary %d"
                                .formatted(
                                        reconstructedStateIndex,
                                        safeStateIndex));
            }
        }
        for (ModelTargetResolver.ResolvedModel target :
                documentTargets) {
            ModelMetadata metadata =
                    ModelMetadata.validate(
                            target.modelType());
            Model model =
                    metadata.model().orElseThrow();
            modelCache.put(
                    target.modelId(),
                    loadDocumentUnchecked(
                            target.modelId(),
                            target.modelType(),
                            metadata, model));
        }
        return new ModelCacheTracker.RefreshedBatch(
                safeStateIndex);
    }

    private static void validateValueId(
            String modelId, ModelMetadata metadata, Object value) {
        if (value == null) {
            return;
        }
        Object storedId = metadata.entityId().orElseThrow().read(value);
        if (storedId == null || !Objects.equals(modelId, storedId.toString())) {
            throw new EventSourcingException(
                    "Stored model document '%s' reports @EntityId '%s'"
                            .formatted(modelId, storedId));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> cast(Entity<?> entity) {
        return (Entity<T>) entity;
    }

    private final class ReconstructionSession {
        private final Map<ViewKey, Entity<?>> reconstructed =
                new LinkedHashMap<>(128, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<ViewKey, Entity<?>> eldest) {
                        return size() > 1_024;
                    }
                };
        private final Map<ModelKey, TreeMap<Long, Entity<?>>> checkpoints =
                new HashMap<>();
        private final Map<PayloadKey, List<Message>> deserializedEvents =
                new LinkedHashMap<>(128, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<PayloadKey, List<Message>> eldest) {
                        return size() > 1_024;
                    }
                };
        private final Map<HandlerKey, List<ModelMetadata.HandlerMethod>> replayHandlers =
                new ConcurrentHashMap<>();
        private final Map<ReplayAncestorKey, ModelTargetResolver.Resolution>
                replayAncestorResolutions =
                new LinkedHashMap<>(128, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<ReplayAncestorKey,
                                    ModelTargetResolver.Resolution> eldest) {
                        return size() > 1_024;
                    }
                };

        ReconstructionBatch reconstruct(
                List<ModelTargetResolver.ResolvedModel> targets, Long maxStateIndex) {
            return reconstruct(
                    targets, maxStateIndex,
                    null, null,
                    maxStateIndex == null);
        }

        ReconstructionBatch reconstruct(
                List<ModelTargetResolver.ResolvedModel> targets,
                Long maxStateIndex,
                String boundaryActionId,
                Integer boundarySubstep) {
            return reconstruct(
                    targets, maxStateIndex,
                    boundaryActionId, boundarySubstep,
                    maxStateIndex == null
                    && boundaryActionId == null);
        }

        ReconstructionBatch reconstruct(
                List<ModelTargetResolver.ResolvedModel> targets,
                Long maxStateIndex,
                String boundaryActionId,
                Integer boundarySubstep,
                boolean cacheAtBoundary) {
            if (targets.isEmpty()) {
                long boundary = eventLoader.load(
                        Map.of(), maxStateIndex,
                        boundaryActionId, boundarySubstep,
                        ignored -> {
                        }).stateIndex();
                return new ReconstructionBatch(boundary, Map.of());
            }
            LinkedHashMap<String, MutableReconstruction> states =
                    new LinkedHashMap<>();
            LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                Entity<?> base = reconstructionBase(
                        target, maxStateIndex,
                        boundaryActionId == null);
                states.put(
                        target.modelId(),
                        new MutableReconstruction(target, base));
                cursors.put(
                        target.modelId(),
                        base == null ? -1L : base.sequenceNumber());
            }
            ModelEventBatchLoader.LoadResult loaded = eventLoader.load(
                    cursors, maxStateIndex,
                    boundaryActionId, boundarySubstep,
                    page -> applyPage(page, states));
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                ModelHeadState head = loaded.heads().get(target.modelId());
                MutableReconstruction state = states.get(target.modelId());
                Entity<?> entity;
                if (head == null) {
                    modelCache.remove(
                            target.modelId());
                    entity = empty(target);
                } else {
                    entity = withHead(
                            state.current, head);
                }
                validateReconstruction(target, head, entity);
                result.put(target.modelId(), entity);
                if (cacheAtBoundary
                    && ModelMetadata.of(target.modelType()).model().orElseThrow().cached()
                    && (head == null || head.isHistoryComplete())) {
                    modelCache.put(target.modelId(), entity);
                }
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), loaded.stateIndex(),
                        null, Integer.MAX_VALUE, loaded.stateIndex()), entity);
            }
            return new ReconstructionBatch(loaded.stateIndex(), result);
        }

        private Entity<?> reconstructionBase(
                ModelTargetResolver.ResolvedModel target,
                Long maxStateIndex,
                boolean allowCurrentCache) {
            Model model = ModelMetadata.of(target.modelType()).model().orElseThrow();
            if (allowCurrentCache
                && model.cached()) {
                Entity<?> cached =
                        modelCache.get(
                                target.modelId());
                if (cached != null
                    && (maxStateIndex == null
                        || stateIndex(cached)
                           <= maxStateIndex)) {
                    if (!target.modelType()
                            .equals(cached.type())) {
                        modelCache.remove(
                                target.modelId());
                        throw new EventSourcingException(
                                "Cached model '%s' has type %s instead of %s"
                                        .formatted(
                                                target.modelId(),
                                                cached.type()
                                                        .getName(),
                                                target.modelType()
                                                        .getName()));
                    }
                    return cached;
                }
            }
            Entity<?> result = null;
            if (model.snapshotPeriod() > 0 && snapshotStore != null
                && (maxStateIndex != null || allowCurrentCache)) {
                result = snapshotStore.getSnapshot(
                                target.modelId(), maxStateIndex)
                        .map(snapshot -> fromSnapshot(target, snapshot))
                        .orElse(null);
            }
            if (maxStateIndex != null) {
                TreeMap<Long, Entity<?>> known = checkpoints.get(
                        new ModelKey(target.modelId(), target.modelType()));
                if (known != null) {
                    Map.Entry<Long, Entity<?>> floor =
                            known.floorEntry(maxStateIndex);
                    if (floor != null
                        && (result == null
                            || stateIndex(result) < floor.getKey())) {
                        result = floor.getValue();
                    }
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private Entity<?> fromSnapshot(
                ModelTargetResolver.ResolvedModel target,
                ModelSnapshotStore.Snapshot snapshot) {
            if (snapshot.value() == null
                || !target.modelType().isInstance(snapshot.value())) {
                throw new EventSourcingException(
                        "Snapshot for model '%s' contains %s instead of %s"
                                .formatted(
                                        target.modelId(),
                                        snapshot.value() == null
                                                ? "null" : snapshot.value().getClass().getName(),
                                        target.modelType().getName()));
            }
            validateValueId(
                    target.modelId(), ModelMetadata.of(target.modelType()),
                    snapshot.value());
            return ImmutableModelRoot.<Object>builder()
                    .id(target.modelId())
                    .type((Class<Object>) target.modelType())
                    .idProperty(ModelMetadata.of(target.modelType())
                                        .entityId().orElseThrow().name())
                    .value(snapshot.value())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(snapshot.sequenceNumber())
                    .stateIndex(snapshot.stateIndex())
                    .timestamp(snapshot.timestamp())
                    .build();
        }

        private void applyPage(
                GetModelEventsResult page,
                Map<String, MutableReconstruction> states) {
            Map<Long, io.fluxzero.common.api.SerializedMessage> payloads = new HashMap<>();
            for (ModelEventPayload payload : page.getPayloads()) {
                payloads.put(payload.getStateIndex(), payload.getEvent());
            }
            for (ModelEventStream stream : page.getStreams()) {
                MutableReconstruction state = states.get(stream.getModelId());
                if (state == null) {
                    throw new EventSourcingException(
                            "Model event store returned unrelated stream " + stream.getModelId());
                }
                if (stream.getHead() != null
                    && !stream.getHead().isHistoryComplete()) {
                    throw incompleteHistory(stream.getModelId());
                }
                for (ModelEventMembership membership : stream.getMemberships()) {
                    state.apply(new StoredEvent(
                            membership,
                            Objects.requireNonNull(
                                    payloads.get(membership.getStateIndex()),
                                    "Missing validated model payload")));
                }
            }
        }

        private Entity<?> reconstructAt(
                ModelTargetResolver.ResolvedModel target, long stateIndex) {
            return reconstructAt(List.of(target), stateIndex)
                    .get(target.modelId());
        }

        private Map<String, Entity<?>> reconstructAt(
                List<ModelTargetResolver.ResolvedModel> targets,
                long stateIndex) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            List<ModelTargetResolver.ResolvedModel> missing =
                    new ArrayList<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                ViewKey viewKey = new ViewKey(
                        target.modelId(), target.modelType(), stateIndex,
                        null, Integer.MAX_VALUE, stateIndex);
                Entity<?> known = reconstructed.get(viewKey);
                if (known == null) {
                    missing.add(target);
                } else {
                    result.put(target.modelId(), known);
                }
            }
            if (missing.isEmpty()) {
                return result;
            }
            LinkedHashMap<String, MutableReconstruction> states =
                    new LinkedHashMap<>();
            LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
            for (ModelTargetResolver.ResolvedModel target : missing) {
                Entity<?> base = reconstructionBase(
                        target, stateIndex, false);
                states.put(
                        target.modelId(),
                        new MutableReconstruction(target, base));
                cursors.put(
                        target.modelId(),
                        base == null ? -1L : base.sequenceNumber());
            }
            ModelEventBatchLoader.LoadResult loaded = eventLoader.load(
                    cursors, stateIndex, page -> applyPage(page, states));
            for (ModelTargetResolver.ResolvedModel target : missing) {
                ModelHeadState head = loaded.heads().get(target.modelId());
                Entity<?> entity;
                if (head == null) {
                    modelCache.remove(
                            target.modelId());
                    entity = empty(target);
                } else {
                    entity = withHead(
                            states.get(target.modelId())
                                    .current,
                            head);
                }
                validateReconstruction(target, head, entity);
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), stateIndex,
                        null, Integer.MAX_VALUE, stateIndex), entity);
                result.put(target.modelId(), entity);
            }
            LinkedHashMap<String, Entity<?>> ordered = new LinkedHashMap<>();
            targets.forEach(target -> ordered.put(
                    target.modelId(), result.get(target.modelId())));
            return ordered;
        }

        private Entity<?> reconstructView(
                ModelTargetResolver.ResolvedModel target,
                long readStateIndex,
                String actionId,
                int substep,
                long actionStateIndex) {
            return reconstructViews(
                    List.of(target), readStateIndex, actionId,
                    substep, actionStateIndex).get(target.modelId());
        }

        private Map<String, Entity<?>> reconstructViews(
                List<ModelTargetResolver.ResolvedModel> targets,
                long readStateIndex,
                String actionId,
                int substep,
                long actionStateIndex) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            List<ModelTargetResolver.ResolvedModel> missing =
                    new ArrayList<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                ViewKey key = new ViewKey(
                        target.modelId(), target.modelType(), readStateIndex,
                        actionId, substep, actionStateIndex);
                Entity<?> cached = reconstructed.get(key);
                if (cached == null) {
                    missing.add(target);
                } else {
                    result.put(target.modelId(), cached);
                }
            }
            if (missing.isEmpty()) {
                return result;
            }
            Map<String, Entity<?>> base =
                    reconstructAt(missing, readStateIndex);
            if (substep > 0) {
                LinkedHashMap<String, Long> cursors =
                        new LinkedHashMap<>();
                missing.forEach(target -> cursors.put(
                        target.modelId(),
                        base.get(target.modelId()).sequenceNumber()));
                eventLoader.load(
                        cursors, null, actionId,
                        substep - 1,
                        page -> applyActionPrefix(
                                page, missing, base, readStateIndex,
                                actionId, substep));
            }
            for (ModelTargetResolver.ResolvedModel target : missing) {
                Entity<?> entity = base.get(target.modelId());
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), readStateIndex,
                        actionId, substep, actionStateIndex), entity);
                result.put(target.modelId(), entity);
            }
            LinkedHashMap<String, Entity<?>> ordered = new LinkedHashMap<>();
            targets.forEach(target -> ordered.put(
                    target.modelId(), result.get(target.modelId())));
            return ordered;
        }

        private void applyActionPrefix(
                GetModelEventsResult page,
                List<ModelTargetResolver.ResolvedModel> targets,
                Map<String, Entity<?>> current,
                long readStateIndex,
                String actionId,
                int substep) {
            Map<String, ModelTargetResolver.ResolvedModel> targetsById =
                    new HashMap<>();
            targets.forEach(target -> targetsById.put(
                    target.modelId(), target));
            Map<Long, io.fluxzero.common.api.SerializedMessage> payloads =
                    new HashMap<>();
            for (ModelEventPayload payload : page.getPayloads()) {
                payloads.put(payload.getStateIndex(), payload.getEvent());
            }
            for (ModelEventStream stream : page.getStreams()) {
                ModelTargetResolver.ResolvedModel target =
                        targetsById.get(stream.getModelId());
                if (target == null) {
                    throw new EventSourcingException(
                            "Model event store returned unrelated stream "
                            + stream.getModelId());
                }
                for (ModelEventMembership membership : stream.getMemberships()) {
                    if (membership.getStateIndex() > readStateIndex
                        && membership.getActionId().equals(actionId)
                        && membership.getSubstep() < substep) {
                        current.put(
                                target.modelId(),
                                apply(
                                        target,
                                        current.get(target.modelId()),
                                        new StoredEvent(
                                                membership,
                                                Objects.requireNonNull(
                                                        payloads.get(membership
                                                                .getStateIndex())))));
                    }
                }
            }
        }

        private EventSourcingException incompleteHistory(String modelId) {
            return new EventSourcingException(
                    "Cannot reconstruct model '%s' because its stored event history is incomplete"
                            .formatted(modelId));
        }

        private final class MutableReconstruction {
            private final ModelTargetResolver.ResolvedModel target;
            private final Entity<?> base;
            private Entity<?> current;
            private ModelEventMembership previous;

            private MutableReconstruction(
                    ModelTargetResolver.ResolvedModel target, Entity<?> base) {
                this.target = target;
                this.base = base;
                this.current = base == null ? empty(target) : base;
            }

            private void apply(StoredEvent storedEvent) {
                ModelEventMembership membership = storedEvent.membership();
                boolean followsCurrent = previous == null
                        ? base == null
                          || membership.getReadStateIndex() >= stateIndex(base)
                        : membership.getReadStateIndex() >= previous.getStateIndex()
                          || sameEarlierAction(previous, membership);
                Entity<?> begin = followsCurrent
                        ? current
                        : reconstructView(
                                target, membership.getReadStateIndex(),
                                membership.getActionId(), membership.getSubstep(),
                                membership.getStateIndex());
                current = ReconstructionSession.this.apply(
                        target, begin, storedEvent);
                previous = membership;
                rememberCheckpoint(target, current);
            }
        }

        private void rememberCheckpoint(
                ModelTargetResolver.ResolvedModel target, Entity<?> entity) {
            Model model = ModelMetadata.of(target.modelType())
                    .model().orElseThrow();
            int period = model.checkpointPeriod();
            if (period <= 0 || entity.sequenceNumber() < 0
                || Math.floorMod(entity.sequenceNumber() + 1L, period) != 0L) {
                return;
            }
            TreeMap<Long, Entity<?>> known = checkpoints.computeIfAbsent(
                    new ModelKey(target.modelId(), target.modelType()),
                    ignored -> new TreeMap<>());
            known.put(stateIndex(entity), entity);
            while (known.size() > 1_024) {
                known.pollFirstEntry();
            }
        }

        private Entity<?> apply(
                ModelTargetResolver.ResolvedModel target,
                Entity<?> begin,
                StoredEvent storedEvent) {
            ModelEventMembership membership = storedEvent.membership();
            List<Message> messages = deserialize(target.modelType(), membership, storedEvent);
            Entity<?> result = begin;
            for (Message message : messages) {
                Object payload = message.getPayload();
                List<ModelMetadata.HandlerMethod> handlers = replayHandlers.computeIfAbsent(
                        new HandlerKey(payload.getClass(), target.modelType()),
                        ignored -> replayHandlers(payload.getClass(), target.modelType()));
                if (handlers.isEmpty()) {
                    if (ModelMetadata.of(target.modelType()).model().orElseThrow()
                            .ignoreUnknownEvents()) {
                        continue;
                    }
                    throw new EventSourcingException(
                            "No replay apply found for %s on model %s"
                                    .formatted(payload.getClass().getName(), target.modelType().getName()));
                }
                ModelTargetResolver.Resolution resolution =
                        ModelTargetResolver.resolve(payload, handlers);
                if (resolution.hasAncestorDependencies()) {
                    long relationshipBoundary =
                            membership.getReadStateIndex();
                    if (relationshipBoundary < 0L) {
                        throw new EventSourcingException(
                                "Model event at state %d requires an ancestor before any model state was observed"
                                        .formatted(
                                                membership
                                                        .getStateIndex()));
                    }
                    ReplayAncestorKey key =
                            new ReplayAncestorKey(
                                    resolution,
                                    relationshipBoundary,
                                    membership.getActionId(),
                                    membership.getSubstep());
                    ModelTargetResolver.Resolution directResolution =
                            resolution;
                    boolean firstSubstep =
                            membership.getSubstep() == 0;
                    resolution =
                            replayAncestorResolutions.computeIfAbsent(
                                    key, ignored -> {
                                        AncestorResolution ancestors =
                                                resolveAncestors(
                                                        directResolution,
                                                        firstSubstep
                                                                ? relationshipBoundary
                                                                : null,
                                                        firstSubstep
                                                                ? null
                                                                : membership
                                                                        .getActionId(),
                                                        firstSubstep
                                                                ? null
                                                                : membership
                                                                        .getSubstep()
                                                                  - 1,
                                                        Map.of());
                                        boolean invalidBoundary =
                                                firstSubstep
                                                        ? ancestors
                                                                  .stateIndex()
                                                          != relationshipBoundary
                                                        : ancestors
                                                                  .stateIndex()
                                                          < relationshipBoundary
                                                          || ancestors
                                                                     .stateIndex()
                                                             >= membership
                                                                     .getStateIndex();
                                        if (invalidBoundary) {
                                            throw new EventSourcingException(
                                                    "Historical ancestor graph for action %s substep %d "
                                                    + "resolved invalid boundary %d (read=%d, event=%d)"
                                                            .formatted(
                                                                    membership
                                                                            .getActionId(),
                                                                    membership
                                                                            .getSubstep(),
                                                                    ancestors
                                                                            .stateIndex(),
                                                                    relationshipBoundary,
                                                                    membership
                                                                            .getStateIndex()));
                                        }
                                        return ancestors.resolution();
                                    });
                }
                List<ModelTargetResolver.ResolvedModel> dependencies =
                        resolution.models().stream()
                                .filter(dependency -> !dependency.modelId()
                                        .equals(target.modelId()))
                                .toList();
                Map<String, Entity<?>> dependencyViews = dependencies.isEmpty()
                        ? Map.of()
                        : reconstructViews(
                                dependencies,
                                membership.getReadStateIndex(),
                                membership.getActionId(),
                                membership.getSubstep(),
                                membership.getStateIndex());
                Map<String, Entity<?>> loaded = new LinkedHashMap<>();
                for (ModelTargetResolver.ResolvedModel dependency : resolution.models()) {
                    Entity<?> entity = dependency.modelId().equals(target.modelId())
                            ? result
                            : dependencyViews.get(dependency.modelId());
                    loaded.put(dependency.modelId(), entity);
                }
                ModelActionContext context = ModelActionContext.create(
                        membership.getReadStateIndex(), resolution, loaded);
                DeserializingMessage event = new DeserializingMessage(
                        message, EVENT, null, serializer);
                context.attachTo(event);
                try {
                    ModelEventReplayer.ReplayResult replay =
                            eventReplayer.replay(
                                    event, context, handlers, target.modelId());
                    if (replay.applied()) {
                        result = updateValue(result, replay.value());
                    } else {
                        throw new EventSourcingException(
                                "Stored membership for %s at state %d produced no target transition"
                                        .formatted(target.modelId(), membership.getStateIndex()));
                    }
                } catch (Throwable failure) {
                    throw new EventSourcingException(
                            "Failed to apply model event at state %d to %s"
                                    .formatted(membership.getStateIndex(), target.modelId()),
                            failure);
                }
            }
            return withMembership(result, storedEvent, begin);
        }

        private List<Message> deserialize(
                Class<?> modelType,
                ModelEventMembership membership,
                StoredEvent storedEvent) {
            boolean ignoreUnknown = ModelMetadata.of(modelType).model().orElseThrow()
                    .ignoreUnknownEvents();
            PayloadKey key = new PayloadKey(
                    membership.getStateIndex(), ignoreUnknown);
            return deserializedEvents.computeIfAbsent(key, ignored ->
                    serializer.deserializeMessages(
                                    Stream.of(storedEvent.event()), EVENT,
                                    ignoreUnknown ? UnknownTypeStrategy.IGNORE : UnknownTypeStrategy.FAIL)
                            .map(DeserializingMessage::toMessage)
                            .toList());
        }

        private List<ModelMetadata.HandlerMethod> replayHandlers(
                Class<?> payloadType, Class<?> modelType) {
            LinkedHashSet<ModelMetadata.HandlerMethod> result = new LinkedHashSet<>();
            ModelMetadata.of(payloadType).applyMethods().stream()
                    .filter(handler -> handler.targetModelTypes().stream()
                            .anyMatch(target -> compatible(target, modelType)))
                    .forEach(result::add);
            ModelMetadata.of(modelType).applyMethods().stream()
                    .filter(handler -> potentiallyAcceptsPayload(handler, payloadType))
                    .forEach(result::add);
            return List.copyOf(result);
        }

        private boolean potentiallyAcceptsPayload(
                ModelMetadata.HandlerMethod handler, Class<?> payloadType) {
            Executable executable = handler.executable();
            boolean hasDomainParameter = false;
            for (Parameter parameter : executable.getParameters()) {
                if (handler.modelParameters().stream()
                        .anyMatch(model -> model.parameter().equals(parameter))) {
                    continue;
                }
                Class<?> parameterType = parameter.getType();
                if (parameterType.isAssignableFrom(payloadType)) {
                    return true;
                }
                if (!isFrameworkParameter(parameterType)) {
                    hasDomainParameter = true;
                }
            }
            return !hasDomainParameter;
        }

        private boolean isFrameworkParameter(Class<?> parameterType) {
            return parameterType.equals(Instant.class)
                   || parameterType.equals(io.fluxzero.common.api.Metadata.class)
                   || parameterType.equals(Message.class)
                   || parameterType.equals(DeserializingMessage.class);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> empty(ModelTargetResolver.ResolvedModel target) {
            ModelMetadata metadata = ModelMetadata.validate(target.modelType());
            return ImmutableModelRoot.<Object>builder()
                    .id(target.modelId())
                    .type((Class<Object>) target.modelType())
                    .idProperty(metadata.entityId().orElseThrow().name())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .build();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Entity<?> updateValue(Entity<?> entity, Object value) {
            return ((Entity) entity).update(ignored -> value);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withMembership(
                Entity<?> entity,
                StoredEvent storedEvent,
                Entity<?> previous) {
            ModelEventMembership membership = storedEvent.membership();
            io.fluxzero.common.api.SerializedMessage event = storedEvent.event();
            Model model = ModelMetadata.of(entity.type())
                    .model().orElseThrow();
            return ImmutableModelRoot.<Object>builder()
                    .id(entity.id())
                    .type((Class<Object>) entity.type())
                    .idProperty(entity.idProperty())
                    .value(entity.get())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(membership.getSequenceNumber())
                    .stateIndex(membership.getStateIndex())
                    .lastEventId(event.getMessageId())
                    .lastEventIndex(event.getIndex())
                    .timestamp(Instant.ofEpochMilli(event.getTimestamp()))
                    .previous(castPrevious(retainPrevious(
                            previous, model)))
                    .build();
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withHead(Entity<?> entity, ModelHeadState head) {
            if (head == null) {
                return entity;
            }
            if (entity instanceof ImmutableModelRoot<?> model
                && model.sequenceNumber() == head.getSequenceNumber()
                && model.stateIndex() == head.getStateIndex()) {
                return entity;
            }
            return ImmutableModelRoot.<Object>builder()
                    .id(entity.id())
                    .type((Class<Object>) entity.type())
                    .idProperty(entity.idProperty())
                    .value(entity.get())
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(head.getSequenceNumber())
                    .stateIndex(head.getStateIndex())
                    .timestamp(entity.timestamp())
                    .previous(castPrevious(entity.previous()))
                    .build();
        }

        private void validateReconstruction(
                ModelTargetResolver.ResolvedModel target,
                ModelHeadState head,
                Entity<?> entity) {
            if (head == null) {
                if (entity.isPresent()) {
                    throw new EventSourcingException(
                            "Missing model head for reconstructed " + target.modelId());
                }
                return;
            }
            if (head.isDeleted() != entity.isEmpty()) {
                throw new EventSourcingException(
                        "Model '%s' reconstructed deletion=%s but its head reports deletion=%s"
                                .formatted(target.modelId(), entity.isEmpty(), head.isDeleted()));
            }
            validateValueId(target.modelId(), ModelMetadata.of(target.modelType()), entity.get());
        }
    }

    private static boolean compatible(Class<?> left, Class<?> right) {
        return left.isAssignableFrom(right) || right.isAssignableFrom(left);
    }

    private static long stateIndex(Entity<?> entity) {
        return entity instanceof ModelRoot<?> model ? model.stateIndex() : -1L;
    }

    private static boolean sameEarlierAction(
            ModelEventMembership previous, ModelEventMembership current) {
        return previous.getActionId().equals(current.getActionId())
               && previous.getSubstep() < current.getSubstep();
    }

    private record ReconstructionBatch(
            long stateIndex, Map<String, Entity<?>> entities) {
    }

    private record AncestorResolution(
            long stateIndex,
            ModelTargetResolver.Resolution resolution) {
    }

    private record GraphReachability(
            List<String> ancestorIds,
            Map<String, List<ModelGraphEdge>> incoming) {
    }

    private record StoredEvent(
            ModelEventMembership membership,
            io.fluxzero.common.api.SerializedMessage event) {
    }

    private record ViewKey(
            String modelId,
            Class<?> modelType,
            long readStateIndex,
            String actionId,
            int substep,
            long actionStateIndex) {
    }

    private record ModelKey(String modelId, Class<?> modelType) {
    }

    private record PayloadKey(long stateIndex, boolean ignoreUnknown) {
    }

    private record HandlerKey(Class<?> payloadType, Class<?> modelType) {
    }

    private record ReplayAncestorKey(
            ModelTargetResolver.Resolution resolution,
            long relationshipBoundary,
            String actionId,
            int substep) {
    }

    private static final class ModelEventStateBoundary {
        private final String sourceActionId;
        private final int sourceSubstep;
        private Long stateIndex;

        private ModelEventStateBoundary(
                String sourceActionId, int sourceSubstep) {
            this.sourceActionId = sourceActionId;
            this.sourceSubstep = sourceSubstep;
        }

        private synchronized Long stateIndex() {
            return stateIndex;
        }

        private synchronized String actionId() {
            return stateIndex == null
                    ? sourceActionId : null;
        }

        private synchronized Integer substep() {
            return stateIndex == null
                    ? sourceSubstep : null;
        }

        private synchronized void pin(long value) {
            if (stateIndex != null && stateIndex != value) {
                throw new EventSourcingException(
                        "Published model action %s substep %d resolved to both state %d and %d"
                                .formatted(
                                        sourceActionId, sourceSubstep,
                                        stateIndex, value));
            }
            stateIndex = value;
        }
    }

    /**
     * Final authoritative state and positions for a locally committed model.
     */
    public record CommittedModel(
            String modelId,
            Class<?> modelType,
            boolean historyComplete,
            boolean snapshotDue,
            List<CommittedRevision> revisions) {
        public CommittedModel {
            revisions = List.copyOf(revisions);
            if (revisions.isEmpty()) {
                throw new IllegalArgumentException(
                        "A committed model must contain at least one revision");
            }
        }

        public CommittedRevision latest() {
            return revisions.getLast();
        }

        public Object value() {
            return latest().value();
        }

        public long sequenceNumber() {
            return latest().sequenceNumber();
        }

        public long stateIndex() {
            return latest().stateIndex();
        }

        public String lastEventId() {
            return latest().lastEventId();
        }

        public Long lastEventIndex() {
            return latest().lastEventIndex();
        }

        public Instant timestamp() {
            return latest().timestamp();
        }
    }

    /**
     * One accepted local revision used to keep the cache chain identical to event-stream reconstruction.
     */
    public record CommittedRevision(
            Object value,
            long sequenceNumber,
            long stateIndex,
            String lastEventId,
            Long lastEventIndex,
            Instant timestamp) {
    }
}
