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

import io.fluxzero.common.api.SerializedMessage;
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
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
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
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.CascadedModelDeletion;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelCommitContext;
import io.fluxzero.sdk.modeling.ModelEventReplayer;
import io.fluxzero.sdk.modeling.ModelGraphProjections;
import io.fluxzero.sdk.modeling.MessageBatchModelView;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.modeling.ModelTargetResolver;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import lombok.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.NOTIFICATION;
import static io.fluxzero.common.api.search.ModelGraphComposition.UNBOUNDED;
import static io.fluxzero.common.reflection.ReflectionUtils.classForName;

/**
 * Default repository for independently stored models.
 * <p>
 * Current document-based models use their synchronously maintained direct document. Event-sourced and historical
 * loads use the model-stream protocol and reconstruct every selected stream at one pinned {@code stateIndex}.
 */
public class DefaultModelRepository extends AbstractNamespaced<ModelRepository> implements ModelRepository {
    private static final int COMMIT_ANCESTOR_MAX_DEPTH = 64;
    private static final int COMMIT_ANCESTOR_MAX_MODELS = 10_000;
    private static final int MAX_PARALLEL_GRAPH_RECONSTRUCTIONS = 8;
    private static final int COMMITTED_CACHE_UPDATE_BATCH_SIZE = 128;

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
    private final Map<HandlerKey, ReplayPlan> replayPlans =
            new ConcurrentHashMap<>();

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
                                exactModelId(modelId),
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
        String exactId = exactModelId(modelId);
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

    private static String exactModelId(Object modelId) {
        Objects.requireNonNull(modelId, "Model ID must not be null");
        return modelId instanceof Id<?> id
                ? ModelMetadata.of(id.getType()).repositoryId(id)
                : modelId.toString();
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
        return MessageBatchModelView.overlayCurrent(
                messageBatchNamespace(), modelId, modelType,
                loadDurable(modelId, modelType));
    }

    private <T> Entity<T> loadDurable(
            String modelId,
            Class<T> modelType) {
        ModelEventStateBoundary handlerBoundary =
                handlerBoundary();
        if (Object.class.equals(modelType)) {
            Class<?> resolvedType = resolveUntypedType(
                    modelId, handlerBoundary);
            if (resolvedType == null) {
                return cast(emptyUntyped(modelId));
            }
            return cast(loadDurable(
                    modelId,
                    resolvedType));
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
            if (cached != null
                && (cached.isPresent()
                    || !metadata.hasAliases())) {
                return cast(cached);
            }
        }
        if (!annotation.eventSourced()
            && handlerBoundary == null) {
            Entity<?> entity = loadDocumentUnchecked(
                    modelId, modelType,
                    metadata, annotation);
            if (entity.isEmpty()
                && metadata.hasAliases()) {
                String resolvedId = resolveCurrentModelId(modelId);
                if (!resolvedId.equals(modelId)) {
                    entity = loadDocumentUnchecked(
                            resolvedId, modelType,
                            metadata, annotation);
                }
            }
            if (!annotation.cached()
                || modelCacheTracker == null) {
                return cast(entity);
            }
            Long readStateIndex =
                    modelCacheTracker
                            .safeDocumentBoundary();
            if (readStateIndex != null
                && (entity.isPresent()
                    || !metadata.hasAliases())) {
                String resolvedId = entity.isPresent()
                        ? entity.id().toString()
                        : modelId;
                modelCache.put(resolvedId, entity);
                modelCacheTracker.loaded(
                        resolvedId, modelType,
                        readStateIndex);
            }
            return cast(entity);
        }
        requireEventReconstruction();
        ModelTargetResolver.ResolvedModel target = new ModelTargetResolver.ResolvedModel(
                modelId, modelType, ModelTargetResolver.Access.READ_ONLY,
                List.of(metadata.entityId().orElseThrow().name()));
        ModelCommitContext context = loadContext(
                new ModelTargetResolver.Resolution(
                        List.of(target), List.of()),
                boundary(handlerBoundary),
                Map.of(), false);
        pin(handlerBoundary, context.readStateIndex());
        Entity<?> entity = context.entries().getFirst().entity();
        if (handlerBoundary == null
            && annotation.cached()
            && modelCacheTracker != null
            && entity.isPresent()) {
            modelCacheTracker.loaded(
                    entity.id().toString(),
                    modelType,
                    context.readStateIndex());
        }
        return cast(entity);
    }

    @Override
    public <T> List<Entity<T>> loadAll(
            @NonNull List<?> modelIds,
            @NonNull Class<T> modelType) {
        if (modelIds.isEmpty()) {
            return List.of();
        }
        ModelMetadata metadata = ModelMetadata.validate(modelType);
        String idProperty = metadata.entityId().orElseThrow().name();
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        List<String> ids = modelIds.stream()
                .map(modelId -> Objects.requireNonNull(
                        modelId, "Model ID must not be null"))
                .map(metadata::repositoryId)
                .peek(modelId -> {
                    if (!uniqueIds.add(modelId)) {
                        throw new IllegalArgumentException(
                                "Duplicate model ID " + modelId);
                    }
                })
                .toList();
        List<ModelTargetResolver.ResolvedModel> targets = ids.stream()
                .map(modelId -> new ModelTargetResolver.ResolvedModel(
                        modelId,
                        modelType,
                        ModelTargetResolver.Access.READ_ONLY,
                        List.of(idProperty)))
                .toList();
        ModelEventStateBoundary handlerBoundary = handlerBoundary();
        ModelCommitContext context = loadContext(
                new ModelTargetResolver.Resolution(targets, List.of()),
                boundary(handlerBoundary),
                Map.of(), false);
        context = MessageBatchModelView.overlayCurrent(
                messageBatchNamespace(), context);
        pin(handlerBoundary, context.readStateIndex());
        return context.entries().stream()
                .map(ModelCommitContext.Entry::entity)
                .map(DefaultModelRepository::<T>cast)
                .toList();
    }

    @Override
    public <T> Graph<T> loadGraph(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            @NonNull Graph.Options options) {
        requireEventReconstruction();
        ModelMetadata.validate(rootType);
        ModelEventStateBoundary handlerBoundary =
                handlerBoundary();
        ReconstructedGraph<T> graph = loadGraph(
                rootId, rootType, options,
                boundary(handlerBoundary),
                handlerBoundary, true);
        return Graphs.compose(
                rootId, graph.stateIndex(), graph.models(),
                graph.edges(), this, false);
    }

    @Override
    public <T> Graph<T> loadGraphAt(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long stateIndex,
            @NonNull Graph.Options options) {
        return loadGraphAt(
                rootId, rootType, stateIndex, options, false);
    }

    /**
     * Reconstructs a graph at an exact durable boundary and overlays pending values from earlier messages in the
     * current message batch. This is used by atomic model planning that must retain read-your-writes semantics without
     * advancing beyond its already pinned durable boundary.
     */
    public <T> Graph<T> loadGraphAtIncludingMessageBatch(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long stateIndex,
            @NonNull Graph.Options options) {
        return loadGraphAt(
                rootId, rootType, stateIndex, options, true);
    }

    private <T> Graph<T> loadGraphAt(
            String rootId,
            Class<T> rootType,
            long stateIndex,
            Graph.Options options,
            boolean includeMessageBatch) {
        if (stateIndex < -1L) {
            throw new IllegalArgumentException(
                    "Model graph stateIndex must be at least -1");
        }
        requireEventReconstruction();
        ModelMetadata.validate(rootType);
        ReconstructedGraph<T> graph = loadGraph(
                rootId, rootType, options,
                ModelEventBatchLoader.Boundary.at(stateIndex), null,
                includeMessageBatch);
        return Graphs.compose(
                rootId, graph.stateIndex(), graph.models(),
                graph.edges(), this, true);
    }

