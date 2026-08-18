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
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private static final int COMMITTED_CACHE_UPDATE_BATCH_SIZE = 128;

    private final Client client;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final EntityHelper entityHelper;
    private final ModelExecutionPlan.Compiler modelExecution;
    private final ModelReplayCursor replayCursor;
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
        this.replayCursor = client.getEventStoreClient() == null
                ? null : new ModelReplayCursor(
                        client.getEventStoreClient(), serializer, entityHelper,
                        modelExecution, modelCache, snapshotStore,
                        this::loadDocumentProjection, this);
        this.modelCacheTracker =
                replayCursor == null
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
        Model model = metadata.model().orElseThrow(() -> new IllegalArgumentException(
                modelType.getName() + " is not annotated with @Model"));
        if (replayCursor == null) {
            if (!model.eventSourced() && handlerBoundary == null) {
                return loadDocument(modelId, modelType, metadata, model);
            }
            requireEventReconstruction();
        }
        ModelReplayCursor.EntityProjection projection = replayCursor.entity(
                modelId, modelType, boundary(handlerBoundary), modelCacheTracker);
        pin(handlerBoundary, projection.stateIndex());
        return cast(projection.entity());
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
        Map<String, ModelBatchScope.StagedModel> staged =
                includeMessageBatch
                        ? ModelBatchScope.currentValues(
                                messageBatchNamespace())
                        : Map.of();
        Graph<T> result = replayCursor.graph(
                rootId, rootType, options, boundary,
                messageBatchNamespace(), staged, historical);
        pin(handlerBoundary, result.stateIndex());
        return result;
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
        ModelReplayCursor.FirstEvent first = replayCursor.firstEvent(modelId, boundary);
        pin(handlerBoundary, first.stateIndex());
        if (first.event() == null) {
            return null;
        }
        LinkedHashSet<Class<?>> candidates =
                new LinkedHashSet<>();
        try {
            serializer.deserializeMessages(
                            Stream.of(first.event()),
                            EVENT, UnknownTypeStrategy.FAIL)
                    .map(DeserializingMessage::getPayload)
                    .forEach(payload -> payloadFactoryTarget(
                                    first.modelId(), payload)
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
        ModelReplayCursor.LoadResult result = replayCursor.loadHeads(List.of(modelId), boundary);
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
        requireEventReconstruction();
        return replayCursor.context(
                resolution, boundary, stagedValues, includeMessageBatch,
                includeMessageBatch ? messageBatchNamespace() : null,
                modelCacheTracker);
    }

    private AncestorResolution resolveAncestors(
            ModelTargetResolver.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch) {
        ModelReplayCursor.AncestorResult result = replayCursor.resolveAncestors(
                resolution, boundary, stagedValues, includeMessageBatch,
                includeMessageBatch ? messageBatchNamespace() : null);
        return new AncestorResolution(result.stateIndex(), result.resolution());
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
        ModelReplayCursor.AncestorResult result = replayCursor.resolveAncestors(
                resolution, boundary, stagedValues, includeMessageBatch,
                includeMessageBatch ? messageBatchNamespace() : null,
                requireAncestors, closestAncestorsOnly, allowMultipleAncestors,
                maxDepth, maxModels);
        return new AncestorResolution(result.stateIndex(), result.resolution());
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
        if (serializer == null || entityHelper == null || modelExecution == null || replayCursor == null) {
            throw new EventSourcingException(
                    "Event-sourced model reconstruction requires a configured serializer and model entity helper");
        }
    }

    private <T> Entity<T> loadDocument(
            String modelId, Class<T> modelType, ModelMetadata metadata, Model annotation) {
        return cast(loadDocumentUnchecked(modelId, modelType, metadata, annotation));
    }

    private Entity<?> loadDocumentProjection(String modelId, Class<?> modelType) {
        ModelMetadata metadata = ModelMetadata.validate(modelType);
        return loadDocumentUnchecked(
                modelId, modelType, metadata, metadata.model().orElseThrow());
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
        return replayCursor.refresh(targets, safeStateIndex);
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
