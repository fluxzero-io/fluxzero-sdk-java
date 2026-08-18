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
import io.fluxzero.common.api.modeling.GetModelGraphBefore;
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
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelCommitContext;
import io.fluxzero.sdk.modeling.ModelExecutionPlan;
import io.fluxzero.sdk.modeling.ModelGraphProjections;
import io.fluxzero.sdk.modeling.ModelBatchScope;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.modeling.ModelTargetResolver;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import lombok.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
public class DefaultModelRepository extends AbstractNamespaced<ModelRepository>
        implements ModelRepository, ModelAncestorResolver {
    private static final int COMMIT_ANCESTOR_MAX_DEPTH = 64;
    private static final int COMMIT_ANCESTOR_MAX_MODELS = 10_000;
    private static final int COMMITTED_CACHE_UPDATE_BATCH_SIZE = 128;

    private final Client client;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final EntityHelper entityHelper;
    private final ModelExecutionPlan.Compiler modelExecution;
    private final ModelReplayCursor eventLoader;
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
             (ModelExecutionPlan.Compiler) null);
    }

    public DefaultModelRepository(
            Client client,
            DocumentStore documentStore,
            Serializer serializer,
            EntityHelper entityHelper,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        this(client, documentStore, serializer, entityHelper, serializer, NoOpCache.INSTANCE,
             parameterResolvers == null ? null : new ModelExecutionPlan.Compiler(parameterResolvers));
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
             parameterResolvers == null ? null : new ModelExecutionPlan.Compiler(parameterResolvers));
    }

    private DefaultModelRepository(
            Client client,
            DocumentStore documentStore,
            Serializer serializer,
            EntityHelper entityHelper,
            Serializer snapshotSerializer,
            Cache cache,
            ModelExecutionPlan.Compiler modelExecution) {
        this.client = Objects.requireNonNull(client, "client");
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
        this.serializer = serializer;
        this.entityHelper = entityHelper;
        this.snapshotSerializer = snapshotSerializer;
        this.modelExecution = modelExecution;
        this.cacheSource = Objects.requireNonNull(cache, "cache");
        this.modelCache = cache == NoOpCache.INSTANCE
                ? cache : new RepositoryCache(cache, "$Model", client.namespace());
        this.snapshotStore = snapshotSerializer == null
                ? null : new ModelSnapshotStore(documentStore, snapshotSerializer);
        this.eventLoader = client.getEventStoreClient() == null
                ? null : new ModelReplayCursor(
                        client.getEventStoreClient(), serializer, entityHelper,
                        modelExecution, modelCache, snapshotStore,
                        this::resolveReplayAncestors);
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
                snapshotSerializer, cacheSource, modelExecution);
    }

    /** Returns the model evaluator shared by live commits and stored-event replay. */
    public ModelExecutionPlan.Compiler modelExecution() {
        return modelExecution;
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
        return ModelBatchScope.overlayCurrent(
                messageBatchNamespace(), modelId, modelType,
                loadDurable(modelId, modelType));
    }

    private <T> Entity<T> loadDurable(
            String modelId,
            Class<T> modelType) {
        ModelReadBoundary.Pinned handlerBoundary =
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
        ModelReadBoundary.Pinned handlerBoundary = handlerBoundary();
        ModelCommitContext context = loadContext(
                new ModelTargetResolver.Resolution(targets, List.of()),
                boundary(handlerBoundary),
                Map.of(), false);
        context = ModelBatchScope.overlayCurrent(
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
        ModelReadBoundary.Pinned handlerBoundary =
                handlerBoundary();
        return readGraph(
                rootId, rootType, options, boundary(handlerBoundary),
                handlerBoundary, true, false);
    }

    @Override
    public <A> Optional<Graph<A>> loadAncestorGraph(
            String modelId,
            Class<?> modelType,
            Class<A> ancestorType,
            ModelReadBoundary boundary) {
        return loadAncestorGraphs(
                modelId, modelType, ancestorType,
                boundary, false).stream().findFirst();
    }

    @Override
    public <A> List<Graph<A>> loadAncestorGraphs(
            String modelId,
            Class<?> modelType,
            Class<A> ancestorType,
            ModelReadBoundary boundary) {
        return loadAncestorGraphs(
                modelId, modelType, ancestorType,
                boundary, true);
    }

    private <A> List<Graph<A>> loadAncestorGraphs(
            String modelId,
            Class<?> modelType,
            Class<A> ancestorType,
            ModelReadBoundary boundary,
            boolean all) {
        requireEventReconstruction();
        ModelMetadata sourceMetadata = ModelMetadata.validate(modelType);
        ModelTargetResolver.ResolvedModel source =
                new ModelTargetResolver.ResolvedModel(
                        modelId, modelType,
                        ModelTargetResolver.Access.READ_ONLY,
                        List.of(sourceMetadata.entityId()
                                        .orElseThrow().name()));
        ModelTargetResolver.Resolution request =
                new ModelTargetResolver.Resolution(
                        List.of(source), List.of(),
                        List.of(new ModelTargetResolver.AncestorDependency(
                                ancestorType, null,
                                "Graph.ancestor(%s)".formatted(
                                        ancestorType.getName()))));
        Map<String, Object> stagedValues;
        if (!boundary.includeMessageBatch()) {
            stagedValues = Map.of();
        } else {
            Map<String, ModelBatchScope.StagedModel> staged =
                    ModelBatchScope.currentValues(
                            messageBatchNamespace());
            if (staged.isEmpty()) {
                stagedValues = Map.of();
            } else {
                LinkedHashMap<String, Object> values =
                        new LinkedHashMap<>(staged.size());
                staged.forEach((id, value) ->
                        values.put(id, value.value()));
                stagedValues = values;
            }
        }
        AncestorResolution resolved = resolveAncestors(
                request,
                ancestorBoundary(boundary),
                stagedValues, boundary.includeMessageBatch(),
                false, !all, all,
                UNBOUNDED, UNBOUNDED);
        List<ModelTargetResolver.ResolvedModel> targets =
                resolved.resolution().models().stream()
                        .filter(candidate ->
                                !candidate.modelId().equals(modelId))
                        .filter(candidate ->
                                ancestorType.isAssignableFrom(
                                        candidate.modelType()))
                        .toList();
        Graph.Options rootOnly = new Graph.Options(0, 1);
        return targets.stream().map(target -> {
            @SuppressWarnings("unchecked") Class<A> targetType =
                    (Class<A>) target.modelType();
            if (boundary.commitId() != null) {
                return loadGraphAtCommit(
                        target.modelId(), targetType,
                        resolved.stateIndex(), boundary.commitId(),
                        boundary.substep(), rootOnly);
            }
            if (boundary.eventIndex() != null) {
                return loadGraphAtEvent(
                        target.modelId(), targetType,
                        resolved.stateIndex(), boundary.eventIndex(),
                        rootOnly);
            }
            if (boundary.includeMessageBatch()) {
                return loadGraphAtIncludingMessageBatch(
                        target.modelId(), targetType,
                        resolved.stateIndex(), rootOnly);
            }
            return loadGraphAt(
                    target.modelId(), targetType,
                    resolved.stateIndex(), rootOnly);
        }).toList();
    }

    private static ModelReadBoundary ancestorBoundary(
            ModelReadBoundary boundary) {
        if (boundary.commitId() != null) {
            return ModelReadBoundary.commit(
                    boundary.commitId(), boundary.substep());
        }
        if (boundary.eventIndex() != null) {
            return ModelReadBoundary.event(
                    boundary.eventIndex());
        }
        return ModelReadBoundary.at(
                boundary.stateIndex());
    }

    @Override
    public <T> Graph<T> loadGraphAt(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long stateIndex,
            @NonNull Graph.Options options) {
        return readGraph(rootId, rootType, options,
                         ModelReadBoundary.state(stateIndex, false), null, false, true);
    }

    @Override
    public <T> Graph<T> loadGraphAtCommit(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long resolvedStateIndex,
            @NonNull String commitId,
            int substep,
            @NonNull Graph.Options options) {
        return readGraph(rootId, rootType, options,
                         ModelReadBoundary.commit(commitId, substep).resolved(resolvedStateIndex),
                         null, false, true);
    }

    @Override
    public <T> Graph<T> loadGraphAtEvent(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long resolvedStateIndex,
            long eventIndex,
            @NonNull Graph.Options options) {
        return readGraph(rootId, rootType, options,
                         ModelReadBoundary.event(eventIndex).resolved(resolvedStateIndex),
                         null, false, true);
    }

    @Override
    public <T> Graph<T> loadGraphBefore(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long stateIndex,
            @NonNull Graph.Options options) {
        return readGraph(rootId, rootType, options,
                         ModelReadBoundary.state(stateIndex, false).asBefore(),
                         null, false, true);
    }

    @Override
    public <T> Graph<T> loadGraphBeforeCommit(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long resolvedStateIndex,
            @NonNull String commitId,
            int substep,
            @NonNull Graph.Options options) {
        return readGraph(rootId, rootType, options,
                         ModelReadBoundary.commit(commitId, substep)
                                 .resolved(resolvedStateIndex).asBefore(),
                         null, false, true);
    }

    @Override
    public <T> Graph<T> loadGraphBeforeEvent(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long resolvedStateIndex,
            long eventIndex,
            @NonNull Graph.Options options) {
        return readGraph(rootId, rootType, options,
                         ModelReadBoundary.event(eventIndex)
                                 .resolved(resolvedStateIndex).asBefore(),
                         null, false, true);
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
        return readGraph(rootId, rootType, options,
                         ModelReadBoundary.state(stateIndex, true), null, true, true);
    }

    private <T> Graph<T> readGraph(
            String rootId,
            Class<T> rootType,
            Graph.Options options,
            ModelReadBoundary boundary,
            ModelReadBoundary.Pinned handlerBoundary,
            boolean includeMessageBatch,
            boolean historical) {
        requireEventReconstruction();
        ModelMetadata.validate(rootType);
        GetModelGraph request = graphRequest(rootId, boundary, options);
        GetModelGraphResult graph = boundary.before()
                ? client.getEventStoreClient().getModelGraphBefore(new GetModelGraphBefore(request))
                : client.getEventStoreClient().getModelGraph(request);
        pin(handlerBoundary, graph.getStateIndex());
        Map<String, ModelBatchScope.StagedModel> staged =
                includeMessageBatch
                        ? ModelBatchScope.currentValues(
                                messageBatchNamespace())
                        : Map.of();
        ModelBatchScope.StagedModel stagedRoot =
                staged.get(rootId);
        Graph<T> durable = reconstructGraphResponse(
                graph, rootId, rootType, boundary, boundary.before(),
                stagedRoot != null && !stagedRoot.existedBefore()
                        ? stagedRoot : null,
                historical);
        return includeMessageBatch
                ? Graphs.overlayMessageBatch(
                        durable, messageBatchNamespace(), rootType, options, staged,
                        candidate -> readGraph(
                                candidate.modelId(), (Class) candidate.modelType(),
                                Graph.Options.DEFAULT, ModelReadBoundary.at(graph.getStateIndex()),
                                null, false, true))
                : durable;
    }

    private static GetModelGraph graphRequest(
            String rootId,
            ModelReadBoundary boundary,
            Graph.Options options) {
        return new GetModelGraph(
                rootId, boundary.requestStateIndex(),
                boundary.commitId(), boundary.substep(),
                boundary.eventIndex(), options.maxDepth(),
                options.maxModels(), 0, 0L, false);
    }

    private <T> Graph<T>
            reconstructGraphResponse(
                    GetModelGraphResult graph,
                    String rootId,
                    Class<T> rootType,
                    ModelReadBoundary boundary,
                    boolean beforeBoundary,
                    ModelBatchScope.StagedModel missingRoot,
                    boolean historical) {
        List<ModelTargetResolver.ResolvedModel> targets =
                new ArrayList<>(graph.getStreams().size());
        LinkedHashMap<String, ModelHeadState> heads =
                new LinkedHashMap<>();
        for (ModelEventStream stream : graph.getStreams()) {
            Class<?> modelType = graphModelType(
                    stream, rootId, rootType);
            targets.add(new ModelTargetResolver.ResolvedModel(
                    stream.getModelId(), modelType,
                    ModelTargetResolver.Access.READ_ONLY,
                    List.of(ModelMetadata.validate(modelType)
                                    .entityId().orElseThrow().name())));
            heads.put(stream.getModelId(), stream.getHead());
        }
        List<ModelTargetResolver.ResolvedModel> eventTargets =
                new ArrayList<>();
        List<ModelTargetResolver.ResolvedModel> documentTargets =
                new ArrayList<>();
        for (ModelTargetResolver.ResolvedModel target :
                targets) {
            Model model = ModelMetadata.validate(
                            target.modelType())
                    .model().orElseThrow();
            (model.eventSourced()
                    ? eventTargets
                    : documentTargets).add(target);
        }
        LinkedHashMap<String, Entity<?>> reconstructedModels =
                new LinkedHashMap<>();
        if (!eventTargets.isEmpty()) {
            ModelReplayCursor.ReconstructionBatch reconstructed =
                    eventLoader.session().reconstruct(
                            eventTargets,
                            ModelReadBoundary.at(graph.getStateIndex()),
                            !boundary.historical());
            if (reconstructed.stateIndex()
                != graph.getStateIndex()) {
                throw new EventSourcingException(
                        "Model graph moved from state index %d to %d during reconstruction"
                                .formatted(
                                        graph.getStateIndex(),
                                        reconstructed.stateIndex()));
            }
            reconstructedModels.putAll(
                    reconstructed.entities());
        }
        if (!documentTargets.isEmpty()) {
            reconstructedModels.putAll(
                    loadGraphDocumentsAtHeads(
                            documentTargets, heads));
        }
        if (missingRoot != null
            && !reconstructedModels.containsKey(rootId)) {
            reconstructedModels.put(
                    rootId,
                    ImmutableModelRoot.builder()
                            .id(rootId).type((Class) missingRoot.modelType())
                            .idProperty(ModelMetadata.validate(missingRoot.modelType())
                                                .entityId().orElseThrow().name())
                            .value(null).build());
        }
        Map<String, Entity<?>> durableModels;
        if (beforeBoundary) {
            LinkedHashMap<String, Entity<?>> before =
                    new LinkedHashMap<>();
            reconstructedModels.forEach(
                    (modelId, entity) -> before.put(
                            modelId,
                            beforeBoundary(
                                    entity,
                                    graph.getStateIndex())));
            durableModels = before;
        } else {
            durableModels = reconstructedModels;
        }
        return Graphs.compose(
                rootId, graph.getStateIndex(), durableModels,
                graph.getEdges(), this, historical);
    }

    private Map<String, Entity<?>> loadGraphDocumentsAtHeads(
            List<ModelTargetResolver.ResolvedModel> targets,
            Map<String, ModelHeadState> expectedHeads) {
        LinkedHashMap<String, Entity<?>> loaded =
                new LinkedHashMap<>();
        for (ModelTargetResolver.ResolvedModel target :
                targets) {
            ModelMetadata metadata =
                    ModelMetadata.validate(
                            target.modelType());
            Model model = metadata.model().orElseThrow();
            Entity<?> entity = loadDocumentUnchecked(
                    target.modelId(), target.modelType(),
                    metadata, model);
            ModelHeadState expected =
                    expectedHeads.get(target.modelId());
            if (expected == null) {
                if (entity.isPresent()) {
                    throw new EventSourcingException(
                            "Model graph has no head for document model "
                            + target.modelId());
                }
                loaded.put(target.modelId(), entity);
                continue;
            }
            loaded.put(
                    target.modelId(),
                    withDocumentHead(entity, expected));
        }

        Map<String, ModelHeadState> currentHeads = eventLoader.loadHeads(
                targets.stream().map(ModelTargetResolver.ResolvedModel::modelId).toList(),
                ModelReadBoundary.CURRENT).heads();
        for (ModelTargetResolver.ResolvedModel target :
                targets) {
            ModelHeadState expected = expectedHeads.get(
                    target.modelId());
            ModelHeadState actual = currentHeads.get(
                    target.modelId());
            if (!Objects.equals(expected, actual)) {
                throw new EventSourcingException(
                        "Document model '%s' moved while reconstructing graph boundary"
                                .formatted(
                                        target.modelId()));
            }
        }
        return Collections.unmodifiableMap(loaded);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity<?> withDocumentHead(
            Entity<?> entity,
            ModelHeadState head) {
        if (head.isDeleted() != entity.isEmpty()) {
            throw new EventSourcingException(
                    "Document model '%s' has document presence=%s but its head reports deletion=%s"
                            .formatted(
                                    head.getModelId(),
                                    entity.isPresent(),
                                    head.isDeleted()));
        }
        return ImmutableModelRoot.<Object>builder()
                .id(entity.id())
                .type((Class<Object>) entity.type())
                .idProperty(entity.idProperty())
                .value(entity.get())
                .entityHelper(entityHelper)
                .serializer(serializer)
                .sequenceNumber(
                        head.getSequenceNumber())
                .stateIndex(head.getStateIndex())
                .timestamp(entity.timestamp())
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity<?> beforeBoundary(
            Entity<?> entity,
            long stateIndex) {
        if (!(entity instanceof ModelRoot<?> root)
            || root.stateIndex() != stateIndex) {
            return entity;
        }
        Entity<?> previous = root.previous();
        if (previous != null) {
            return previous;
        }
        return ImmutableModelRoot.builder()
                .id(entity.id())
                .type((Class) entity.type())
                .idProperty(ModelMetadata.validate(
                                entity.type())
                                    .entityId()
                                    .orElseThrow()
                                    .name())
                .entityHelper(entityHelper)
                .serializer(serializer)
                .value(null)
                .build();
    }

    private Class<?> resolveUntypedType(
            String modelId,
            ModelReadBoundary.Pinned handlerBoundary) {
        Class<?> payloadType = resolvePayloadFactoryType(
                modelId, handlerBoundary);
        return payloadType == null
                ? resolveStoredType(modelId, handlerBoundary)
                : payloadType;
    }

    private Class<?> resolvePayloadFactoryType(
            String modelId,
            ModelReadBoundary.Pinned handlerBoundary) {
        if (client.getEventStoreClient() == null
            || serializer == null) {
            return null;
        }
        ModelReadBoundary boundary =
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
                                ModelReplayCursor.DEFAULT_SETTINGS
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
            return ModelTargetResolver.compile(
                            payload.getClass(), handlers)
                    .resolve(payload)
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
            ModelReadBoundary.Pinned handlerBoundary) {
        if (client.getEventStoreClient() == null) {
            throw new EventSourcingException(
                    "Loading an independent model by untyped ID requires model-head type metadata");
        }
        ModelReadBoundary boundary =
                boundary(handlerBoundary);
        ModelReplayCursor.LoadResult result = eventLoader.loadHeads(List.of(modelId), boundary);
        pin(handlerBoundary, result.stateIndex());
        ModelHeadState head = result.heads().get(modelId);
        if (head == null) {
            return null;
        }
        if (head.getModelType() == null) {
            throw new EventSourcingException(
                    "Model '%s' has no stored type metadata".formatted(modelId));
        }
        return classForName(serializer.upcastType(
                head.getModelType()));
    }

    private String resolveCurrentModelId(String requestedId) {
        if (client.getEventStoreClient() == null) {
            return requestedId;
        }
        ModelHeadState head = eventLoader.loadHeads(
                List.of(requestedId), ModelReadBoundary.CURRENT).heads().get(requestedId);
        return head == null
                ? requestedId
                : head.getModelId();
    }

    private Entity<Object> emptyUntyped(String modelId) {
        return ImmutableModelRoot.<Object>builder()
                .id(modelId)
                .type(Object.class)
                .entityHelper(entityHelper)
                .serializer(serializer)
                .build();
    }

    private ModelReadBoundary.Pinned handlerBoundary() {
        DeserializingMessage current =
                DeserializingMessage.getCurrent();
        if (current == null
            || current.getMessageType() != EVENT
               && current.getMessageType() != NOTIFICATION) {
            return null;
        }
        return current.computeContextIfAbsent(
                        ModelReadBoundary.Pinned.class,
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
                                return new ModelReadBoundary.Pinned(
                                        ModelReadBoundary.commit(id, parseSubstep(substep)));
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

    private static ModelReadBoundary boundary(
            ModelReadBoundary.Pinned boundary) {
        return boundary == null
                ? ModelReadBoundary.CURRENT
                : boundary.request();
    }

    private static void pin(
            ModelReadBoundary.Pinned boundary,
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

    /**
     * Loads all direct commit targets at one state boundary.
     * <p>
     * A {@code null} boundary pins the current event-store state once. Historical document-model dependencies are
     * reconstructed from stored model events; current document-model targets retain their direct-document load path.
     */
    @Override
    public ModelCommitContext loadContext(
            ModelTargetResolver.Resolution resolution) {
        ModelReadBoundary.Pinned handlerBoundary =
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
                ModelReadBoundary.at(maxStateIndex),
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
                ModelReadBoundary.at(maxStateIndex),
                stagedValues, includeMessageBatch);
        return includeMessageBatch
                ? ModelBatchScope.overlayCurrent(
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

    private ModelCommitContext loadContext(
            ModelTargetResolver.Resolution resolution,
            ModelReadBoundary boundary,
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
            boundary = ModelReadBoundary.at(ancestorStateIndex);
        }
        ModelReplayCursor.Session session = eventLoader.session();
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
                ModelReplayCursor.ReconstructionBatch batch = session.reconstruct(
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
            eventLoader.loadReplayableHeads(
                    documentDependencies,
                    ModelReadBoundary.at(stateIndex));
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
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch) {
        return resolveAncestors(
                resolution, boundary, stagedValues,
                includeMessageBatch, true, false, false,
                COMMIT_ANCESTOR_MAX_DEPTH,
                COMMIT_ANCESTOR_MAX_MODELS);
    }

    private ModelReplayCursor.AncestorResult resolveReplayAncestors(
            ModelTargetResolver.Resolution resolution,
            ModelReadBoundary boundary) {
        AncestorResolution result = resolveAncestors(
                resolution, boundary, Map.of(), false);
        return new ModelReplayCursor.AncestorResult(
                result.stateIndex(), result.resolution());
    }

    private AncestorResolution resolveAncestors(
            ModelTargetResolver.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch,
            boolean requireAncestors,
            boolean closestAncestorsOnly,
            boolean allowMultipleAncestors,
            int maxDepth,
            int maxModels) {
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
            if (maxDepth != UNBOUNDED
                && expansion > maxDepth) {
                throw new IllegalStateException(
                        "Message-batch ancestor overlay exceeds maximum depth "
                        + maxDepth);
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
                    Class<?> parentType = parent.parentModelType(parentId);
                    stagedEdges.add(new ModelGraphEdge(
                            entry.getKey(), parentIdString,
                            parentType == null ? null : parentType.getName(),
                            parent.path().isEmpty()
                                    ? null : parent.path(),
                            -1L, null));
                }
            }
            if (requestRoots.size()
                > maxModels
                && maxModels != UNBOUNDED) {
                throw new IllegalStateException(
                        "Model commit requires more than %d ancestor traversal roots"
                                .formatted(
                                        maxModels));
            }

            graph = client.getEventStoreClient().getModelAncestors(
                    new GetModelAncestors(
                            List.copyOf(requestRoots),
                            boundary.stateIndex(),
                            boundary.commitId(),
                            boundary.substep(),
                            boundary.eventIndex(),
                            maxDepth, maxModels,
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
        List<Graphs.AncestorPlacement> reachable =
                Graphs.ancestors(roots, edges, maxDepth, maxModels);
        Map<String, Graphs.AncestorPlacement> reachableById =
                reachable.stream().collect(java.util.stream.Collectors.toMap(
                        Graphs.AncestorPlacement::id, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));

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
        for (Graphs.AncestorPlacement placement : reachable) {
            String modelId = placement.id();
            Class<?> storedType = resolveAncestorType(
                    modelId, heads.get(modelId),
                    placement.incoming());
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
            List<String> candidates = reachable.stream()
                    .map(Graphs.AncestorPlacement::id)
                    .filter(modelId -> {
                        Class<?> actualType = knownTypes.get(modelId);
                        return actualType == null
                               || compatible(dependency.modelType(), actualType);
                    })
                    .filter(modelId -> dependency.association() == null
                                       || reachableById.get(modelId).incoming()
                                               .stream()
                                               .anyMatch(edge -> dependency.association()
                                                       .equals(edge.getPath())))
                    .toList();
            if (closestAncestorsOnly && candidates.size() > 1) {
                int closestDepth = candidates.stream()
                        .mapToInt(candidate -> reachableById.get(candidate).depth())
                        .min().orElseThrow();
                candidates = candidates.stream()
                        .filter(candidate -> reachableById.get(candidate).depth() == closestDepth)
                        .toList();
            }
            if (candidates.isEmpty()) {
                if (!requireAncestors) {
                    continue;
                }
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
            if (candidates.size() > 1
                && !allowMultipleAncestors) {
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
            for (String modelId : candidates) {
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
            ModelBatchScope.StagedModel pending =
                    ModelBatchScope.currentValue(
                            namespace, modelId);
            if (pending != null) {
                stagedValues.put(modelId, pending.value());
                changed = true;
            }
        }
        return changed;
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
        for (CommittedRevision revision : committed.revisions()) {
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
        if (serializer == null || entityHelper == null || modelExecution == null || eventLoader == null) {
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
                ? Optional.of(annotation.searchProjection().collection())
                        .filter(value -> !value.isEmpty())
                        .map(ApplicationProperties::substituteProperties)
                        .orElse(modelType.getSimpleName())
                : metadata.participatesInGraphComposition()
                        ? ModelDocumentMutation
                                .GRAPH_COMPONENT_COLLECTION
                        : Optional.of(annotation.searchProjection().collection())
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
                    eventLoader.session()
                            .reconstruct(
                                    eventTargets,
                                    ModelReadBoundary.CURRENT)
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
        String repositoryId = storedId == null ? null
                : metadata.parentScopedEntityId()
                ? metadata.repositoryId(storedId, value)
                : metadata.repositoryId(storedId);
        if (!Objects.equals(modelId, repositoryId)) {
            throw new EventSourcingException(
                    "Stored model document '%s' reports @EntityId '%s'"
                            .formatted(modelId, storedId));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> cast(Entity<?> entity) {
        return (Entity<T>) entity;
    }

    private static boolean compatible(Class<?> left, Class<?> right) {
        return left.isAssignableFrom(right) || right.isAssignableFrom(left);
    }

    private static long stateIndex(Entity<?> entity) {
        return entity instanceof ModelRoot<?> model ? model.stateIndex() : -1L;
    }

    private record CurrentModelContext(
            long stateIndex, Map<String, Entity<?>> entities) {
    }

    private record AncestorResolution(
            long stateIndex,
            ModelTargetResolver.Resolution resolution) {
    }

    /** Receives a cache value and the boundaries that prove it current. */
    @FunctionalInterface
    public interface CurrentModelSink {
        void accept(
                Entity<?> entity,
                long validThrough,
                long modelStateIndex);
    }

    /**
     * Final authoritative state and positions for a locally committed model.
     */
    public record CommittedModel(
            String modelId,
            Class<?> modelType,
            ModelMetadata.RootConfiguration model,
            ModelMetadata.Property entityId,
            boolean valueIdsValidated,
            boolean historyComplete,
            List<CommittedRevision> revisions) {

        public CommittedModel(
                String modelId,
                Class<?> modelType,
                boolean historyComplete,
                CommittedRevision revision) {
            this(
                    modelId, modelType,
                    ModelMetadata.validate(modelType),
                    historyComplete, List.of(revision));
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
                    true, historyComplete, List.of(revision));
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

        public CommittedModel {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelType, "modelType");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(entityId, "entityId");
            revisions = List.copyOf(revisions);
            if (revisions.isEmpty()) {
                throw new IllegalArgumentException(
                        "A committed model must contain at least one revision");
            }
        }

        public long stateIndex() {
            return revisions.getLast().stateIndex();
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