    private <T> ReconstructedGraph<T> loadGraph(
            String rootId,
            Class<T> rootType,
            Graph.Options options,
            ModelEventBatchLoader.Boundary boundary,
            ModelEventStateBoundary handlerBoundary,
            boolean includeMessageBatch) {
        GetModelGraphResult graph = client.getEventStoreClient().getModelGraph(
                new GetModelGraph(
                        rootId, boundary.stateIndex(),
                        boundary.commitId(),
                        boundary.substep(),
                        boundary.eventIndex(),
                        options.maxDepth(), options.maxModels(),
                        0, 0L, false));
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
                        !boundary.historical());
        if (reconstructed.stateIndex() != graph.getStateIndex()) {
            throw new EventSourcingException(
                    "Model graph moved from state index %d to %d during reconstruction"
                            .formatted(graph.getStateIndex(), reconstructed.stateIndex()));
        }
        Map<String, MessageBatchModelView.StagedModel> staged =
                includeMessageBatch
                        ? MessageBatchModelView.currentValues(
                                messageBatchNamespace())
                        : Map.of();
        Map<String, Entity<?>> durableModels =
                reconstructed.entities();
        MessageBatchModelView.StagedModel stagedRoot =
                staged.get(rootId);
        if (stagedRoot != null
            && !stagedRoot.existedBefore()
            && !durableModels.containsKey(rootId)) {
            LinkedHashMap<String, Entity<?>> withEmptyRoot =
                    new LinkedHashMap<>(durableModels);
            withEmptyRoot.put(
                    rootId,
                    ImmutableModelRoot.builder()
                            .id(rootId)
                            .type((Class) stagedRoot.modelType())
                            .idProperty(ModelMetadata.validate(
                                            stagedRoot.modelType())
                                                .entityId()
                                                .orElseThrow()
                                                .name())
                            .value(null)
                            .build());
            durableModels = withEmptyRoot;
        }
        ReconstructedGraph<T> durable = composeGraph(
                rootId, graph.getStateIndex(),
                durableModels, graph.getEdges());
        return includeMessageBatch
                ? overlayMessageBatchGraph(
                        durable, rootId, rootType, options, staged)
                : durable;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ReconstructedGraph<T> overlayMessageBatchGraph(
            ReconstructedGraph<T> durable,
            String rootId,
            Class<T> rootType,
            Graph.Options options,
            Map<String, MessageBatchModelView.StagedModel> staged) {
        if (staged.isEmpty()) {
            return durable;
        }
        MessageBatchModelView.StagedModel rootCandidate =
                staged.get(rootId);
        if (rootCandidate != null
            && rootCandidate.value() == null) {
            Entity<?> deleted = MessageBatchModelView.overlayCurrent(
                    messageBatchNamespace(), rootId,
                    (Class) rootCandidate.modelType(),
                    durable.root().model());
            return composeGraph(
                    rootId, durable.stateIndex(),
                    Map.of(rootId, deleted), List.of());
        }
        LinkedHashMap<String, Entity<?>> models =
                new LinkedHashMap<>(durable.models());
        LinkedHashSet<ModelGraphEdge> edges =
                new LinkedHashSet<>(durable.edges());
        edges.removeIf(edge ->
                staged.containsKey(edge.getChildId()));
        staged.values().forEach(model -> {
            Object value = model.value();
            if (value == null) {
                return;
            }
            for (ModelMetadata.ParentReference parent :
                    ModelMetadata.validate(model.modelType())
                            .parentReferences()) {
                Object parentId = parent.read(value);
                if (parentId != null) {
                    edges.add(new ModelGraphEdge(
                            model.modelId(), parent.repositoryId(parentId),
                            parent.parentModelType() == null
                                    ? null
                                    : parent.parentModelType().getName(),
                            parent.path().isEmpty() ? null : parent.path(), -1L, null));
                }
            }
        });

        GraphSelection initial = selectGraph(
                rootId, edges, options);
        for (String modelId : initial.modelIds()) {
            MessageBatchModelView.StagedModel candidate =
                    staged.get(modelId);
            if (candidate == null || models.containsKey(modelId)
                || !candidate.existedBefore()) {
                continue;
            }
            ReconstructedGraph<?> supplemental = loadGraph(
                    modelId, (Class) candidate.modelType(),
                    Graph.Options.DEFAULT,
                    ModelEventBatchLoader.Boundary.at(
                            durable.stateIndex()),
                    null, false);
            models.putAll(supplemental.models());
            supplemental.edges().stream()
                    .filter(edge ->
                            !staged.containsKey(
                                    edge.getChildId()))
                    .forEach(edges::add);
        }

        GraphSelection selected = selectGraph(
                rootId, edges, options);
        LinkedHashMap<String, Entity<?>> selectedModels =
                new LinkedHashMap<>();
        for (String modelId : selected.modelIds()) {
            MessageBatchModelView.StagedModel candidate =
                    staged.get(modelId);
            Entity<?> entity = models.get(modelId);
            if (candidate != null) {
                if (entity == null) {
                    entity = ImmutableModelRoot.builder()
                            .id(modelId)
                            .type((Class) candidate.modelType())
                            .idProperty(ModelMetadata.validate(
                                            candidate.modelType())
                                                .entityId()
                                                .orElseThrow()
                                                .name())
                            .value(null)
                            .build();
                }
                entity = MessageBatchModelView.overlayCurrent(
                        messageBatchNamespace(), modelId,
                        (Class) candidate.modelType(), entity);
            }
            if (entity == null) {
                throw new EventSourcingException(
                        "Message-batch model graph contains an unloaded node "
                        + modelId);
            }
            selectedModels.put(modelId, entity);
        }
        Class<T> effectiveRootType =
                staged.containsKey(rootId)
                        ? (Class<T>) staged.get(rootId).modelType()
                        : rootType;
        if (!rootType.isAssignableFrom(effectiveRootType)) {
            throw new EventSourcingException(
                    "Message-batch graph root '%s' has staged type %s instead of %s"
                            .formatted(
                                    rootId, effectiveRootType.getName(),
                                    rootType.getName()));
        }
        return composeGraph(
                rootId, durable.stateIndex(),
                selectedModels, selected.edges());
    }

    private static GraphSelection selectGraph(
            String rootId,
            Collection<ModelGraphEdge> edges,
            Graph.Options options) {
        LinkedHashMap<String, List<ModelGraphEdge>> byParent =
                new LinkedHashMap<>();
        for (ModelGraphEdge edge : edges) {
            byParent.computeIfAbsent(
                            edge.getParentId(), ignored -> new ArrayList<>())
                    .add(edge);
        }
        LinkedHashSet<String> modelIds = new LinkedHashSet<>();
        modelIds.add(rootId);
        List<String> frontier = List.of(rootId);
        List<ModelGraphEdge> selectedEdges = new ArrayList<>();
        for (int depth = 0;
             !frontier.isEmpty()
             && (options.maxDepth() == UNBOUNDED
                 || depth < options.maxDepth());
             depth++) {
            List<String> next = new ArrayList<>();
            for (String parentId : frontier) {
                for (ModelGraphEdge edge :
                        byParent.getOrDefault(parentId, List.of())) {
                    selectedEdges.add(edge);
                    if (modelIds.add(edge.getChildId())) {
                        if (options.maxModels() != UNBOUNDED
                            && modelIds.size() > options.maxModels()) {
                            throw new IllegalArgumentException(
                                    "Model graph exceeds maxModels "
                                    + options.maxModels());
                        }
                        next.add(edge.getChildId());
                    }
                }
            }
            frontier = next;
        }
        return new GraphSelection(
                List.copyOf(modelIds),
                List.copyOf(selectedEdges));
    }

    private record GraphSelection(
            List<String> modelIds,
            List<ModelGraphEdge> edges) {
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
        return reconstructPinned(
                targets, stateIndex, cacheAtBoundary,
                MAX_PARALLEL_GRAPH_RECONSTRUCTIONS,
                "Model graph");
    }

    private ReconstructionBatch reconstructPinned(
            List<ModelTargetResolver.ResolvedModel> targets,
            long stateIndex,
            boolean cacheAtBoundary,
            int maxParallelism,
            String description) {
        int batchCount = Math.min(
                maxParallelism,
                targets.size());
        if (batchCount < 2) {
            return new ReconstructionSession().reconstruct(
                    targets,
                    ModelEventBatchLoader.Boundary.at(stateIndex),
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
                                                                 batch,
                                                                 ModelEventBatchLoader.Boundary.at(
                                                                         stateIndex),
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
                            "Failed to reconstruct " + description.toLowerCase(), cause);
                }
                if (result.stateIndex() != stateIndex) {
                    throw new EventSourcingException(
                            "%s batch moved from state index %d to %d during reconstruction"
                                    .formatted(
                                            description,
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
                        description + " reconstruction omitted "
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
        ModelEventBatchLoader.Boundary boundary =
                boundary(handlerBoundary);
        GetModelEventsResult result =
                client.getEventStoreClient().getModelEvents(
                        new GetModelEvents(
                                List.of(
                                        new ModelEventStreamRequest(
                                                modelId, -1L, 1)),
                                boundary.stateIndex(),
                                boundary.commitId(),
                                boundary.substep(),
                                boundary.eventIndex(),
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
        String resolvedModelId = stream.getHead() == null
                ? modelId
                : stream.getHead().getModelId();
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
                                    resolvedModelId, payload)
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
        ModelEventBatchLoader.Boundary boundary =
                boundary(handlerBoundary);
        GetModelEventsResult result = client.getEventStoreClient().getModelEvents(
                new GetModelEvents(
                        List.of(new ModelEventStreamRequest(
                                modelId, -1L, 0)),
                        boundary.stateIndex(),
                        boundary.commitId(),
                        boundary.substep(),
                        boundary.eventIndex(),
                        ModelEventBatchLoader.DEFAULT_SETTINGS
                                .maxPayloadBytes()));
        pin(handlerBoundary, result.getStateIndex());
        ModelEventStream stream = result.getStreams().getFirst();
        if (stream.getHead() == null) {
            return null;
        }
        if (stream.getHead().getModelType() == null) {
            throw new EventSourcingException(
                    "Model '%s' has no stored type metadata".formatted(modelId));
        }
        return classForName(serializer.upcastType(
                stream.getHead().getModelType()));
    }

    private String resolveCurrentModelId(String requestedId) {
        if (client.getEventStoreClient() == null) {
            return requestedId;
        }
        GetModelEventsResult result =
                client.getEventStoreClient().getModelEvents(
                        new GetModelEvents(
                                List.of(
                                        new ModelEventStreamRequest(
                                                requestedId, -1L, 0)),
                                null, 0L));
        if (result.getStreams().size() != 1) {
            throw new EventSourcingException(
                    "Model alias lookup for '%s' returned %d streams"
                            .formatted(
                                    requestedId,
                                    result.getStreams().size()));
        }
        ModelEventStream stream = result.getStreams().getFirst();
        if (!requestedId.equals(stream.getModelId())) {
            throw new EventSourcingException(
                    "Model alias lookup for '%s' returned stream '%s'"
                            .formatted(
                                    requestedId,
                                    stream.getModelId()));
        }
        return stream.getHead() == null
                ? requestedId
                : stream.getHead().getModelId();
    }

    private Entity<Object> emptyUntyped(String modelId) {
        return ImmutableModelRoot.<Object>builder()
                .id(modelId)
                .type(Object.class)
                .entityHelper(entityHelper)
                .serializer(serializer)
                .build();
    }

    private ModelEventStateBoundary handlerBoundary() {
        DeserializingMessage current =
                DeserializingMessage.getCurrent();
        if (current == null
            || current.getMessageType() != EVENT
               && current.getMessageType() != NOTIFICATION) {
            return null;
        }
        return current.computeContextIfAbsent(
                        ModelEventStateBoundary.class,
                        message -> {
                            Object commitId = message.getMetadata() == null
                                    ? null
                                    : message.getMetadata().get(
                                            ModelEventMetadata.COMMIT_ID);
                            Object substep = message.getMetadata() == null
                                    ? null
                                    : message.getMetadata().get(
                                            ModelEventMetadata.SUBSTEP);
                            if (commitId instanceof String id
                                && !id.isBlank()
                                && substep != null) {
                                return ModelEventStateBoundary.commit(
                                        id, parseSubstep(substep));
                            }
                            return null;
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
                        "Published model event has an invalid commit substep",
                        failure);
            }
        }
        if (result < 0) {
            throw new EventSourcingException(
                    "Published model event has a negative commit substep");
        }
        return result;
    }

    private static ModelEventBatchLoader.Boundary boundary(
            ModelEventStateBoundary boundary) {
        return boundary == null
                ? ModelEventBatchLoader.Boundary.CURRENT
                : boundary.request();
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
    private <T> ReconstructedGraph<T> composeGraph(
            String rootId,
            long stateIndex,
            Map<String, Entity<?>> models,
            List<ModelGraphEdge> edges) {
        GraphComposer composer = new GraphComposer(models, edges);
        ReconstructedNode<T> root =
                (ReconstructedNode<T>) composer.node(rootId);
        return new ReconstructedGraph<>(
                stateIndex, root,
                Collections.unmodifiableMap(new LinkedHashMap<>(models)),
                edges);
    }

    private static final class GraphComposer {
        private final Map<String, Entity<?>> models;
        private final Map<String, List<ModelGraphEdge>> edgesByParent =
                new LinkedHashMap<>();
        private final Map<String, ReconstructedNode<?>> nodes =
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
        private ReconstructedNode<?> node(String modelId) {
            ReconstructedNode<?> known = nodes.get(modelId);
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
            LinkedHashMap<String, List<ReconstructedNode<?>>> children =
                    new LinkedHashMap<>();
            for (ModelGraphEdge edge :
                    edgesByParent.getOrDefault(modelId, List.of())) {
                children.computeIfAbsent(
                                edge.getPath(), ignored -> new ArrayList<>())
                        .add(node(edge.getChildId()));
            }
            visiting.remove(modelId);
            LinkedHashMap<String, List<ReconstructedNode<?>>> immutable =
                    new LinkedHashMap<>();
            children.forEach((path, values) ->
                                     immutable.put(path, List.copyOf(values)));
            ReconstructedNode<?> result = new ReconstructedNode(
                    model, Collections.unmodifiableMap(immutable));
            nodes.put(modelId, result);
            return result;
        }
    }

    private record ReconstructedGraph<T>(
            long stateIndex,
            ReconstructedNode<T> root,
            Map<String, Entity<?>> models,
            List<ModelGraphEdge> edges) {
    }

    private record ReconstructedNode<T>(
            Entity<T> model,
            Map<String, List<ReconstructedNode<?>>> children) {
    }

    /**
     * Loads all direct commit targets at one state boundary.
     * <p>
     * A {@code null} boundary pins the current event-store state once. Historical document-model dependencies are
     * reconstructed from stored model events; current document-model targets retain their direct-document load path.
     */
    @Override
    public ModelCommitContext loadContext(
            ModelTargetResolver.Resolution resolution) {
        ModelEventStateBoundary handlerBoundary =
                handlerBoundary();
        ModelCommitContext context = loadContext(
                resolution, boundary(handlerBoundary), Map.of(), true);
        pin(handlerBoundary, context.readStateIndex());
        return context;
    }

    /**
     * Loads all direct commit targets at one explicit state boundary.
     * <p>
     * A {@code null} boundary pins the current event-store state once. Historical document-model dependencies are
     * reconstructed from stored model events; current document-model targets retain their direct-document load path.
     */
    public ModelCommitContext loadContext(
            ModelTargetResolver.Resolution resolution, Long maxStateIndex) {
        return loadContext(
                resolution,
                ModelEventBatchLoader.Boundary.at(maxStateIndex),
                Map.of(), false);
    }

    /**
     * Loads an commit context and overlays relationships declared by model values staged in earlier substeps.
     */
    public ModelCommitContext loadContext(
            ModelTargetResolver.Resolution resolution,
            Long maxStateIndex,
            Map<String, Object> stagedValues) {
        return loadContext(
                resolution, maxStateIndex,
                stagedValues, true);
    }

    /**
     * Loads a commit context with an explicit choice whether pending values from the surrounding tracking batch should
     * be overlaid. Automatic model handling disables this generic overlay because its preplanned batch view already
     * supplies exactly the required predecessors; explicit operations and ordinary handlers enable it.
     */
    public ModelCommitContext loadContext(
            ModelTargetResolver.Resolution resolution,
            Long maxStateIndex,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch) {
        ModelCommitContext context = loadContext(
                resolution,
                ModelEventBatchLoader.Boundary.at(maxStateIndex),
                stagedValues, includeMessageBatch);
        return includeMessageBatch
                ? MessageBatchModelView.overlayCurrent(
                        messageBatchNamespace(), context)
                : context;
    }

    private String messageBatchNamespace() {
        DeserializingMessage message =
                DeserializingMessage.getCurrent();
        if (message == null) {
            return client.namespace();
        }
        String consumerNamespace =
                ClientUtils.getConsumerNamespace(message);
        String resolvedConsumerNamespace =
                client.forNamespace(consumerNamespace).namespace();
        return Objects.equals(
                client.namespace(), resolvedConsumerNamespace)
                ? consumerNamespace : client.namespace();
    }

    /**
     * Returns independently proven current cache values for the requested direct models.
     *
     * <p>A missing or stale entry is omitted instead of invalidating unrelated hits. Callers can therefore batch-load
     * only the misses while retaining the exact state boundary through which every returned value is known to be
     * current.</p>
     */
    public Map<String, CurrentModel> currentModels(
            Collection<ModelTargetResolver.ResolvedModel> targets) {
        if (modelCacheTracker == null || targets.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, CurrentModel> result =
                new LinkedHashMap<>(targets.size());
        for (ModelTargetResolver.ResolvedModel target : targets) {
            ModelCacheTracker.CurrentModel current =
                    modelCacheTracker.currentVersion(
                            target.modelId(), target.modelType());
            if (current != null) {
                result.put(
                        target.modelId(),
                        new CurrentModel(
                                current.entity(),
                                current.validThrough()));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns current cache values for a pre-resolved batch of direct model IDs.
     *
     * <p>This is the allocation-light equivalent of {@link #currentModels(Collection)} for automatic single-target
     * command batches whose structural target plan has already proved the ID/type pairing.</p>
     */
    public Map<String, CurrentModel> currentModels(
            Map<String, Class<?>> modelTypes) {
        if (modelCacheTracker == null || modelTypes.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, CurrentModel> result =
                new LinkedHashMap<>(modelTypes.size());
        modelTypes.forEach((modelId, modelType) -> {
            ModelCacheTracker.CurrentModel current =
                    modelCacheTracker.currentVersion(
                            modelId, modelType);
            if (current != null) {
                result.put(
                        modelId,
                        new CurrentModel(
                                current.entity(),
                                current.validThrough()));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * Supplies one independently proven current cache value without allocating an intermediate result object.
     */
    public boolean supplyCurrentModel(
            String modelId,
            Class<?> modelType,
            CurrentModelSink sink) {
        return modelCacheTracker != null
               && modelCacheTracker.supplyCurrentVersion(
                       modelId, modelType, sink);
    }

    /**
     * Supplies independently proven current cache values for a pre-resolved group of direct model lookups.
     * Cache access bookkeeping is amortized across the group without weakening the proof boundary of any model.
     */
    public void supplyCurrentModels(
            Iterable<? extends CurrentModelLookup> lookups) {
        if (modelCacheTracker != null) {
            modelCacheTracker.supplyCurrentVersions(
                    lookups);
        }
    }

    private ModelCommitContext loadContext(
            ModelTargetResolver.Resolution resolution,
            ModelEventBatchLoader.Boundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(stagedValues, "stagedValues");
        requireEventReconstruction();
        boolean historicalBoundary = boundary.historical();
        if (!historicalBoundary
            && modelCacheTracker != null
            && resolution.models().stream()
                    .anyMatch(target ->
                                      ModelMetadata.validate(
                                                      target.modelType())
                                              .model().orElseThrow()
                                              .cached())) {
            /*
             * Overlap the non-blocking tracker bootstrap with model I/O. This keeps websocket callbacks free while
             * making the freshly reconstructed value eligible for the immediate hot-cache path in the common case.
             */
            modelCacheTracker.prepare();
        }
        Long ancestorStateIndex = null;
        if (resolution.hasAncestorDependencies()) {
            AncestorResolution ancestors = resolveAncestors(
                    resolution, boundary, stagedValues,
                    includeMessageBatch);
            resolution = ancestors.resolution();
            ancestorStateIndex = ancestors.stateIndex();
            boundary = ModelEventBatchLoader.Boundary.at(ancestorStateIndex);
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
                            Map.of(), boundary,
                            ignored -> {
                            }).stateIndex()
                    : ancestorStateIndex;
        } else {
            CurrentModelContext cached =
                    !historicalBoundary
                    && ancestorStateIndex == null
                            ? currentContext(eventTargets)
                            : null;
            if (cached == null) {
                ReconstructionBatch batch = session.reconstruct(
                        eventTargets, boundary);
                stateIndex = batch.stateIndex();
                loaded.putAll(batch.entities());
            } else {
                stateIndex = cached.stateIndex();
                loaded.putAll(cached.entities());
            }
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
                    ModelEventBatchLoader.Boundary.at(stateIndex));
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
        LinkedHashMap<String, Entity<?>> canonicalLoaded =
                new LinkedHashMap<>(loaded.size());
        List<ModelTargetResolver.ResolvedModel> canonicalTargets =
                new ArrayList<>(resolution.models().size());
        boolean aliasesResolved = false;
        for (ModelTargetResolver.ResolvedModel target :
                resolution.models()) {
            Entity<?> entity = loaded.get(target.modelId());
            String resolvedId = entity != null
                                && entity.isPresent()
                                && entity.id() != null
                    ? entity.id().toString()
                    : target.modelId();
            if (!resolvedId.equals(target.modelId())) {
                if (target.access().writes()) {
                    throw new EventSourcingException(
                            "Writable model target '%s' resolved through alias to '%s'"
                                    .formatted(
                                            target.modelId(),
                                            resolvedId));
                }
                aliasesResolved = true;
                target = new ModelTargetResolver.ResolvedModel(
                        resolvedId,
                        target.modelType(),
                        target.access(),
                        target.sourceProperties());
            }
            if (canonicalLoaded.put(resolvedId, entity) != null) {
                throw new EventSourcingException(
                        "Multiple requested model IDs resolve to "
                        + resolvedId);
            }
            canonicalTargets.add(target);
        }
        if (aliasesResolved) {
            resolution = resolution.withResolvedModels(
                    canonicalTargets);
            loaded = canonicalLoaded;
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
        return ModelCommitContext.create(stateIndex, resolution, loaded);
    }

    /**
     * Resolves a coherent current context without a store round trip when tracking has proved that
     * every selected cached value overlaps at one global state boundary.
     */
    private CurrentModelContext currentContext(
            List<ModelTargetResolver.ResolvedModel> targets) {
        if (modelCacheTracker == null || targets.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Entity<?>> entities =
                new LinkedHashMap<>();
        long latestModelStateIndex = -1L;
        long sharedValidThrough = Long.MAX_VALUE;
        for (ModelTargetResolver.ResolvedModel target :
                targets) {
            ModelCacheTracker.CurrentModel current =
                    modelCacheTracker.currentVersion(
                            target.modelId(),
                            target.modelType());
            if (current == null) {
                return null;
            }
            if (current.entity().isEmpty()
                && ModelMetadata.validate(
                                target.modelType())
                        .hasAliases()) {
                return null;
            }
            entities.put(
                    target.modelId(),
                    current.entity());
            latestModelStateIndex =
                    Math.max(
                            latestModelStateIndex,
                            current.modelStateIndex());
            sharedValidThrough =
                    Math.min(
                            sharedValidThrough,
                            current.validThrough());
        }
        if (latestModelStateIndex
            > sharedValidThrough) {
            return null;
        }
        return new CurrentModelContext(
                sharedValidThrough,
                Map.copyOf(entities));
    }

    private AncestorResolution resolveAncestors(
            ModelTargetResolver.Resolution resolution,
            ModelEventBatchLoader.Boundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch) {
        if (resolution.models().isEmpty()) {
            throw new IllegalStateException(
                    "Ancestor injection requires at least one direct model target from which to traverse");
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        resolution.models().forEach(target -> roots.add(target.modelId()));
        LinkedHashMap<String, Object> effectiveStagedValues =
                new LinkedHashMap<>(stagedValues);
        Map<String, Class<?>> stagedTypes;
        List<ModelGraphEdge> stagedEdges;
        GetModelGraphResult graph;
        for (int expansion = 0; ; expansion++) {
            if (expansion > COMMIT_ANCESTOR_MAX_DEPTH) {
                throw new IllegalStateException(
                        "Message-batch ancestor overlay exceeds maximum depth "
                        + COMMIT_ANCESTOR_MAX_DEPTH);
            }
            LinkedHashSet<String> requestRoots =
                    new LinkedHashSet<>(roots);
            stagedTypes = new LinkedHashMap<>();
            stagedEdges = new ArrayList<>();
            for (Map.Entry<String, Object> entry :
                    effectiveStagedValues.entrySet()) {
                requestRoots.add(entry.getKey());
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                ModelMetadata metadata =
                        ModelMetadata.validate(value.getClass());
                stagedTypes.put(entry.getKey(), value.getClass());
                for (ModelMetadata.ParentReference parent :
                        metadata.parentReferences()) {
                    Object parentId = parent.read(value);
                    if (parentId == null) {
                        continue;
                    }
                    String parentIdString = Objects.requireNonNull(
                            parent.repositoryId(parentId),
                            () -> "@ParentId "
                                  + parent.property().name()
                                  + " returned a null ID string");
                    requestRoots.add(parentIdString);
                    stagedEdges.add(new ModelGraphEdge(
                            entry.getKey(), parentIdString,
                            parent.parentModelType() == null
                                    ? null
                                    : parent.parentModelType().getName(),
                            parent.path().isEmpty()
                                    ? null : parent.path(),
                            -1L, null));
                }
            }
            if (requestRoots.size()
                > COMMIT_ANCESTOR_MAX_MODELS) {
                throw new IllegalStateException(
                        "Model commit requires more than %d ancestor traversal roots"
                                .formatted(
                                        COMMIT_ANCESTOR_MAX_MODELS));
            }

            graph = client.getEventStoreClient().getModelAncestors(
                    new GetModelAncestors(
                            List.copyOf(requestRoots),
                            boundary.stateIndex(),
                            boundary.commitId(),
                            boundary.substep(),
                            boundary.eventIndex(),
                            COMMIT_ANCESTOR_MAX_DEPTH,
                            COMMIT_ANCESTOR_MAX_MODELS,
                            0, 0L));
            if (!includeMessageBatch
                || !addPendingAncestorValues(
                        requestRoots, graph,
                        effectiveStagedValues)) {
                break;
            }
        }
        List<ModelGraphEdge> edges = new ArrayList<>(graph.getEdges());
        if (!effectiveStagedValues.isEmpty()) {
            edges.removeIf(edge ->
                    effectiveStagedValues.containsKey(
                            edge.getChildId()));
            edges.addAll(stagedEdges);
        }
        GraphReachability reachable = reachableAncestors(
                roots, edges, COMMIT_ANCESTOR_MAX_DEPTH,
                COMMIT_ANCESTOR_MAX_MODELS);

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
            ModelTargetResolver.merge(
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

    private boolean addPendingAncestorValues(
            Collection<String> requestRoots,
            GetModelGraphResult graph,
            Map<String, Object> stagedValues) {
        LinkedHashSet<String> candidateIds =
                new LinkedHashSet<>(requestRoots);
        graph.getStreams().forEach(stream ->
                candidateIds.add(stream.getModelId()));
        graph.getEdges().forEach(edge -> {
            candidateIds.add(edge.getChildId());
            candidateIds.add(edge.getParentId());
        });
        boolean changed = false;
        String namespace = messageBatchNamespace();
        for (String modelId : candidateIds) {
            if (stagedValues.containsKey(modelId)) {
                continue;
            }
            MessageBatchModelView.StagedModel pending =
                    MessageBatchModelView.currentValue(
                            namespace, modelId);
            if (pending != null) {
                stagedValues.put(modelId, pending.value());
                changed = true;
            }
        }
        return changed;
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

    /**
     * Makes accepted local model transitions immediately visible through this repository.
     */
    public void updateAfterCommit(
            List<CommittedModel> committedModels) {
        if (committedModels.isEmpty()) {
            return;
        }
        if (committedModels.size() == 1) {
            updateAfterCommit(committedModels.getFirst());
            return;
        }
        for (int offset = 0;
             offset < committedModels.size();
             offset += COMMITTED_CACHE_UPDATE_BATCH_SIZE) {
            modelCache.<CommittedModel, Entity<?>>updateAll(
                    committedModels.subList(
                            offset,
                            Math.min(
                                    committedModels.size(),
                                    offset
                                    + COMMITTED_CACHE_UPDATE_BATCH_SIZE)),
                    CommittedModel::modelId,
                    (committed, current) ->
                            applyCommittedModel(
                                    current, committed));
        }
        if (modelCacheTracker == null) {
            return;
        }
        committedModels.forEach(
                this::updateTrackerAfterCommit);
    }

    private Entity<?> applyCommittedModel(
            Entity<?> current,
            CommittedModel committed) {
        if (!committed.historyComplete()) {
            return current != null
                   && stateIndex(current)
                      > committed.stateIndex()
                    ? current : null;
        }
        if (!committed.model().cached()) {
            return null;
        }
        if (current != null
            && stateIndex(current)
               >= committed.stateIndex()) {
            return current;
        }
        return committedEntity(
                committed, ModelMetadata.of(committed.modelType()),
                committed.model(), current);
    }

    private void updateTrackerAfterCommit(
            CommittedModel committed) {
        if (!committed.historyComplete()) {
            modelCacheTracker.forget(
                    committed.modelId());
        } else if (committed.model().cached()) {
            modelCacheTracker.committed(
                    committed.modelId(),
                    committed.modelType(),
                    committed.stateIndex());
        }
    }

    private void updateAfterCommit(
            CommittedModel committed) {
        modelCache.<Entity<?>>compute(
                committed.modelId(),
                (ignored, current) ->
                        applyCommittedModel(
                                current, committed));
        if (modelCacheTracker != null) {
            updateTrackerAfterCommit(committed);
        }
    }

    /**
     * Marks model targets as belonging to an in-flight commit from this SDK so a concurrently observed tracker update
     * does not race the authoritative accepted result into an unnecessary cache refresh.
     *
     * @return an idempotent completion callback
     */
    public Runnable beginLocalCommit(
            Collection<String> modelIds) {
        return modelCacheTracker == null
                ? () -> {
                }
                : modelCacheTracker
                        .beginLocalCommit(modelIds);
    }

    CompletableFuture<Boolean> cacheTrackingReadiness() {
        return modelCacheTracker == null
                ? CompletableFuture.completedFuture(false)
                : modelCacheTracker.readiness();
    }

    /**
     * Removes commit-scoped entries before a strict-policy retry reload.
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
            ModelMetadata.RootConfiguration model,
            Entity<?> previous) {
        ModelMetadata.Property entityId = metadata.entityId().orElseThrow();
        Entity<?> result = previous;
        for (int index = 0;
             index < committed.revisionCount(); index++) {
            CommittedRevision revision =
                    committed.revision(index);
            if (!committed.valueIdsValidated()) {
                validateValueId(
                        committed.modelId(), metadata, revision.value());
            }
            result = ImmutableModelRoot.committed(
                    committed.modelId(),
                    (Class<Object>) committed.modelType(),
                    entityId.name(), revision.value(),
                    entityHelper, serializer,
                    revision.lastEventId(),
                    revision.lastEventIndex(),
                    revision.timestamp(),
                    revision.sequenceNumber(),
                    revision.stateIndex(),
                    castPrevious(retainPrevious(
                            result, model)));
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Committed model has no revisions: "
                    + committed.modelId());
        }
        return result;
    }

    private Entity<?> retainPrevious(
            Entity<?> previous,
            ModelMetadata.RootConfiguration model) {
        if (previous == null || !model.cached()
            || !model.eventSourced() || model.cachingDepth() == 0) {
            return null;
        }
        if (model.cachingDepth() < 0) {
            return previous;
        }
        return truncatePrevious(previous, model.cachingDepth() - 1);
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
        return root.previous() == previous
                ? root : root.withPrevious((Entity) previous);
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
        String collection = annotation.searchable()
                ? Optional.of(annotation.collection())
                        .filter(value -> !value.isEmpty())
                        .map(ApplicationProperties::substituteProperties)
                        .orElse(modelType.getSimpleName())
                : metadata.participatesInGraphComposition()
                        ? ModelDocumentMutation
                                .GRAPH_COMPONENT_COLLECTION
                        : Optional.of(annotation.collection())
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
                                    ModelEventBatchLoader.Boundary.CURRENT)
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
        if (storedId == null || !Objects.equals(modelId, metadata.repositoryId(storedId))) {
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
        private final ConcurrentMap<PayloadKey, List<DeserializingMessage>>
                deserializedEvents = new ConcurrentHashMap<>();
        private final ConcurrentMap<PreparedReplayKey, List<PreparedReplay>>
                preparedReplays = new ConcurrentHashMap<>();
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
                    targets,
                    ModelEventBatchLoader.Boundary.at(maxStateIndex),
                    maxStateIndex == null);
        }

        ReconstructionBatch reconstruct(
                List<ModelTargetResolver.ResolvedModel> targets,
                ModelEventBatchLoader.Boundary boundary) {
            return reconstruct(
                    targets, boundary, !boundary.historical());
        }

        ReconstructionBatch reconstruct(
                List<ModelTargetResolver.ResolvedModel> targets,
            ModelEventBatchLoader.Boundary boundary,
            boolean cacheAtBoundary) {
            long started = System.nanoTime();
            if (targets.isEmpty()) {
                long stateBoundary = eventLoader.load(
                        Map.of(), boundary,
                        ignored -> {
                        }).stateIndex();
                return new ReconstructionBatch(stateBoundary, Map.of());
            }
            LinkedHashMap<String, MutableReconstruction> states =
                    new LinkedHashMap<>();
            LinkedHashMap<String, Long> cursors = new LinkedHashMap<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                Entity<?> base = reconstructionBase(
                        target, boundary.stateIndex(),
                        boundary.commitId() == null
                        && boundary.eventIndex() == null);
                states.put(
                        target.modelId(),
                        new MutableReconstruction(target, base));
                cursors.put(
                        target.modelId(),
                        base == null ? -1L : base.sequenceNumber());
            }
            ModelEventBatchLoader.LoadResult loaded =
                    eventLoader.loadForReconstruction(
                            cursors, boundary,
                            page -> applyPage(
                                    page, states),
                            page -> applyCompactPage(
                                    page, states));
            long loadedAt = System.nanoTime();
            List<FinalizedReconstruction> finalized =
                    targets.stream()
                            .map(target -> {
                                ModelHeadState head =
                                        loaded.heads()
                                                .get(target.modelId());
                                MutableReconstruction state =
                                        states.get(target.modelId());
                                Entity<?> entity;
                                if (head == null) {
                                    entity = empty(target);
                                } else {
                                    entity = withHead(
                                            state.current, head);
                                }
                                ModelTargetResolver.ResolvedModel resolvedTarget =
                                        state.target;
                                validateReconstruction(
                                        resolvedTarget, head, entity);
                                boolean cacheable =
                                        cacheAtBoundary
                                        && head != null
                                        && ModelMetadata.of(
                                                        resolvedTarget.modelType())
                                                .model()
                                                .orElseThrow()
                                                .cached()
                                        && head.isHistoryComplete();
                                if (head == null && !cacheable) {
                                    modelCache.remove(
                                            target.modelId());
                                }
                                return new FinalizedReconstruction(
                                        target, resolvedTarget,
                                        entity, cacheable);
                            })
                            .toList();
            LinkedHashMap<String, Entity<?>> cacheCandidates =
                    new LinkedHashMap<>();
            finalized.stream()
                    .filter(FinalizedReconstruction::cacheable)
                    .forEach(value ->
                                     cacheCandidates.put(
                                             value.resolvedTarget().modelId(),
                                             value.entity()));
            modelCache.mergeAll(
                    cacheCandidates,
                    (current, candidate) ->
                            current != null
                            && stateIndex(current)
                               >= stateIndex(candidate)
                                    ? current
                                    : candidate);
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            for (FinalizedReconstruction value : finalized) {
                ModelTargetResolver.ResolvedModel target =
                        value.target();
                Entity<?> entity = value.entity();
                result.put(target.modelId(), entity);
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), loaded.stateIndex(),
                        null, Integer.MAX_VALUE, loaded.stateIndex()), entity);
            }
            if (Boolean.getBoolean("fluxzero.modelReconstructionDiagnostics")
                && targets.size() >= 1_000) {
                System.out.printf(
                        "Model reconstruction total: %,d targets, load/apply %.3f ms, finalize %.3f ms%n",
                        targets.size(),
                        (loadedAt - started) / 1_000_000.0,
                        (System.nanoTime() - loadedAt) / 1_000_000.0);
            }
            return new ReconstructionBatch(loaded.stateIndex(), result);
        }

        private record FinalizedReconstruction(
                ModelTargetResolver.ResolvedModel target,
                ModelTargetResolver.ResolvedModel resolvedTarget,
                Entity<?> entity,
                boolean cacheable) {
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
            long started = System.nanoTime();
            page.getStreams().forEach(
                    stream -> resolveTarget(
                            stream.getModelId(), stream.getHead(), states));
            PayloadLookup payloads =
                    PayloadLookup.from(page.getPayloads());
            boolean independent =
                    page.getStreams().size() >= 32
                    && page.getStreams().parallelStream()
                            .allMatch(stream ->
                                              isIndependent(
                                                      stream,
                                                      states,
                                                      payloads));
            long classified = System.nanoTime();
            if (independent) {
                page.getStreams().parallelStream()
                        .forEach(stream ->
                                         applyStream(
                                                 stream,
                                                 states,
                                                 payloads));
            } else {
                page.getStreams().forEach(stream ->
                                                   applyStream(
                                                           stream,
                                                           states,
                                                           payloads));
            }
            if (deserializedEvents.size() > 1_024) {
                deserializedEvents.clear();
            }
            preparedReplays.clear();
            if (Boolean.getBoolean("fluxzero.modelReconstructionDiagnostics")
                && page.getStreams().size() >= 1_000) {
                System.out.printf(
                        "Model reconstruction: %,d streams, independent=%s, classify %.3f ms, apply %.3f ms%n",
                        page.getStreams().size(),
                        independent,
                        (classified - started) / 1_000_000.0,
                        (System.nanoTime() - classified) / 1_000_000.0);
            }
        }

        private void applyCompactPage(
                ModelEventBatchLoader.CompactPage page,
                Map<String, MutableReconstruction> states) {
            long started = System.nanoTime();
            page.streams().forEach(
                    stream -> resolveTarget(
                            stream.modelId(), stream.head(), states));
            boolean independent =
                    page.streams().size() >= 32
                    && page.streams().parallelStream()
                            .allMatch(stream ->
                                              isIndependent(
                                                      stream,
                                                      states));
            long classified = System.nanoTime();
            if (independent) {
                page.streams().parallelStream()
                        .forEach(stream ->
                                         applyCompactStream(
                                                 stream,
                                                 states));
            } else {
                page.streams().forEach(stream ->
                                                   applyCompactStream(
                                                           stream,
                                                           states));
            }
            if (Boolean.getBoolean(
                        "fluxzero.modelReconstructionDiagnostics")
                && page.streams().size() >= 1_000) {
                System.out.printf(
                        "Compact model reconstruction: %,d streams, independent=%s, classify %.3f ms, apply %.3f ms%n",
                        page.streams().size(),
                        independent,
                        (classified - started)
                        / 1_000_000.0,
                        (System.nanoTime() - classified)
                        / 1_000_000.0);
            }
        }

        private void resolveTarget(
                String requestedId,
                ModelHeadState head,
                Map<String, MutableReconstruction> states) {
            if (head == null
                || requestedId.equals(head.getModelId())) {
                return;
            }
            MutableReconstruction state = states.get(requestedId);
            if (state == null) {
                throw new EventSourcingException(
                        "Model alias response returned unrelated stream "
                        + requestedId);
            }
            state.resolve(head.getModelId());
        }

        private boolean isIndependent(
                ModelEventBatchLoader.CompactStream stream,
                Map<String, MutableReconstruction> states) {
            MutableReconstruction state =
                    states.get(stream.modelId());
            if (state == null) {
                throw new EventSourcingException(
                        "Model event store returned unrelated stream "
                        + stream.modelId());
            }
            for (ModelEventBatchLoader.CompactEvent compactEvent :
                    stream.events()) {
                ReplayPlan directPlan =
                        directReplayPlan(
                                compactEvent.event(),
                                state.target.modelType());
                if (directPlan != null) {
                    compactEvent.preparedReplay(
                            directPlan);
                    continue;
                }
                ModelEventMembership membership =
                        compactEvent.membership();
                StoredEvent storedEvent =
                        new StoredEvent(
                                membership,
                                compactEvent.event());
                List<PreparedReplay> prepared =
                        prepareReplay(
                                state.target,
                                membership,
                                storedEvent,
                                false);
                compactEvent.preparedReplay(prepared);
                for (PreparedReplay replay : prepared) {
                    ReplayPlan plan =
                            replay.plan();
                    ModelTargetResolver.Resolution resolution =
                            replay.resolution();
                    if (!plan.handlers().isEmpty()
                        && !plan.direct()
                        && (resolution.hasAncestorDependencies()
                            || resolution.models().stream()
                                    .anyMatch(target ->
                                                      !target.modelId()
                                                              .equals(
                                                                      stream.modelId())))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private ReplayPlan directReplayPlan(
                SerializedMessage event,
                Class<?> modelType) {
            Class<?> payloadType =
                    serializer.serializedClassWithoutUpcasting(
                            event);
            if (payloadType == null) {
                return null;
            }
            ReplayPlan plan =
                    replayPlans.computeIfAbsent(
                            new HandlerKey(
                                    payloadType,
                                    modelType),
                            ignored ->
                                    replayPlan(
                                            payloadType,
                                            modelType));
            return plan.direct() ? plan : null;
        }

        private void applyCompactStream(
                ModelEventBatchLoader.CompactStream stream,
                Map<String, MutableReconstruction> states) {
            MutableReconstruction state =
                    states.get(stream.modelId());
            if (state == null) {
                throw new EventSourcingException(
                        "Model event store returned unrelated stream "
                        + stream.modelId());
            }
            if (stream.head() != null
                && !stream.head().isHistoryComplete()) {
                throw incompleteHistory(
                        stream.modelId());
            }
            for (ModelEventBatchLoader.CompactEvent compactEvent :
                    stream.events()) {
                StoredEvent storedEvent =
                        new StoredEvent(
                                compactEvent.membership(),
                                compactEvent.event());
                Object preparedReplay =
                        compactEvent.preparedReplay();
                if (preparedReplay instanceof ReplayPlan directPlan) {
                    state.applyDirect(
                            storedEvent,
                            directPlan);
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<PreparedReplay> prepared =
                        (List<PreparedReplay>)
                                preparedReplay;
                if (prepared == null) {
                    prepared =
                            prepareReplay(
                                    state.target,
                                    compactEvent.membership(),
                                    storedEvent,
                                    false);
                }
                state.apply(
                        storedEvent,
                        prepared);
            }
        }

        private boolean isIndependent(
                ModelEventStream stream,
                Map<String, MutableReconstruction> states,
                PayloadLookup payloads) {
            MutableReconstruction state = states.get(stream.getModelId());
            if (state == null) {
                throw new EventSourcingException(
                        "Model event store returned unrelated stream "
                        + stream.getModelId());
            }
            for (ModelEventMembership membership : stream.getMemberships()) {
                StoredEvent storedEvent = new StoredEvent(
                        membership,
                        payloads.getRequired(
                                membership.getStateIndex()));
                ReplayPlan directPlan =
                        directReplayPlan(
                                storedEvent.event(),
                                state.target.modelType());
                if (directPlan != null) {
                    continue;
                }
                List<PreparedReplay> prepared =
                        new ArrayList<>();
                for (DeserializingMessage event :
                        deserialize(
                                state.target.modelType(),
                                membership,
                                storedEvent)) {
                    Class<?> payloadType = event.getPayloadClass();
                    ReplayPlan plan = replayPlans.computeIfAbsent(
                            new HandlerKey(
                                    payloadType,
                                    state.target.modelType()),
                            ignored ->
                                    replayPlan(
                                            payloadType,
                                            state.target.modelType()));
                    if (plan.handlers().isEmpty()
                        || plan.direct()) {
                        prepared.add(
                                new PreparedReplay(
                                        event, plan, null));
                        continue;
                    }
                    Object payload = event.getPayload();
                    ModelTargetResolver.Resolution resolution =
                            plan.targets().resolve(payload);
                    if (resolution.hasAncestorDependencies()
                        || resolution.models().stream()
                                .anyMatch(target ->
                                                  !target.modelId()
                                                          .equals(
                                                          stream.getModelId()))) {
                        return false;
                    }
                    prepared.add(
                            new PreparedReplay(
                                    event, plan, resolution));
                }
                preparedReplays.put(
                        new PreparedReplayKey(
                                membership.getStateIndex(),
                                state.target.modelType()),
                        List.copyOf(prepared));
            }
            return true;
        }

        private void applyStream(
                ModelEventStream stream,
                Map<String, MutableReconstruction> states,
                PayloadLookup payloads) {
            MutableReconstruction state = states.get(stream.getModelId());
            if (state == null) {
                throw new EventSourcingException(
                        "Model event store returned unrelated stream "
                        + stream.getModelId());
            }
            if (stream.getHead() != null
                && !stream.getHead().isHistoryComplete()) {
                throw incompleteHistory(stream.getModelId());
            }
            for (ModelEventMembership membership : stream.getMemberships()) {
                StoredEvent storedEvent = new StoredEvent(
                        membership,
                        payloads.getRequired(
                                membership.getStateIndex()));
                ReplayPlan directPlan =
                        directReplayPlan(
                                storedEvent.event(),
                                state.target.modelType());
                if (directPlan == null) {
                    state.apply(storedEvent);
                } else {
                    state.applyDirect(
                            storedEvent,
                            directPlan);
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
            List<ModelTargetResolver.ResolvedModel> missing = new ArrayList<>();
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
            ReconstructionBatch loaded =
                    reconstruct(
                            missing,
                            ModelEventBatchLoader.Boundary.at(stateIndex),
                            false);
            if (loaded.stateIndex() != stateIndex) {
                throw new EventSourcingException(
                        "Historical model load moved from state index %d to %d"
                                .formatted(stateIndex, loaded.stateIndex()));
            }
            result.putAll(loaded.entities());
            return ordered(targets, result);
        }

        private Entity<?> reconstructView(
                ModelTargetResolver.ResolvedModel target,
                long readStateIndex,
                String commitId,
                int substep,
                long commitStateIndex) {
            return reconstructViews(
                    List.of(target), readStateIndex, commitId,
                    substep, commitStateIndex).get(target.modelId());
        }

        private Map<String, Entity<?>> reconstructViews(
                List<ModelTargetResolver.ResolvedModel> targets,
                long readStateIndex,
                String commitId,
                int substep,
                long commitStateIndex) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            List<ModelTargetResolver.ResolvedModel> missing =
                    new ArrayList<>();
            for (ModelTargetResolver.ResolvedModel target : targets) {
                ViewKey key = new ViewKey(
                        target.modelId(), target.modelType(), readStateIndex,
                        commitId, substep, commitStateIndex);
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
                        cursors,
                        ModelEventBatchLoader.Boundary.commit(
                                commitId, substep - 1),
                        page -> applyCommitPrefix(
                                page, missing, base, readStateIndex,
                                commitId, substep));
            }
            for (ModelTargetResolver.ResolvedModel target : missing) {
                Entity<?> entity = base.get(target.modelId());
                reconstructed.put(new ViewKey(
                        target.modelId(), target.modelType(), readStateIndex,
                        commitId, substep, commitStateIndex), entity);
                result.put(target.modelId(), entity);
            }
            return ordered(targets, result);
        }

        private Map<String, Entity<?>> ordered(
                List<ModelTargetResolver.ResolvedModel> targets,
                Map<String, Entity<?>> values) {
            LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
            targets.forEach(target -> result.put(
                    target.modelId(), values.get(target.modelId())));
            return result;
        }

        private void applyCommitPrefix(
                GetModelEventsResult page,
                List<ModelTargetResolver.ResolvedModel> targets,
                Map<String, Entity<?>> current,
                long readStateIndex,
                String commitId,
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
                        && membership.getCommitId().equals(commitId)
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
            private ModelTargetResolver.ResolvedModel target;
            private final Entity<?> base;
            private Entity<?> current;
            private ModelEventMembership previous;

            private MutableReconstruction(
                    ModelTargetResolver.ResolvedModel target, Entity<?> base) {
                this.target = target;
                this.base = base;
                this.current = base == null ? empty(target) : base;
            }

            private void resolve(String modelId) {
                if (target.modelId().equals(modelId)) {
                    return;
                }
                if (previous != null
                    || base != null && base.isPresent()) {
                    throw new EventSourcingException(
                            "Model alias '%s' resolved after reconstruction started"
                                    .formatted(target.modelId()));
                }
                target = new ModelTargetResolver.ResolvedModel(
                        modelId,
                        target.modelType(),
                        target.access(),
                        target.sourceProperties());
                current = empty(target);
            }

            private void apply(StoredEvent storedEvent) {
                apply(
                        storedEvent,
                        null);
            }

            private void apply(
                    StoredEvent storedEvent,
                    List<PreparedReplay> prepared) {
                ModelEventMembership membership = storedEvent.membership();
                boolean followsCurrent = previous == null
                        ? base == null
                          || membership.getReadStateIndex() >= stateIndex(base)
                        : membership.getReadStateIndex() >= previous.getStateIndex()
                          || DefaultModelRepository.sameEarlierCommit(
                                  previous,
                                  membership);
                Entity<?> begin = followsCurrent
                        ? current
                        : reconstructView(
                                target, membership.getReadStateIndex(),
                                membership.getCommitId(), membership.getSubstep(),
                                membership.getStateIndex());
                current = prepared == null
                        ? ReconstructionSession.this.apply(
                                target, begin, storedEvent)
                        : ReconstructionSession.this.apply(
                                target, begin, storedEvent,
                                prepared);
                previous = membership;
                rememberCheckpoint(target, current);
            }

            private void applyDirect(
                    StoredEvent storedEvent,
                    ReplayPlan plan) {
                ModelEventMembership membership =
                        storedEvent.membership();
                boolean followsCurrent =
                        previous == null
                                ? base == null
                                  || membership.getReadStateIndex()
                                     >= stateIndex(base)
                                : membership.getReadStateIndex()
                                  >= previous.getStateIndex()
                                  || DefaultModelRepository.sameEarlierCommit(
                                          previous,
                                          membership);
                Entity<?> begin =
                        followsCurrent
                                ? current
                                : reconstructView(
                                        target,
                                        membership.getReadStateIndex(),
                                        membership.getCommitId(),
                                        membership.getSubstep(),
                                        membership.getStateIndex());
                DeserializingMessage event =
                        serializer.deserializeFirstMessageOrNull(
                                storedEvent.event(),
                                EVENT,
                                null);
                if (event == null) {
                    throw new EventSourcingException(
                            "Stored model event at state %d was unexpectedly dropped"
                                    .formatted(
                                            membership
                                                    .getStateIndex()));
                }
                Object value =
                        replayDirectValue(
                                target,
                                begin,
                                membership.getStateIndex(),
                                event,
                                plan.handlers()
                                        .getFirst());
                current =
                        withMembershipValue(
                                begin,
                                value,
                                membership.getSequenceNumber(),
                                membership.getStateIndex(),
                                storedEvent.event(),
                                begin);
                previous = membership;
                rememberCheckpoint(
                        target,
                        current);
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
            List<PreparedReplay> prepared =
                    preparedReplays.get(
                            new PreparedReplayKey(
                                    membership.getStateIndex(),
                                    target.modelType()));
            if (prepared == null) {
                prepared =
                        prepareReplay(
                                target,
                                membership,
                                storedEvent,
                                true);
            }
            return apply(
                    target, begin, storedEvent,
                    prepared);
        }

        private Entity<?> apply(
                ModelTargetResolver.ResolvedModel target,
                Entity<?> begin,
                StoredEvent storedEvent,
                List<PreparedReplay> prepared) {
            ModelEventMembership membership =
                    storedEvent.membership();
            Entity<?> result = begin;
            for (PreparedReplay preparedReplay : prepared) {
                DeserializingMessage event = preparedReplay.event();
                Class<?> payloadType = event.getPayloadClass();
                if (event.getPayload() instanceof CascadedModelDeletion) {
                    result = updateValue(result, null);
                    continue;
                }
                ReplayPlan plan = preparedReplay.plan();
                List<ModelMetadata.HandlerMethod> handlers = plan.handlers();
                if (handlers.isEmpty()) {
                    if (ModelMetadata.of(target.modelType()).model().orElseThrow()
                            .ignoreUnknownEvents()) {
                        continue;
                    }
                    throw new EventSourcingException(
                            "No replay apply found for %s on model %s"
                                    .formatted(payloadType.getName(), target.modelType().getName()));
                }
                if (plan.direct()) {
                    result = replayDirect(
                            target,
                            result,
                            membership,
                            event,
                            handlers.getFirst());
                    continue;
                }
                ModelTargetResolver.Resolution resolution =
                        preparedReplay.resolution();
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
                                    membership.getCommitId(),
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
                                                                ? ModelEventBatchLoader.Boundary.at(
                                                                        relationshipBoundary)
                                                                : ModelEventBatchLoader.Boundary.commit(
                                                                        membership.getCommitId(),
                                                                        membership.getSubstep() - 1),
                                                        Map.of(), false);
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
                                                    "Historical ancestor graph for commit %s substep %d "
                                                    + "resolved invalid boundary %d (read=%d, event=%d)"
                                                            .formatted(
                                                                    membership
                                                                            .getCommitId(),
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
                                membership.getCommitId(),
                                membership.getSubstep(),
                                membership.getStateIndex());
                Map<String, Entity<?>> loaded;
                if (dependencies.isEmpty()
                    && resolution.models().size() == 1
                    && resolution.models().getFirst().modelId()
                            .equals(target.modelId())) {
                    loaded = Map.of(target.modelId(), result);
                } else {
                    loaded = new LinkedHashMap<>();
                    for (ModelTargetResolver.ResolvedModel dependency :
                            resolution.models()) {
                        Entity<?> entity =
                                dependency.modelId().equals(target.modelId())
                                        ? result
                                        : dependencyViews.get(
                                                dependency.modelId());
                        loaded.put(dependency.modelId(), entity);
                    }
                }
                ModelCommitContext context = ModelCommitContext.create(
                        membership.getReadStateIndex(), resolution, loaded);
                DeserializingMessage replayEvent =
                        new DeserializingMessage(
                                event.toMessage(),
                                EVENT,
                                null,
                                serializer);
                context.attachTo(replayEvent);
                try {
                    ModelEventReplayer.ReplayResult replay =
                            eventReplayer.replay(
                                    replayEvent,
                                    context,
                                    handlers,
                                    target.modelId());
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

        private List<PreparedReplay> prepareReplay(
                ModelTargetResolver.ResolvedModel target,
                ModelEventMembership membership,
                StoredEvent storedEvent,
                boolean sharedPayload) {
            List<DeserializingMessage> messages =
                    sharedPayload
                            ? deserialize(
                                    target.modelType(),
                                    membership,
                                    storedEvent)
                            : deserializeUncached(
                                    target.modelType(),
                                    storedEvent);
            return messages.stream()
                    .map(event -> {
                        Class<?> payloadType =
                                event.getPayloadClass();
                        ReplayPlan plan =
                                replayPlans.computeIfAbsent(
                                        new HandlerKey(
                                                payloadType,
                                                target.modelType()),
                                        ignored ->
                                                replayPlan(
                                                        payloadType,
                                                        target.modelType()));
                        return new PreparedReplay(
                                event,
                                plan,
                                plan.handlers().isEmpty()
                                || plan.direct()
                                        ? null
                                        : plan.targets()
                                                .resolve(
                                                        event.getPayload()));
                    })
                    .toList();
        }

        private Entity<?> replayDirect(
                ModelTargetResolver.ResolvedModel target,
                Entity<?> begin,
                ModelEventMembership membership,
                DeserializingMessage event,
                ModelMetadata.HandlerMethod handler) {
            return replayDirect(
                    target,
                    begin,
                    membership.getStateIndex(),
                    event,
                    handler);
        }

        private Entity<?> replayDirect(
                ModelTargetResolver.ResolvedModel target,
                Entity<?> begin,
                long stateIndex,
                DeserializingMessage event,
                ModelMetadata.HandlerMethod handler) {
            return updateValue(
                    begin,
                    replayDirectValue(
                            target,
                            begin,
                            stateIndex,
                            event,
                            handler));
        }

        private Object replayDirectValue(
                ModelTargetResolver.ResolvedModel target,
                Entity<?> begin,
                long stateIndex,
                DeserializingMessage event,
                ModelMetadata.HandlerMethod handler) {
            try {
                ModelEventReplayer.ReplayResult replay =
                        eventReplayer.replayDirect(
                                event,
                                begin,
                                handler,
                                target.modelId());
                if (replay.applied()) {
                    return replay.value();
                }
                throw new EventSourcingException(
                        "Stored membership for %s at state %d produced no target transition"
                                .formatted(
                                        target.modelId(),
                                        stateIndex));
            } catch (Throwable failure) {
                throw new EventSourcingException(
                        "Failed to apply model event at state %d to %s"
                                .formatted(
                                        stateIndex,
                                        target.modelId()),
                        failure);
            }
        }

        private List<DeserializingMessage> deserialize(
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
                            .toList());
        }

        private List<DeserializingMessage> deserializeUncached(
                Class<?> modelType,
                StoredEvent storedEvent) {
            boolean ignoreUnknown =
                    ModelMetadata.of(modelType)
                            .model().orElseThrow()
                            .ignoreUnknownEvents();
            return serializer.deserializeMessages(
                            Stream.of(
                                    storedEvent.event()),
                            EVENT,
                            ignoreUnknown
                                    ? UnknownTypeStrategy.IGNORE
                                    : UnknownTypeStrategy.FAIL)
                    .toList();
        }

        private ReplayPlan replayPlan(
                Class<?> payloadType, Class<?> modelType) {
            LinkedHashSet<ModelMetadata.HandlerMethod> result = new LinkedHashSet<>();
            ModelMetadata.of(payloadType).applyMethods().stream()
                    .filter(handler -> handler.targetModelTypes().stream()
                            .anyMatch(target -> compatible(target, modelType)))
                    .forEach(result::add);
            ModelMetadata.of(modelType).applyMethods().stream()
                    .filter(handler -> ModelMetadata.acceptsPayload(handler, payloadType))
                    .forEach(result::add);
            List<ModelMetadata.HandlerMethod> handlers = List.copyOf(result);
            return new ReplayPlan(
                    handlers,
                    ModelTargetResolver.plan(payloadType, handlers),
                    handlers.size() == 1
                    && eventReplayer.supportsDirectReplay(
                            handlers.getFirst(),
                            payloadType,
                            modelType));
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
            return withMembership(
                    entity,
                    membership.getSequenceNumber(),
                    membership.getStateIndex(),
                    storedEvent.event(),
                    previous);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withMembership(
                Entity<?> entity,
                long sequenceNumber,
                long stateIndex,
                SerializedMessage event,
                Entity<?> previous) {
            return withMembershipValue(
                    entity,
                    entity.get(),
                    sequenceNumber,
                    stateIndex,
                    event,
                    previous);
        }

        @SuppressWarnings("unchecked")
        private Entity<?> withMembershipValue(
                Entity<?> entity,
                Object value,
                long sequenceNumber,
                long stateIndex,
                SerializedMessage event,
                Entity<?> previous) {
            Model model = ModelMetadata.of(entity.type())
                    .model().orElseThrow();
            return ImmutableModelRoot.<Object>builder()
                    .id(entity.id())
                    .type((Class<Object>) entity.type())
                    .idProperty(entity.idProperty())
                    .value(value)
                    .entityHelper(entityHelper)
                    .serializer(serializer)
                    .sequenceNumber(sequenceNumber)
                    .stateIndex(stateIndex)
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

    private static boolean sameEarlierCommit(
            ModelEventMembership previous, ModelEventMembership current) {
        return previous.getCommitId().equals(current.getCommitId())
               && previous.getSubstep() < current.getSubstep();
    }

    private record ReconstructionBatch(
            long stateIndex, Map<String, Entity<?>> entities) {
    }

    private record CurrentModelContext(
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
            String commitId,
            int substep,
            long commitStateIndex) {
    }

    private record ModelKey(String modelId, Class<?> modelType) {
    }

    private record PayloadKey(long stateIndex, boolean ignoreUnknown) {
    }

    private record HandlerKey(Class<?> payloadType, Class<?> modelType) {
    }

    private record PreparedReplayKey(long stateIndex, Class<?> modelType) {
    }

    private record PreparedReplay(
            DeserializingMessage event,
            ReplayPlan plan,
            ModelTargetResolver.Resolution resolution) {
    }

    private record ReplayPlan(
            List<ModelMetadata.HandlerMethod> handlers,
            ModelTargetResolver.TargetPlan targets,
            boolean direct) {
    }

    private record PayloadLookup(
            long[] stateIndices,
            SerializedMessage[] events,
            Map<Long, SerializedMessage> unordered) {

        private static PayloadLookup from(
                List<ModelEventPayload> payloads) {
            long[] stateIndices = new long[payloads.size()];
            SerializedMessage[] events =
                    new SerializedMessage[payloads.size()];
            boolean sorted = true;
            for (int index = 0; index < payloads.size(); index++) {
                ModelEventPayload payload = payloads.get(index);
                stateIndices[index] = payload.getStateIndex();
                events[index] = payload.getEvent();
                sorted &= index == 0
                          || stateIndices[index - 1]
                             < stateIndices[index];
            }
            if (sorted) {
                return new PayloadLookup(
                        stateIndices, events, null);
            }
            Map<Long, SerializedMessage> unordered =
                    new HashMap<>(payloads.size() * 4 / 3 + 1);
            for (int index = 0; index < stateIndices.length; index++) {
                unordered.put(
                        stateIndices[index], events[index]);
            }
            return new PayloadLookup(
                    stateIndices, events, unordered);
        }

        private SerializedMessage getRequired(long stateIndex) {
            SerializedMessage event;
            if (unordered == null) {
                int index = Arrays.binarySearch(
                        stateIndices, stateIndex);
                event = index < 0 ? null : events[index];
            } else {
                event = unordered.get(stateIndex);
            }
            return Objects.requireNonNull(
                    event, "Missing validated model payload");
        }
    }

    private record ReplayAncestorKey(
            ModelTargetResolver.Resolution resolution,
            long relationshipBoundary,
            String commitId,
            int substep) {
    }

    private static final class ModelEventStateBoundary {
        private final String sourceCommitId;
        private final Integer sourceSubstep;
        private final Long sourceEventIndex;
        private Long stateIndex;

        private ModelEventStateBoundary(
                String sourceCommitId,
                Integer sourceSubstep,
                Long sourceEventIndex) {
            this.sourceCommitId = sourceCommitId;
            this.sourceSubstep = sourceSubstep;
            this.sourceEventIndex = sourceEventIndex;
        }

        private static ModelEventStateBoundary commit(
                String commitId, int substep) {
            return new ModelEventStateBoundary(
                    commitId, substep, null);
        }

        private static ModelEventStateBoundary event(
                long eventIndex) {
            return new ModelEventStateBoundary(
                    null, null, eventIndex);
        }

        private synchronized ModelEventBatchLoader.Boundary request() {
            if (stateIndex != null) {
                return ModelEventBatchLoader.Boundary.at(
                        stateIndex);
            }
            return sourceEventIndex == null
                    ? ModelEventBatchLoader.Boundary.commit(
                            sourceCommitId, sourceSubstep)
                    : ModelEventBatchLoader.Boundary.event(
                            sourceEventIndex);
        }

        private synchronized void pin(long value) {
            if (stateIndex != null && stateIndex != value) {
                throw new EventSourcingException(
                        "Published model boundary %s resolved to both state %d and %d"
                                .formatted(
                                        description(),
                                        stateIndex, value));
            }
            stateIndex = value;
        }

        private String description() {
            return sourceEventIndex == null
                    ? "commit %s substep %d".formatted(
                            sourceCommitId, sourceSubstep)
                    : "event %d".formatted(sourceEventIndex);
        }
    }

    /**
     * A cached model value and the inclusive global state boundary through which it is known to be current.
     */
    public record CurrentModel(Entity<?> entity, long stateIndex) {
    }

    /** Receives a cache value and the boundaries that prove it current. */
    @FunctionalInterface
    public interface CurrentModelSink {
        void accept(
                Entity<?> entity,
                long validThrough,
                long modelStateIndex);
    }

    /** A pre-resolved direct-model cache lookup that receives the proven current value when present. */
    public interface CurrentModelLookup
            extends CurrentModelSink {
        String modelId();

        Class<?> modelType();
    }

    /**
     * Final authoritative state and positions for a locally committed model.
     */
    public static final class CommittedModel {
        private final String modelId;
        private final Class<?> modelType;
        private final ModelMetadata.Property entityId;
        private final ModelMetadata.RootConfiguration model;
        private final boolean valueIdsValidated;
        private final boolean historyComplete;
        private final CommittedRevision singleRevision;
        private final List<CommittedRevision> revisions;

        public CommittedModel(
                String modelId,
                Class<?> modelType,
                boolean historyComplete,
                CommittedRevision revision) {
            this(
                    modelId, modelType,
                    ModelMetadata.validate(modelType),
                    historyComplete, revision);
        }

        private CommittedModel(
                String modelId,
                Class<?> modelType,
                ModelMetadata metadata,
                boolean historyComplete,
                CommittedRevision revision) {
            this(
                    modelId, modelType,
                    metadata.rootConfiguration().orElseThrow(),
                    metadata.entityId().orElseThrow(),
                    false, historyComplete, revision);
        }

        /**
         * Creates a committed model using model descriptors already validated by the commit planner.
         */
        public CommittedModel(
                String modelId,
                Class<?> modelType,
                ModelMetadata.RootConfiguration model,
                ModelMetadata.Property entityId,
                boolean historyComplete,
                CommittedRevision revision) {
            this(
                    modelId, modelType, model, entityId,
                    true, historyComplete, revision);
        }

        public CommittedModel(
                String modelId,
                Class<?> modelType,
                boolean historyComplete,
                List<CommittedRevision> revisions) {
            this(
                    modelId, modelType,
                    ModelMetadata.validate(modelType),
                    historyComplete, revisions);
        }

        private CommittedModel(
                String modelId,
                Class<?> modelType,
                ModelMetadata metadata,
                boolean historyComplete,
                List<CommittedRevision> revisions) {
            this(
                    modelId, modelType,
                    metadata.rootConfiguration().orElseThrow(),
                    metadata.entityId().orElseThrow(),
                    false, historyComplete, revisions);
        }

        /**
         * Creates a committed model using model descriptors already validated by the commit planner.
         */
        public CommittedModel(
                String modelId,
                Class<?> modelType,
                ModelMetadata.RootConfiguration model,
                ModelMetadata.Property entityId,
                boolean historyComplete,
                List<CommittedRevision> revisions) {
            this(
                    modelId, modelType, model, entityId,
                    true, historyComplete, revisions);
        }

        private CommittedModel(
                String modelId,
                Class<?> modelType,
                ModelMetadata.RootConfiguration model,
                ModelMetadata.Property entityId,
                boolean valueIdsValidated,
                boolean historyComplete,
                CommittedRevision revision) {
            this.modelId = modelId;
            this.modelType = modelType;
            this.model = Objects.requireNonNull(model, "model");
            this.entityId = Objects.requireNonNull(entityId, "entityId");
            this.valueIdsValidated = valueIdsValidated;
            this.historyComplete = historyComplete;
            this.singleRevision = Objects.requireNonNull(
                    revision, "revision");
            this.revisions = null;
        }

        private CommittedModel(
                String modelId,
                Class<?> modelType,
                ModelMetadata.RootConfiguration model,
                ModelMetadata.Property entityId,
                boolean valueIdsValidated,
                boolean historyComplete,
                List<CommittedRevision> revisions) {
            List<CommittedRevision> copy =
                    List.copyOf(revisions);
            if (copy.isEmpty()) {
                throw new IllegalArgumentException(
                        "A committed model must contain at least one revision");
            }
            this.modelId = modelId;
            this.modelType = modelType;
            this.model = Objects.requireNonNull(model, "model");
            this.entityId = Objects.requireNonNull(entityId, "entityId");
            this.valueIdsValidated = valueIdsValidated;
            this.historyComplete = historyComplete;
            this.singleRevision = copy.size() == 1
                    ? copy.getFirst() : null;
            this.revisions = copy.size() == 1
                    ? null : copy;
        }

        public String modelId() {
            return modelId;
        }

        public Class<?> modelType() {
            return modelType;
        }

        private ModelMetadata.Property entityId() {
            return entityId;
        }

        private ModelMetadata.RootConfiguration model() {
            return model;
        }

        private boolean valueIdsValidated() {
            return valueIdsValidated;
        }

        public boolean historyComplete() {
            return historyComplete;
        }

        public List<CommittedRevision> revisions() {
            return revisions == null
                    ? List.of(singleRevision) : revisions;
        }

        public long stateIndex() {
            return revision(revisionCount() - 1)
                    .stateIndex();
        }

        private int revisionCount() {
            return revisions == null ? 1 : revisions.size();
        }

        private CommittedRevision revision(int index) {
            return revisions == null
                    ? singleRevision : revisions.get(index);
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
