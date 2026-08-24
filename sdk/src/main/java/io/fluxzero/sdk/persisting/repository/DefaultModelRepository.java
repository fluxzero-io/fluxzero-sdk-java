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

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
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
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.api.search.AdoptModelMigration;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetDocumentResult;
import io.fluxzero.common.api.search.GetModelMigration;
import io.fluxzero.common.api.search.GetModelMigrationResult;
import io.fluxzero.common.api.search.GetModelMigrations;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.caching.NoOpCache;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.AggregateEventRouting;
import io.fluxzero.sdk.modeling.Change;
import io.fluxzero.sdk.modeling.DirectModelUpdate;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProjectionCompletion;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.ImmutableRoot;
import io.fluxzero.sdk.modeling.CommitAttempt;
import io.fluxzero.sdk.modeling.ModelBatchScope;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.MutationPlan;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.Tracker;
import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.NOTIFICATION;
import static io.fluxzero.common.SearchUtils.parseTimeProperty;
import static io.fluxzero.common.api.search.ModelGraphComposition.UNBOUNDED;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;

/**
 * Default repository for independently stored models.
 * <p>
 * Current document-based models use their synchronously maintained direct document. Event-sourced and historical
 * loads use the model-stream protocol and reconstruct every selected stream at one pinned {@code stateIndex}.
 */
public class DefaultModelRepository extends AbstractNamespaced<ModelRepository>
        implements ModelRepository, ModelAncestorResolver {
    private static final int COMMITTED_CACHE_UPDATE_BATCH_SIZE = 128;
    private static final CompletableFuture<Void> COMPLETED_VOID =
            CompletableFuture.completedFuture(null);
    private static final long INITIAL_MIGRATION_POLL_NANOS = Duration.ofMillis(10).toNanos();
    private static final long MAX_MIGRATION_POLL_NANOS = Duration.ofMillis(250).toNanos();

    private final Client client;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final EntityHelper entityHelper;
    private final MutationPlan.Compiler modelDefinitionCompiler;
    private final ModelReplayCursor replayCursor;
    private final Cache cacheSource;
    private final Cache modelCache;
    private final Serializer snapshotSerializer;
    private final ModelSnapshotStore snapshotStore;
    private final ModelCacheTracker modelCacheTracker;
    private final AtomicReference<MigrationReadBarrierConfiguration>
            migrationReadBarrierConfiguration;
    private final AtomicLong migratedThrough = new AtomicLong(-1L);
    private final ConcurrentHashMap<Class<?>, GraphProjectionRegistration>
            graphProjectionRegistrations = new ConcurrentHashMap<>();
    private volatile Supplier<List<Class<?>>> modelTypes = List::of;

    public DefaultModelRepository(
            Client client,
            DocumentStore documentStore,
            Serializer serializer,
            EntityHelper entityHelper,
            Serializer snapshotSerializer,
            Cache cache,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        this(client, documentStore, serializer, entityHelper, snapshotSerializer, cache,
             new MutationPlan.Compiler(Objects.requireNonNull(
                     parameterResolvers, "parameterResolvers")),
             new AtomicReference<>());
    }

    private DefaultModelRepository(
            Client client,
            DocumentStore documentStore,
            Serializer serializer,
            EntityHelper entityHelper,
            Serializer snapshotSerializer,
            Cache cache,
            MutationPlan.Compiler modelDefinitionCompiler,
            AtomicReference<MigrationReadBarrierConfiguration>
                    migrationReadBarrierConfiguration) {
        this.client = Objects.requireNonNull(client, "client");
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.entityHelper = Objects.requireNonNull(entityHelper, "entityHelper");
        this.snapshotSerializer = snapshotSerializer;
        this.modelDefinitionCompiler = Objects.requireNonNull(
                modelDefinitionCompiler, "modelDefinitionCompiler");
        this.migrationReadBarrierConfiguration = Objects.requireNonNull(
                migrationReadBarrierConfiguration, "migrationReadBarrierConfiguration");
        this.cacheSource = Objects.requireNonNull(cache, "cache");
        this.modelCache = cache == NoOpCache.INSTANCE
                ? cache : new RepositoryCache(cache, "$Model", client.namespace());
        this.snapshotStore = snapshotSerializer == null
                ? null : new ModelSnapshotStore(documentStore, snapshotSerializer);
        EventStoreClient eventStoreClient = Objects.requireNonNull(
                client.getEventStoreClient(), "eventStoreClient");
        this.replayCursor = new ModelReplayCursor(
                eventStoreClient, serializer, entityHelper,
                modelDefinitionCompiler, modelCache, snapshotStore,
                this::loadDocumentProjection, this,
                this::awaitPublishedEventMigration);
        this.modelCacheTracker = cache == NoOpCache.INSTANCE
                ? null
                : new ModelCacheTracker(
                        eventStoreClient,
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
        DefaultModelRepository result = new DefaultModelRepository(
                namespacedClient, namespacedDocumentStore, serializer, entityHelper,
                snapshotSerializer, cacheSource, modelDefinitionCompiler,
                migrationReadBarrierConfiguration);
        result.configureModelTypes(modelTypes);
        return result;
    }

    /** Returns the model-definition compiler shared by live commits and stored-event replay. */
    public MutationPlan.Compiler modelDefinitionCompiler() {
        return modelDefinitionCompiler;
    }

    @Override
    public ModelRepository followPublishedEventMigration(
            @NonNull String migrationName,
            @NonNull Duration maxWait) {
        MigrationReadBarrierConfiguration requested =
                new MigrationReadBarrierConfiguration(migrationName, maxWait);
        MigrationReadBarrierConfiguration configured =
                migrationReadBarrierConfiguration.updateAndGet(
                        current -> current == null ? requested : current);
        if (!configured.equals(requested)) {
            throw new IllegalStateException(
                    "Model repository already follows published-event migration "
                    + configured.migrationName());
        }
        return this;
    }

    boolean awaitPublishedEventMigration(long eventIndex) {
        MigrationReadBarrierConfiguration configuration =
                migrationReadBarrierConfiguration.get();
        if (configuration == null) {
            return false;
        }
        if (eventIndex < 0L) {
            throw new IllegalArgumentException(
                    "Legacy event index must not be negative");
        }
        if (migratedThrough.get() >= eventIndex) {
            return true;
        }
        long started = System.nanoTime();
        long timeoutNanos = configuration.maxWait().toNanos();
        long pollNanos = INITIAL_MIGRATION_POLL_NANOS;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw interruptedMigrationWait(configuration, eventIndex);
            }
            Position position = client.getTrackingClient(EVENT)
                    .getPosition(configuration.migrationName());
            Long observed = position == null
                    ? null
                    : position.lowestIndexForSegment(
                            new int[]{0, MAX_SEGMENT}).orElse(null);
            if (observed != null) {
                migratedThrough.accumulateAndGet(observed, Math::max);
            }
            long current = migratedThrough.get();
            if (current >= eventIndex) {
                return true;
            }
            long elapsed = System.nanoTime() - started;
            if (elapsed >= timeoutNanos) {
                throw new EventSourcingException(
                        "Published-event Model migration %s reached event %s while legacy event %d requires an exact Model state"
                                .formatted(
                                        configuration.migrationName(),
                                        current < 0L ? "no durable position" : Long.toString(current),
                                        eventIndex));
            }
            LockSupport.parkNanos(Math.min(pollNanos, timeoutNanos - elapsed));
            pollNanos = Math.min(MAX_MIGRATION_POLL_NANOS, pollNanos * 2L);
        }
    }

    private static EventSourcingException interruptedMigrationWait(
            MigrationReadBarrierConfiguration configuration,
            long eventIndex) {
        Thread.currentThread().interrupt();
        return new EventSourcingException(
                "Interrupted while waiting for published-event Model migration %s to process legacy event %d"
                        .formatted(configuration.migrationName(), eventIndex));
    }

    private record MigrationReadBarrierConfiguration(
            String migrationName,
            Duration maxWait) {
        private MigrationReadBarrierConfiguration {
            if (migrationName == null || migrationName.isBlank()) {
                throw new IllegalArgumentException(
                        "Migration name must not be blank");
            }
            Objects.requireNonNull(maxWait, "maxWait");
            if (maxWait.isZero() || maxWait.isNegative()) {
                throw new IllegalArgumentException(
                        "Migration read maxWait must be positive");
            }
            try {
                maxWait.toNanos();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Migration read maxWait is too large", e);
            }
        }
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
                ? EntityMetadata.of(id.getType()).repositoryId(id)
                : modelId.toString();
    }

    private CompletableFuture<Void> adoptPersistedModelMigration(
            String persistedId,
            Class<?> modelType,
            EntityMetadata metadata) {
        String collection = metadata.modelDocumentCollection()
                .orElseThrow(() -> new IllegalArgumentException(
                        modelType.getName() + " has no direct document to adopt"));
        GetModelMigrationResult migration = client.getSearchClient()
                .getModelMigration(new GetModelMigration(
                        persistedId, collection));
        ModelHeadState migratedHead = migration.getMigratedHead();
        if (migratedHead == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "No staged Model migration exists for " + persistedId));
        }
        if (!modelType.getName().equals(migratedHead.getModelType())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Staged Model type %s does not match requested type %s for %s"
                                    .formatted(
                                            migratedHead.getModelType(),
                                            modelType.getName(), persistedId)));
        }
        SerializedDocument production = migration.getProductionDocument();
        SerializedDocument staged = migration.getMigratedDocument();
        if (production != null
            && (staged == null
                || !sameCurrentValue(
                        production, staged, modelType))) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Migrated Model value differs from the production document for "
                            + persistedId));
        }
        return client.getSearchClient().adoptModelMigration(
                new AdoptModelMigration(
                        persistedId, collection,
                        migration.getProductionDocumentIndex(),
                        migratedHead.getStateIndex(), STORED));
    }

    @Override
    public CompletableFuture<Integer> adoptModelMigrations() {
        return adoptModelMigrationBatch(0)
                .thenCompose(adopted -> rebuildApplicationGraphProjections()
                        .thenApply(ignored -> adopted));
    }

    private CompletableFuture<Integer> adoptModelMigrationBatch(
            int adopted) {
        List<ModelHeadState> migrations = client.getSearchClient()
                .getModelMigrations(new GetModelMigrations(1_000))
                .getMigrations();
        if (migrations.isEmpty()) {
            return CompletableFuture.completedFuture(adopted);
        }
        CompletableFuture<Void> batch = CompletableFuture.completedFuture(null);
        for (ModelHeadState migration : migrations) {
            Class<?> modelType = migrationType(migration);
            EntityMetadata metadata = EntityMetadata.validate(modelType);
            metadata.rootConfiguration()
                    .filter(root -> root.kind() == EntityMetadata.RootKind.MODEL)
                    .orElseThrow(() -> new IllegalStateException(
                            migration.getModelType() + " is not a Model root"));
            batch = batch.thenCompose(ignored -> adoptPersistedModelMigration(
                    migration.getModelId(), modelType, metadata));
        }
        // Trampoline between batches so a large, immediately completed in-memory migration
        // cannot grow the caller stack once per thousand adopted Models.
        return batch.thenComposeAsync(ignored ->
                adoptModelMigrationBatch(adopted + migrations.size()));
    }

    private CompletableFuture<Void> rebuildApplicationGraphProjections() {
        return CompletableFuture.allOf(
                modelTypes.get().stream()
                        .flatMap(type -> EntityMetadata.graphProjectionRoots(type).stream())
                        .map(EntityMetadata.GraphProjectionRoot::modelType)
                        .distinct()
                        .sorted(Comparator.comparing(Class::getName))
                        .map(type -> registerGraphProjection(type, true))
                        .toArray(CompletableFuture[]::new));
    }

    private Class<?> migrationType(
            ModelHeadState migration) {
        return modelTypes.get().stream()
                .filter(type -> type.getName().equals(
                        migration.getModelType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Staged Model type %s for %s is not registered in this application"
                                .formatted(
                                        migration.getModelType(),
                                        migration.getModelId())));
    }

    private boolean sameCurrentValue(
            SerializedDocument left,
            SerializedDocument right,
            Class<?> modelType) {
        DocumentSerializer documentSerializer = documentStore.getSerializer();
        SerializedDocument normalizedLeft = documentSerializer.toDocument(
                documentSerializer.fromDocument(left, modelType),
                "$migration", "$migration", null, null, Metadata.empty());
        SerializedDocument normalizedRight = documentSerializer.toDocument(
                documentSerializer.fromDocument(right, modelType),
                "$migration", "$migration", null, null, Metadata.empty());
        return normalizedLeft.getDocument().equals(
                        normalizedRight.getDocument())
               && Objects.equals(
                       normalizedLeft.getSummary(),
                       normalizedRight.getSummary())
               && Objects.equals(
                       normalizedLeft.getFacets(),
                       normalizedRight.getFacets())
               && Objects.equals(
                       normalizedLeft.getIndexes(),
                       normalizedRight.getIndexes());
    }

    @Override
    public CompletableFuture<ModelGraphProjectionStatus>
            registerGraphProjection(
                    @NonNull Class<?> modelType,
                    boolean rebuild) {
        ModelGraphProjectionConfiguration configuration =
                graphProjectionDefinition(modelType);
        return graphProjectionRegistrations.compute(
                modelType,
                (ignored, current) -> rebuild
                        || current == null
                        || current.future().isCompletedExceptionally()
                        || !current.configuration().equals(configuration)
                        ? new GraphProjectionRegistration(
                                configuration,
                                current == null
                                        ? requestGraphProjectionRegistration(
                                                configuration, rebuild)
                                        : current.future()
                                                .handle((status, failure) -> null)
                                                .thenCompose(ignoredStatus ->
                                                        requestGraphProjectionRegistration(
                                                                configuration, rebuild)))
                        : current).future();
    }

    /** Configures the application-bound model catalog used to version materialized Graph schemas. */
    public void configureModelTypes(
            Supplier<List<Class<?>>> modelTypes) {
        this.modelTypes = Objects.requireNonNull(
                modelTypes, "Model types");
    }

    /** Returns the application-resolved durable definition owned by this repository. */
    public ModelGraphProjectionConfiguration graphProjectionDefinition(
            @NonNull Class<?> modelType) {
        return EntityMetadata.validate(modelType)
                .graphProjectionConfiguration(
                        modelTypes.get())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                modelType.getName()
                                + " does not enable a graph projection"));
    }

    /** Completes when every affected durable graph projection has processed the supplied commit range. */
    public CompletableFuture<Void> awaitGraphProjections(
            @NonNull Map<Class<?>, Set<String>> projections,
            long firstStateIndex,
            long stateIndex) {
        Map<String, Set<String>> collections = new LinkedHashMap<>();
        projections.forEach((modelType, modelIds) ->
                collections.computeIfAbsent(
                                graphProjectionDefinition(modelType).getCollection(),
                                ignored -> new LinkedHashSet<>())
                        .addAll(modelIds));
        return CompletableFuture.allOf(
                collections.entrySet().stream()
                        .map(entry -> client.getEventStoreClient()
                                .awaitModelGraphProjection(
                                        new AwaitModelGraphProjection(
                                                entry.getKey(), stateIndex,
                                                firstStateIndex, entry.getValue())))
                        .toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<ModelGraphProjectionStatus>
            requestGraphProjectionRegistration(
                    ModelGraphProjectionConfiguration configuration,
                    boolean rebuild) {
        return client.getEventStoreClient()
                .registerModelGraphProjection(
                        new RegisterModelGraphProjection(
                                configuration, rebuild));
    }

    @Override
    public ModelGraphProjectionStatus
            graphProjectionStatus(
                    @NonNull Class<?> modelType) {
        String collection = graphProjectionDefinition(
                modelType).getCollection();
        return client.getEventStoreClient()
                .getModelGraphProjectionStatus(
                        new GetModelGraphProjectionStatus(
                                collection));
    }

    private record GraphProjectionRegistration(
            ModelGraphProjectionConfiguration configuration,
            CompletableFuture<ModelGraphProjectionStatus> future) {
    }

    @Override
    public <T> Entity<T> load(@NonNull String modelId, @NonNull Class<T> modelType) {
        return ModelBatchScope.overlayCurrent(
                messageBatchNamespace(), modelId, modelType,
                loadDurable(modelId, modelType));
    }

    @Override
    public <T> Entity<T> loadCurrent(@NonNull String modelId, @NonNull Class<T> modelType) {
        return ModelBatchScope.overlayCurrent(
                messageBatchNamespace(), modelId, modelType,
                loadDurable(modelId, modelType, ModelReadBoundary.current(), null));
    }

    private <T> Entity<T> loadDurable(
            String modelId,
            Class<T> modelType) {
        PinnedBoundary handlerBoundary =
                handlerBoundary();
        return loadDurable(
                modelId, modelType,
                boundary(handlerBoundary), handlerBoundary);
    }

    private <T> Entity<T> loadDurable(
            String modelId,
            Class<T> modelType,
            ModelReadBoundary boundary,
            PinnedBoundary handlerBoundary) {
        if (Object.class.equals(modelType)) {
            Class<?> resolvedType = resolveStoredType(
                    modelId, boundary, handlerBoundary);
            if (resolvedType == null) {
                return cast(emptyUntyped(modelId));
            }
            return cast(loadDurable(
                    modelId, resolvedType,
                    boundary, handlerBoundary));
        }
        EntityMetadata metadata = EntityMetadata.validate(modelType);
        metadata.rootConfiguration()
                .filter(root -> root.kind() == EntityMetadata.RootKind.MODEL)
                .orElseThrow(() -> new IllegalArgumentException(
                modelType.getName() + " is not annotated with @Model"));
        MutationPlan.ResolvedModel target = new MutationPlan.ResolvedModel(
                modelId, modelType, MutationPlan.Access.READ_ONLY,
                List.of(metadata.entityId().orElseThrow().name()));
        CommitAttempt context = replayCursor.context(
                new MutationPlan.Resolution(List.of(target), List.of()),
                boundary, Map.of(), null,
                modelCacheTracker, handlerBoundary != null);
        pin(handlerBoundary, context.readStateIndex());
        return cast(context.entity(context.targets().getFirst().modelId()));
    }

    @Override
    public <T> List<Entity<T>> loadAll(
            @NonNull List<?> modelIds,
            @NonNull Class<T> modelType) {
        if (modelIds.isEmpty()) {
            return List.of();
        }
        EntityMetadata metadata = EntityMetadata.validate(modelType);
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
        List<MutationPlan.ResolvedModel> targets = ids.stream()
                .map(modelId -> new MutationPlan.ResolvedModel(
                        modelId,
                        modelType,
                        MutationPlan.Access.READ_ONLY,
                        List.of(idProperty)))
                .toList();
        PinnedBoundary handlerBoundary = handlerBoundary();
        CommitAttempt context = loadContext(
                new MutationPlan.Resolution(targets, List.of()),
                boundary(handlerBoundary),
                Map.of(), true);
        pin(handlerBoundary, context.readStateIndex());
        return context.modelIds().stream()
                .map(context::entity)
                .map(DefaultModelRepository::<T>cast)
                .toList();
    }

    @Override
    public <T> Graph<T> loadGraph(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            @NonNull Graph.Options options) {
        EntityMetadata.validate(rootType);
        PinnedBoundary handlerBoundary =
                handlerBoundary();
        return reconstructGraph(
                rootId, rootType, options, boundary(handlerBoundary),
                handlerBoundary, true);
    }

    @Override
    public <T> Graph<T> loadGraph(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            @NonNull ModelReadBoundary boundary,
            @NonNull Graph.Options options) {
        return reconstructGraph(
                rootId, rootType, options, boundary, null,
                boundary.includeMessageBatch());
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
        EntityMetadata sourceMetadata = EntityMetadata.validate(modelType);
        MutationPlan.ResolvedModel source =
                new MutationPlan.ResolvedModel(
                        modelId, modelType,
                        MutationPlan.Access.READ_ONLY,
                        List.of(sourceMetadata.entityId()
                                        .orElseThrow().name()));
        MutationPlan.Resolution request =
                new MutationPlan.Resolution(
                        List.of(source), List.of(),
                        List.of(new MutationPlan.AncestorDependency(
                                ancestorType, null,
                                "Graph.ancestor(%s)".formatted(
                                        ancestorType.getName()))));
        Map<String, Object> stagedValues;
        if (!boundary.includeMessageBatch()) {
            stagedValues = Map.of();
        } else {
            Map<String, Entity<?>> staged =
                    ModelBatchScope.currentValues(
                            messageBatchNamespace());
            if (staged.isEmpty()) {
                stagedValues = Map.of();
            } else {
                LinkedHashMap<String, Object> values =
                        new LinkedHashMap<>(staged.size());
                staged.forEach((id, value) ->
                        values.put(id, value.get()));
                stagedValues = values;
            }
        }
        AncestorResolution resolved = resolveAncestors(
                request,
                boundary.forRequest(),
                stagedValues, boundary.includeMessageBatch(),
                false, !all, all,
                UNBOUNDED, UNBOUNDED);
        List<MutationPlan.ResolvedModel> targets =
                resolved.resolution().models().stream()
                        .filter(candidate ->
                                !candidate.modelId().equals(modelId))
                        .filter(candidate ->
                                ancestorType.isAssignableFrom(
                                        candidate.modelType()))
                        .toList();
        Graph.Options rootOnly = new Graph.Options(0, 1);
        ModelReadBoundary graphBoundary = boundary.resolved(
                resolved.stateIndex(), boundary.includeMessageBatch());
        return targets.stream().map(target -> {
            @SuppressWarnings("unchecked") Class<A> targetType =
                    (Class<A>) target.modelType();
            return loadGraph(
                    target.modelId(), targetType,
                    graphBoundary, rootOnly);
        }).toList();
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
        return loadGraph(
                rootId, rootType,
                ModelReadBoundary.state(stateIndex, true), options);
    }

    private <T> Graph<T> reconstructGraph(
            String rootId,
            Class<T> rootType,
            Graph.Options options,
            ModelReadBoundary boundary,
            PinnedBoundary handlerBoundary,
            boolean includeMessageBatch) {
        EntityMetadata.validate(rootType);
        Map<String, Entity<?>> staged =
                includeMessageBatch
                        ? ModelBatchScope.currentValues(
                                messageBatchNamespace())
                        : Map.of();
        Graph<T> result = replayCursor.graph(
                rootId, rootType, options, boundary,
                messageBatchNamespace(), staged);
        pin(handlerBoundary, result.stateIndex());
        return result;
    }

    private Class<?> resolveStoredType(
            String modelId,
            ModelReadBoundary boundary,
            PinnedBoundary handlerBoundary) {
        if (client.getEventStoreClient() == null) {
            throw new EventSourcingException(
                    "Loading an independent model by untyped ID requires model-head type metadata");
        }
        ModelReplayCursor.LoadResult result = replayCursor.loadHeads(List.of(modelId), boundary);
        pin(handlerBoundary, result.stateIndex());
        ModelHeadState head = result.heads().get(modelId);
        if (head == null) {
            return null;
        }
        return replayCursor.modelType(head.getModelType(), modelId);
    }

    private Entity<Object> emptyUntyped(String modelId) {
        return ImmutableModelRoot.initial(
                modelId, Object.class, null, null, entityHelper, serializer);
    }

    private PinnedBoundary handlerBoundary() {
        DeserializingMessage current =
                DeserializingMessage.getCurrent();
        if (current == null
            || current.getMessageType() != EVENT
               && current.getMessageType() != NOTIFICATION) {
            return null;
        }
        return current.computeContextIfAbsent(
                        PinnedBoundary.class,
                        message -> {
                            ModelReadBoundary boundary = ModelEventMetadata.readBoundary(
                                    message.getMetadata(), message.getMessageType(), message.getIndex());
                            return boundary == null ? null : new PinnedBoundary(boundary);
                        });
    }

    private static final class PinnedBoundary {
        private final ModelReadBoundary source;
        private Long stateIndex;

        private PinnedBoundary(ModelReadBoundary source) {
            this.source = source;
        }

        private synchronized ModelReadBoundary request() {
            return stateIndex == null
                    ? source
                    : ModelReadBoundary.state(stateIndex, false);
        }

        private synchronized void pin(long value) {
            if (stateIndex != null && stateIndex != value) {
                String description = source.commitId() == null
                        ? "event %d".formatted(source.eventIndex())
                        : "commit %s substep %d".formatted(
                                source.commitId(), source.substep());
                throw new EventSourcingException(
                        "Published model boundary %s resolved to both state %d and %d"
                                .formatted(description, stateIndex, value));
            }
            stateIndex = value;
        }
    }

    private static ModelReadBoundary boundary(
            PinnedBoundary boundary) {
        return boundary == null
                ? ModelReadBoundary.CURRENT
                : boundary.request();
    }

    private static void pin(
            PinnedBoundary boundary,
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
    public CommitAttempt loadContext(
            MutationPlan.Resolution resolution) {
        PinnedBoundary handlerBoundary =
                handlerBoundary();
        CommitAttempt context = loadContext(
                resolution, boundary(handlerBoundary), Map.of(), true);
        pin(handlerBoundary, context.readStateIndex());
        return context;
    }

    /**
     * Loads a commit context with an explicit choice whether pending values from the surrounding tracking batch should
     * be overlaid. Automatic model handling disables this generic overlay because its preplanned batch view already
     * supplies exactly the required predecessors; explicit operations and ordinary handlers enable it.
     */
    public CommitAttempt loadContext(
            MutationPlan.Resolution resolution,
            Long maxStateIndex,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch) {
        return loadContext(
                resolution,
                ModelReadBoundary.at(maxStateIndex),
                stagedValues, includeMessageBatch, false);
    }

    public CommitAttempt loadContext(
            MutationPlan.Resolution resolution,
            Long maxStateIndex,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch,
            boolean migration) {
        return loadContext(
                resolution,
                ModelReadBoundary.at(maxStateIndex),
                stagedValues, includeMessageBatch, migration);
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

    private CommitAttempt loadContext(
            MutationPlan.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch) {
        return loadContext(
                resolution, boundary, stagedValues,
                includeMessageBatch, false);
    }

    private CommitAttempt loadContext(
            MutationPlan.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch,
            boolean migration) {
        String namespace = includeMessageBatch ? messageBatchNamespace() : null;
        Map<String, Object> effectiveStagedValues = stagedValues;
        if (includeMessageBatch) {
            Map<String, Object> batchValues = ModelBatchScope.currentValues(
                    namespace, resolution);
            if (!batchValues.isEmpty()) {
                if (stagedValues.isEmpty()) {
                    effectiveStagedValues = batchValues;
                } else {
                    LinkedHashMap<String, Object> combined = new LinkedHashMap<>(batchValues);
                    combined.putAll(stagedValues);
                    effectiveStagedValues = combined;
                }
            }
        }
        CommitAttempt context = replayCursor.context(
                resolution, boundary, effectiveStagedValues,
                includeMessageBatch
                        ? modelId -> ModelBatchScope.currentValue(namespace, modelId)
                        : null,
                migration ? null : modelCacheTracker, true,
                migration);
        return includeMessageBatch
                ? ModelBatchScope.overlayCurrent(namespace, context)
                : context;
    }

    private AncestorResolution resolveAncestors(
            MutationPlan.Resolution resolution,
            ModelReadBoundary boundary,
            Map<String, Object> stagedValues,
            boolean includeMessageBatch,
            boolean requireAncestors,
            boolean closestAncestorsOnly,
            boolean allowMultipleAncestors,
            int maxDepth,
            int maxModels) {
        String namespace = includeMessageBatch ? messageBatchNamespace() : null;
        ModelReplayCursor.AncestorResult result = replayCursor.resolveAncestors(
                resolution, boundary, stagedValues,
                includeMessageBatch
                        ? modelId -> ModelBatchScope.currentValue(namespace, modelId)
                        : null,
                requireAncestors, closestAncestorsOnly, allowMultipleAncestors,
                maxDepth, maxModels);
        return new AncestorResolution(result.stateIndex(), result.resolution());
    }

    /**
     * Makes accepted local model transitions immediately visible through this repository.
     */
    public void updateAfterCommit(
            List<Commit.Outcome> outcomes) {
        LinkedHashMap<String, List<CommittedRevision>> byModel = new LinkedHashMap<>();
        outcomes.stream().flatMap(outcome -> outcome.revisions().stream())
                .forEach(revision -> byModel.computeIfAbsent(
                        revision.change().modelId(), ignored -> new ArrayList<>()).add(revision));
        if (byModel.isEmpty()) {
            return;
        }
        List<Map.Entry<String, List<CommittedRevision>>> committedModels =
                new ArrayList<>(byModel.entrySet());
        if (committedModels.size() == 1) {
            updateAfterCommit(committedModels.getFirst());
            return;
        }
        for (int offset = 0;
             offset < committedModels.size();
             offset += COMMITTED_CACHE_UPDATE_BATCH_SIZE) {
            modelCache.<Map.Entry<String, List<CommittedRevision>>, Entity<?>>updateAll(
                    committedModels.subList(
                            offset,
                            Math.min(
                                    committedModels.size(),
                                    offset
                                    + COMMITTED_CACHE_UPDATE_BATCH_SIZE)),
                    Map.Entry::getKey,
                    (committed, current) ->
                            applyCommittedModel(
                                    current, committed.getKey(), committed.getValue()));
        }
        if (modelCacheTracker == null) {
            return;
        }
        committedModels.forEach(committed ->
                updateTrackerAfterCommit(committed));
    }

    private Entity<?> applyCommittedModel(
            Entity<?> current,
            String modelId,
            List<CommittedRevision> revisions) {
        CommittedRevision committed = revisions.getLast();
        Change transition = committed.change();
        long stateIndex = committed.result().getStateIndex();
        if (!committed.target().isHistoryComplete()) {
            return current != null
                   && ModelReplayCursor.stateIndex(current) > stateIndex
                    ? current : null;
        }
        if (!transition.configuration().cached()) {
            return null;
        }
        if (current != null && ModelReplayCursor.stateIndex(current) >= stateIndex) {
            return current;
        }
        return committedEntity(
                modelId, transition, revisions, current);
    }

    private void updateTrackerAfterCommit(
            Map.Entry<String, List<CommittedRevision>> committed) {
        String modelId = committed.getKey();
        CommittedRevision revision = committed.getValue().getLast();
        Change transition = revision.change();
        if (!revision.target().isHistoryComplete()) {
            modelCacheTracker.forget(modelId);
        } else if (transition.configuration().cached()) {
            modelCacheTracker.committed(
                    modelId, transition.modelType(),
                    revision.result().getStateIndex());
        }
    }

    private void updateAfterCommit(
            Map.Entry<String, List<CommittedRevision>> committed) {
        modelCache.<Entity<?>>compute(
                committed.getKey(),
                (ignored, current) ->
                        applyCommittedModel(
                                current, committed.getKey(), committed.getValue()));
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
            String modelId,
            Change finalTransition,
            List<CommittedRevision> revisions,
            Entity<?> previous) {
        EntityMetadata.Property entityId =
                finalTransition.metadata().entityId().orElseThrow();
        EntityMetadata.RootConfiguration model = finalTransition.configuration();
        Entity<?> result = previous;
        for (CommittedRevision revision : revisions) {
            Change transition = revision.change();
            ModelCommitStep requestStep = revision.request();
            ModelUpdate resultStep = revision.result();
            ModelCommitTargetResult targetResult = revision.target();
            result = ImmutableModelRoot.revision(
                    modelId, (Class<Object>) finalTransition.modelType(), entityId.name(), transition.after(),
                    entityHelper, serializer,
                    requestStep.getEvent() == null ? null : requestStep.getEvent().getMessageId(),
                    resultStep.getEventIndex(), Instant.ofEpochMilli(revision.timestamp()),
                    targetResult.getSequenceNumber(), resultStep.getStateIndex(),
                    castPrevious(ImmutableRoot.retainPrevious(result, model)));
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Committed model has no revisions: "
                    + modelId);
        }
        return result;
    }

    private record CommittedRevision(
            Change change,
            ModelCommitStep request,
            ModelUpdate result,
            ModelCommitTargetResult target,
            long timestamp) {
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> castPrevious(Entity<?> entity) {
        return (Entity<T>) entity;
    }

    private ModelReplayCursor.DocumentVersion loadDocumentProjection(
            String modelId, Class<?> modelType, boolean migration) {
        EntityMetadata metadata = EntityMetadata.validate(modelType);
        return loadDocumentUnchecked(
                modelId, modelType, metadata, migration);
    }

    @SuppressWarnings("unchecked")
    private ModelReplayCursor.DocumentVersion loadDocumentUnchecked(
            String modelId, Class<?> modelType, EntityMetadata metadata,
            boolean migration) {
        String collection = metadata.modelDocumentReadCollection();
        GetDocumentResult result = client.getSearchClient().fetchModelDocument(
                new GetDocument(
                        modelId,
                        migration
                                ? ModelDocumentMutation.MIGRATION_COLLECTION
                                : collection));
        ModelHeadState head = result.getModelHead();
        if (head != null) {
            if (!modelId.equals(head.getModelId())) {
                throw new EventSourcingException(
                        "Direct Model document '%s' reports head identity '%s'"
                                .formatted(modelId, head.getModelId()));
            }
            Class<?> storedType = replayCursor.modelType(
                    head.getModelType(), modelId);
            if (!modelType.isAssignableFrom(storedType)) {
                throw new EventSourcingException(
                        "Direct Model document '%s' has stored type %s instead of %s"
                                .formatted(modelId, storedType.getName(), modelType.getName()));
            }
        }
        Object value = result.getDocument() == null
                ? null : serializer.deserialize(
                        result.getDocument().getDocument(), modelType);
        if (value != null && !modelType.isInstance(value)) {
            throw new EventSourcingException(
                    "Direct Model document '%s' contains %s instead of %s"
                            .formatted(modelId, value.getClass().getName(), modelType.getName()));
        }
        String idProperty = metadata.entityId().orElseThrow().name();
        ModelReplayCursor.validateValueId(modelId, metadata, value);
        Entity<?> entity = ImmutableModelRoot.initial(
                modelId, (Class<Object>) modelType, idProperty, value,
                entityHelper, serializer);
        if (head == null) {
            return new ModelReplayCursor.DocumentVersion(entity, null);
        }
        Entity<?> revision = ImmutableModelRoot.revision(
                entity.id(), (Class<Object>) entity.type(), entity.idProperty(), entity.get(),
                entityHelper, serializer, null, null, entity.timestamp(),
                head.getSequenceNumber(), head.getStateIndex(), null);
        return new ModelReplayCursor.DocumentVersion(revision, head);
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

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> cast(Entity<?> entity) {
        return (Entity<T>) entity;
    }

    private record AncestorResolution(
            long stateIndex,
            MutationPlan.Resolution resolution) {
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
     * Converts a side-effect-free {@link CommitAttempt} evaluation into one authoritative runtime commit package.
     * <p>
     * The original event payload is serialized once per substep. Per-target stream membership remains separate, while
     * global publication is the union of all targeted model publication policies. Optional direct documents and snapshots
     * travel with the same package. The runtime durably retains incomplete materialization work and reports completion
     * before a successful model commit returns, preserving immediate direct-search visibility across retries and restarts.
     */
    public final class Commit {
        private final EventStoreClient eventStoreClient;
        private final Serializer serializer;
        private final Serializer snapshotSerializer;
        private final DocumentSerializer documentSerializer;
        private final DispatchInterceptor dispatchInterceptor;
        private final String source;
        private final GraphProjectionCompletion graphProjectionCompletion;
        private final ConcurrentHashMap<Class<?>, Optional<String>> documentCollections =
                new ConcurrentHashMap<>();
        private final ModelCommitBatchingClient.ModelCommitResultProcessor resultProcessor =
                this::processCommitResults;

        public Commit(
                EventStoreClient eventStoreClient,
                Serializer serializer,
                DocumentSerializer documentSerializer,
                DispatchInterceptor dispatchInterceptor,
                String source,
                Serializer snapshotSerializer,
                GraphProjectionCompletion graphProjectionCompletion) {
            this.eventStoreClient = Objects.requireNonNull(eventStoreClient);
            this.serializer = Objects.requireNonNull(serializer);
            this.snapshotSerializer = snapshotSerializer;
            this.documentSerializer = Objects.requireNonNull(documentSerializer);
            this.dispatchInterceptor = Objects.requireNonNull(dispatchInterceptor);
            this.source = source;
            this.graphProjectionCompletion =
                    graphProjectionCompletion == GraphProjectionCompletion.DEFAULT
                            ? GraphProjectionCompletion.ASYNC
                            : Objects.requireNonNull(
                                    graphProjectionCompletion,
                                    "graphProjectionCompletion");
        }

        public CompletableFuture<Optional<CommitModelsResult>> commit(
                String commitId, CommitAttempt evaluation) {
            return commit(commitId, evaluation, ModelConflictPolicy.ACCEPT);
        }

        public CompletableFuture<Optional<CommitModelsResult>> commit(
                String commitId,
                CommitAttempt evaluation,
                ModelConflictPolicy conflictPolicy) {
            return commitPrepared(
                    prepare(commitId, evaluation, conflictPolicy),
                    null, -1);
        }

        public CompletableFuture<Optional<CommitModelsResult>>
                commitPrepared(
                        Outcome prepared,
                        ModelCommitBatchingClient.ModelCommitBatch batch,
                        int batchSlot) {
            if (prepared.commit() == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            CompletableFuture<CommitModelsResult> committed = batch == null
                    ? eventStoreClient.commitModels(prepared.commit())
                    : batch.add(
                            batchSlot,
                            prepared.commit(),
                            new ModelCommitBatchingClient.ModelCommitCompletion(
                                    prepared,
                                    resultProcessor));
            if (batch != null) {
                return committed.thenApply(result -> Optional.of(result));
            }
            return committed.thenCompose(result ->
                    result.isAccepted()
                            ? processCommits(List.of(prepared.accepted(result)))
                                    .thenApply(ignored -> Optional.of(result))
                            : CompletableFuture.completedFuture(
                                    Optional.of(result)));
        }

        private CompletableFuture<Void> processCommitResults(
                List<CommitModelsResult> results,
                List<Object> contexts) {
            if (results.size() != contexts.size()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                                "Model commit results and contexts must have equal sizes"));
            }
            List<Outcome> committed = new ArrayList<>(results.size());
            for (int index = 0; index < results.size(); index++) {
                Object context = contexts.get(index);
                if (!(context instanceof Outcome prepared)) {
                    return CompletableFuture.failedFuture(
                            new IllegalArgumentException(
                                    "Unexpected model commit completion context: "
                                    + context.getClass().getName()));
                }
                CommitModelsResult result = results.get(index);
                if (result.isAccepted()) {
                    committed.add(prepared.accepted(result));
                }
            }
            return committed.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : processCommits(committed);
        }

        public ModelCommitBatchingClient.ModelCommitBatch beginBatch(int producers) {
            return eventStoreClient instanceof ModelCommitBatchingClient batching
                    ? batching.beginModelCommitBatch(producers) : null;
        }

        public ModelCommitBatchingClient.ModelCommitBatch beginReadyBatch() {
            return eventStoreClient instanceof ModelCommitBatchingClient batching
                    ? batching.beginReadyModelCommitBatch() : null;
        }

        /** Keeps tracker fencing around the complete commit and retry lifecycle. */
        public CompletableFuture<Optional<CommitModelsResult>> trackLocalCommit(
                CommitAttempt attempt,
                DeserializingMessage message,
                boolean migration,
                Supplier<CompletableFuture<Optional<CommitModelsResult>>> operation) {
            GraphProjectionCommit projections = migration
                    ? GraphProjectionCommit.EMPTY
                    : graphProjections(attempt);
            CompletableFuture<Void> registrations = projections.roots().isEmpty()
                    ? COMPLETED_VOID
                    : CompletableFuture.allOf(
                            projections.roots().stream()
                                    .map(root -> DefaultModelRepository.this
                                            .registerGraphProjection(root.modelType(), false))
                                    .toArray(CompletableFuture[]::new));
            CompletableFuture<Optional<CommitModelsResult>> committed =
                    registrations == COMPLETED_VOID
                            ? trackLocalChanges(attempt, operation)
                            : registrations.thenCompose(message.captureContext().wrap(
                                    ignored -> trackLocalChanges(attempt, operation)));
            return projections.awaitedTargets().isEmpty()
                    ? committed
                    : committed.thenCompose(result ->
                            awaitGraphProjections(projections.awaitedTargets(), result));
        }

        private CompletableFuture<Optional<CommitModelsResult>> trackLocalChanges(
                CommitAttempt attempt,
                Supplier<CompletableFuture<Optional<CommitModelsResult>>> operation) {
            List<String> modelIds = attempt.transitions().size() == 1
                    ? List.of(attempt.transitions().getFirst().modelId())
                    : attempt.transitions().stream()
                            .map(Change::modelId).distinct().toList();
            Runnable complete = DefaultModelRepository.this.beginLocalCommit(modelIds);
            try {
                return Objects.requireNonNull(
                                operation.get(), "Model repository commit operation returned null")
                        .whenComplete((ignored, failure) -> complete.run());
            } catch (Throwable failure) {
                complete.run();
                throw failure;
            }
        }

        private CompletableFuture<Optional<CommitModelsResult>> awaitGraphProjections(
                Map<Class<?>, Set<String>> projections,
                Optional<CommitModelsResult> commitResult) {
            if (commitResult.isEmpty() || commitResult.get().getUpdates().isEmpty()) {
                return CompletableFuture.completedFuture(commitResult);
            }
            CommitModelsResult result = commitResult.get();
            return DefaultModelRepository.this.awaitGraphProjections(
                            projections,
                            result.getUpdates().getFirst().getStateIndex(),
                            result.getUpdates().getLast().getStateIndex())
                    .thenApply(ignored -> commitResult);
        }

        private GraphProjectionCommit graphProjections(CommitAttempt attempt) {
            GraphProjectionCompletion consumer = null;
            LinkedHashSet<EntityMetadata.GraphProjectionRoot> definitions =
                    new LinkedHashSet<>();
            LinkedHashMap<Class<?>, LinkedHashSet<String>> awaited = new LinkedHashMap<>();
            for (Change change : attempt.transitions()) {
                List<EntityMetadata.GraphProjectionRoot> roots =
                        EntityMetadata.graphProjectionRoots(change.modelType());
                if (roots.isEmpty()) {
                    continue;
                }
                definitions.addAll(roots);
                if (consumer == null) {
                    consumer = Tracker.current()
                            .map(Tracker::getConfiguration)
                            .map(configuration -> configuration.getGraphProjectionCompletion())
                            .orElse(GraphProjectionCompletion.DEFAULT);
                }
                for (EntityMetadata.GraphProjectionRoot root : roots) {
                    if (change.graphProjectionCompletion()
                            .orElse(consumer)
                            .orElse(root.projection().completion())
                            .orElse(graphProjectionCompletion) == GraphProjectionCompletion.AWAIT) {
                        awaited.computeIfAbsent(
                                        root.modelType(), ignored -> new LinkedHashSet<>())
                                .add(change.modelId());
                    }
                }
            }
            return definitions.isEmpty()
                    ? GraphProjectionCommit.EMPTY
                    : new GraphProjectionCommit(
                            Set.copyOf(definitions),
                            awaited.entrySet().stream().collect(
                                    java.util.stream.Collectors.toUnmodifiableMap(
                                            Map.Entry::getKey,
                                            entry -> Set.copyOf(entry.getValue()))));
        }

        private record GraphProjectionCommit(
                Set<EntityMetadata.GraphProjectionRoot> roots,
                Map<Class<?>, Set<String>> awaitedTargets) {
            private static final GraphProjectionCommit EMPTY =
                    new GraphProjectionCommit(Set.of(), Map.of());
        }

        private CompletableFuture<Void> processCommits(
                List<Outcome> committed) {
            DefaultModelRepository.this.updateAfterCommit(committed);
            return CompletableFuture.completedFuture(null);
        }

        public Outcome prepare(String commitId, CommitAttempt evaluation) {
            return prepare(commitId, evaluation, ModelConflictPolicy.ACCEPT);
        }

        public Outcome prepare(
                String commitId,
                CommitAttempt evaluation,
                ModelConflictPolicy conflictPolicy) {
            return prepare(commitId, evaluation, conflictPolicy, false);
        }

        public Outcome prepare(
                String commitId,
                CommitAttempt evaluation,
                ModelConflictPolicy conflictPolicy,
                boolean migration) {
            return doPrepare(commitId, evaluation, conflictPolicy, migration);
        }

        private Outcome doPrepare(
                String commitId,
                CommitAttempt evaluation,
                ModelConflictPolicy conflictPolicy,
                boolean migration) {
            Objects.requireNonNull(commitId, "commitId");
            if (commitId.isBlank()) {
                throw new IllegalArgumentException("Model commit ID must not be blank");
            }
            Objects.requireNonNull(evaluation, "evaluation");
            Objects.requireNonNull(conflictPolicy, "conflictPolicy");

            Map<String, List<Change>> graphPublications = new LinkedHashMap<>();
            Set<String> ordinaryEventIds = new java.util.HashSet<>();
            for (CommitAttempt.Step step : evaluation.steps()) {
                DeserializingMessage message = step.message();
                List<Change> transitions = step.changes().stream()
                        .peek(Change::validate)
                        .filter(Change::active)
                        .toList();
                if (transitions.isEmpty()) {
                    continue;
                }
                boolean direct = step.directMutation();
                String messageId = message.getMessageId();
                if (direct) {
                    List<Change> published = message.getPayload() instanceof Graph<?>
                            ? List.of()
                            : transitions.stream().filter(Change::publishEvent).toList();
                    if (!published.isEmpty()) {
                        graphPublications.computeIfAbsent(
                                messageId, ignored -> new ArrayList<>()).addAll(published);
                    }
                } else {
                    ordinaryEventIds.add(messageId);
                }
            }

            List<ModelCommitStep> protocolSteps = new ArrayList<>();
            Map<ModelCommitTarget, Change> preparedChanges = new IdentityHashMap<>();
            Map<String, Long> nextSequences = new LinkedHashMap<>();
            Set<String> cascadeRoots = evaluation.cascadeRootIds();
            for (CommitAttempt.Step step : evaluation.steps()) {
                DeserializingMessage message = step.message();
                List<Change> transitions = step.changes().stream()
                        .filter(Change::active).toList();
                if (transitions.isEmpty()) {
                    continue;
                }
                boolean direct = step.directMutation();
                List<Change> committedTransitions = direct
                        ? transitions.stream().map(transition -> transition.withEffects(
                                transition.storeEvent(), false, transition.updateState())).toList()
                        : transitions;
                List<Change> graphPublished = graphPublications.getOrDefault(
                        message.getMessageId(), List.of());
                if (direct && !graphPublished.isEmpty()
                    && !ordinaryEventIds.contains(message.getMessageId())) {
                    SerializedMessage publication = serialize(
                            message, commitId, protocolSteps.size(), false);
                    publication.setSource(source);
                    applyEventRouting(publication, graphPublished);
                    publication = BinaryWire.prepareEnvelope(publication);
                    Change anchor = graphPublished.getFirst();
                    ModelCommitTarget publicationTarget = target(
                            anchor.withEffects(false, true, false), message,
                            anchor.beforeSequenceNumber(), false, migration)
                            .toBuilder().expectedSequenceNumber(null).build();
                    protocolSteps.add(new ModelCommitStep(
                            publication, true, List.of(publicationTarget)));
                }
                Long existingEventIndex = existingEventIndex(message);
                boolean publishEvent = !direct
                                       && existingEventIndex == null
                                       && (transitions.stream().anyMatch(Change::publishEvent)
                                           || !graphPublished.isEmpty());
                boolean eventRequired = existingEventIndex != null
                                        || publishEvent
                                        || committedTransitions.stream().anyMatch(Change::storeEvent);
                SerializedMessage event = !eventRequired ? null
                        : direct ? serializeDirectModelUpdate(
                                message, committedTransitions, commitId, protocolSteps.size())
                        : serialize(
                                message, commitId, protocolSteps.size(),
                                transitions.stream().anyMatch(Change::cascadedDeletion));
                if (event != null) {
                    event.setSource(source);
                    if (!direct) {
                        List<Change> routingTransitions =
                                new ArrayList<>(transitions.size() + graphPublished.size());
                        routingTransitions.addAll(transitions);
                        routingTransitions.addAll(graphPublished);
                        applyEventRouting(event, routingTransitions);
                    }
                    event = BinaryWire.prepareEnvelope(event);
                }
                List<ModelCommitTarget> targets = new ArrayList<>(committedTransitions.size());
                for (Change transition : committedTransitions) {
                    ModelCommitTarget target = target(
                            transition, message, nextSequences,
                            cascadeRoots.contains(transition.modelId()),
                            migration);
                    targets.add(target);
                    preparedChanges.put(target, transition);
                }
                protocolSteps.add(new ModelCommitStep(event, publishEvent, List.copyOf(targets)));
            }
            boolean possibleDuplicate = possibleDuplicate(evaluation, preparedChanges.values());
            if (protocolSteps.isEmpty()) {
                return new Outcome(null, preparedChanges);
            }
            CommitModels commit = new CommitModels(
                    commitId, evaluation.readStateIndex(), evaluation.readModelIds(),
                    List.copyOf(protocolSteps), conflictPolicy, STORED,
                    possibleDuplicate, migration);
            return new Outcome(commit, preparedChanges);
        }

        public Outcome prepareRebased(
                String commitId,
                Outcome original,
                CommitAttempt evaluation) {
            if (original.commit() == null) {
                throw new IllegalArgumentException(
                        "Cannot rebase an empty model commit");
            }
            Outcome rebased = doPrepare(
                    commitId, evaluation, ModelConflictPolicy.ACCEPT,
                    original.commit().isMigration());
            requireSameShape(original, rebased);
            CommitModels candidate = rebased.commit();
            CommitModels commit = new CommitModels(
                    candidate.getCommitId(), candidate.getReadStateIndex(),
                    candidate.getReadModelIds(), candidate.getSubsteps(),
                    candidate.getConflictPolicy(), original.commit().getGuarantee(),
                    original.commit().isPossibleDuplicate(),
                    original.commit().isMigration());
            return new Outcome(commit, rebased.changes);
        }

        private static void requireSameShape(
                Outcome original,
                Outcome rebased) {
            if (rebased.commit() == null
                || original.commit().getSubsteps().size()
                   != rebased.commit().getSubsteps().size()) {
                throw changedRebaseShape();
            }
            for (int substep = 0;
                 substep < original.commit().getSubsteps().size();
                 substep++) {
                ModelCommitStep before = original.commit().getSubsteps().get(substep);
                ModelCommitStep after = rebased.commit().getSubsteps().get(substep);
                if (before.isPublishEvent() != after.isPublishEvent()
                    || before.getTargets().size() != after.getTargets().size()) {
                    throw changedRebaseShape();
                }
                for (int target = 0; target < before.getTargets().size(); target++) {
                    ModelCommitTarget left = before.getTargets().get(target);
                    ModelCommitTarget right = after.getTargets().get(target);
                    if (!left.getModelId().equals(right.getModelId())
                        || !left.getModelType().equals(right.getModelType())
                        || left.isStoreEvent() != right.isStoreEvent()
                        || left.isUpdateState() != right.isUpdateState()) {
                        throw changedRebaseShape();
                    }
                }
            }
        }

        private static IllegalStateException changedRebaseShape() {
            return new IllegalStateException(
                    "Apply-only rebase changed the model commit shape");
        }

        private ModelCommitTarget target(
                Change transition,
                DeserializingMessage message,
                Map<String, Long> nextSequences,
                boolean cascadeDelete,
                boolean migration) {
            return target(
                    transition,
                    message,
                    nextSequence(transition, nextSequences),
                    cascadeDelete, migration);
        }

        private ModelCommitTarget target(
                Change transition,
                DeserializingMessage message,
                long nextSequence,
                boolean cascadeDelete,
                boolean migration) {
            ModelDocumentMutation document = transition.updateState()
                    && (existingEventIndex(message) == null
                        || migration)
                    ? directDocument(
                            transition,
                            message.getTimestamp(), message.getMetadata())
                    : null;
            RelationshipUpdate relationships = transition.updateState()
                    ? relationshipUpdate(transition)
                    : RelationshipUpdate.UNCHANGED;
            ModelSnapshotMutation snapshot = snapshot(
                    transition, nextSequence,
                    message.getTimestamp());
            List<String> aliases = transition.updateState()
                    ? transition.metadata().aliases(transition.after())
                    : null;
            return new ModelCommitTarget(
                    transition.modelId(),
                    transition.modelType().getName(),
                    transition.beforeSequenceNumber(),
                    transition.storeEvent(),
                    transition.updateState(),
                    transition.updateState()
                    && transition.after() == null,
                    cascadeDelete
                    && transition.updateState()
                    && transition.after() == null,
                    document,
                    snapshot,
                    relationships.update(),
                    relationships.relationships(),
                    aliases);
        }

        private SerializedMessage serialize(
                DeserializingMessage message,
                String commitId,
                int substep,
                boolean internalLifecycleEvent) {
            SerializedMessage source = message.getSerializedObject(serializer);
            io.fluxzero.sdk.common.Message logicalMessage =
                    message.toMessage();
            /*
             * The applied update is the event payload. Tracked commands already carry the
             * current, post-upcast serialized representation, while intercepted updates
             * lazily create that representation through getSerializedObject(). Reusing it
             * avoids serializing every automatic model update a second time. Only payload
             * data is shared: transport/tracking fields intentionally stay behind on the
             * command and event dispatch interceptors still receive an independent message.
             */
            SerializedMessage candidate = new SerializedMessage(
                    source.getData(),
                    logicalMessage.getMetadata(),
                    logicalMessage.getMessageId(),
                    logicalMessage.getTimestamp().toEpochMilli());
            Long existingEventIndex = existingEventIndex(message);
            if (existingEventIndex != null) {
                candidate.setIndex(existingEventIndex);
                return candidate;
            }
            SerializedMessage serialized = internalLifecycleEvent
                    ? candidate
                    : dispatchInterceptor.modifySerializedMessage(
                            candidate, logicalMessage, EVENT, null);
            if (serialized == null) {
                throw new IllegalStateException(
                        "Serialized model event was suppressed after @Apply evaluation; "
                        + "logical event suppression must happen before model applies");
            }
            serialized.setMetadata(serialized.getMetadata().with(
                    ModelEventMetadata.COMMIT_ID, commitId,
                    ModelEventMetadata.SUBSTEP, substep));
            return serialized;
        }

        private static Long existingEventIndex(
                DeserializingMessage message) {
            return message.getMessageType() == EVENT
                   ? message.getIndex() : null;
        }

        private SerializedMessage serializeDirectModelUpdate(
                DeserializingMessage sourceMessage,
                List<Change> transitions,
                String commitId,
                int substep) {
            List<DirectModelUpdate.Target> targets = transitions.stream()
                    .filter(transition -> transition.storeEvent())
                    .map(transition -> new DirectModelUpdate.Target(
                            transition.modelId(),
                            transition.after() == null
                                    ? null
                                    : serializer.serialize(
                                            transition.after())))
                    .toList();
            if (targets.isEmpty()) {
                return null;
            }
            io.fluxzero.sdk.common.Message logical = sourceMessage.toMessage();
            DeserializingMessage direct = new DeserializingMessage(
                    new io.fluxzero.sdk.common.Message(
                            new DirectModelUpdate(targets),
                            logical.getMetadata(),
                            logical.getMessageId() + "$direct-model-update",
                            logical.getTimestamp()),
                    EVENT, null, serializer);
            return serialize(direct, commitId, substep, true);
        }

        private static boolean possibleDuplicate(
                CommitAttempt evaluation,
                Collection<Change> changes) {
            Long sourceIndex = DeserializingMessage.getOptionally()
                    .map(DeserializingMessage::getIndex)
                    .orElse(null);
            if (sourceIndex == null
                || changes.stream().anyMatch(transition ->
                        !transition.storeEvent() || !transition.publishEvent())) {
                return true;
            }
            return evaluation.transitions().stream()
                    .map(Change::beforeLastEventIndex)
                    .filter(Objects::nonNull)
                    .anyMatch(index -> index >= sourceIndex);
        }

        private static void applyEventRouting(
                SerializedMessage event,
                List<Change> transitions) {
            List<Change> published = transitions.stream()
                    .filter(transition -> transition.publishEvent()).toList();
            if (published.isEmpty()) {
                return;
            }
            boolean aggregateIdRouting = published.stream()
                    .anyMatch(transition -> transition.eventRouting()
                            == AggregateEventRouting.AGGREGATE_ID);
            boolean messageRouting = published.stream()
                    .anyMatch(transition -> transition.eventRouting()
                            == AggregateEventRouting.MESSAGE_ROUTING_KEY);
            if (aggregateIdRouting && (messageRouting || published.size() != 1)) {
                throw new IllegalStateException(
                        "One model event cannot use conflicting aggregate-ID routing for multiple published targets");
            }
            if (aggregateIdRouting) {
                event.setSegment(ConsistentHashing.computeSegment(
                        published.getFirst().modelId()));
            }
        }

        private static RelationshipUpdate relationshipUpdate(
                Change transition) {
            EntityMetadata metadata = transition.metadata();
            if (metadata.parentReferences().isEmpty()) {
                return transition.after() == null
                        ? RelationshipUpdate.CLEARED
                        : RelationshipUpdate.UNCHANGED;
            }
            List<ModelRelationship> before = metadata.parentRelationships(
                            transition.modelId(), transition.before()).stream()
                    .map(EntityMetadata.ParentRelationship::asCommitRelationship)
                    .toList();
            List<ModelRelationship> after = metadata.parentRelationships(
                            transition.modelId(), transition.after()).stream()
                    .map(EntityMetadata.ParentRelationship::asCommitRelationship)
                    .toList();
            boolean update = transition.after() == null
                             || !before.equals(after);
            return new RelationshipUpdate(
                    update, update ? after : List.of());
        }

        private static long nextSequence(
                Change transition,
                Map<String, Long> nextSequences) {
            long previous = nextSequences.getOrDefault(
                    transition.modelId(),
                    transition.beforeSequenceNumber());
            long result = previous
                          + (transition.storeEvent()
                                     ? 1L : 0L);
            nextSequences.put(
                    transition.modelId(), result);
            return result;
        }

        private ModelSnapshotMutation snapshot(
                Change transition,
                long nextSequence,
                Instant timestamp) {
            EntityMetadata.RootConfiguration model = transition.configuration();
            EntityMetadata.SnapshotSettings snapshotSettings = model.snapshotSettings(false);
            if (snapshotSerializer == null
                || !model.eventSourced()
                || !transition.storeEvent()
                || transition.after() == null
                || !snapshotSettings.due(nextSequence, 1)) {
                return null;
            }
            return new ModelSnapshotMutation(
                    snapshotSerializer.serialize(
                            transition.after()),
                    timestamp.toEpochMilli(),
                    snapshotSettings.period(),
                    snapshotSettings.maxCount());
        }

        private ModelDocumentMutation directDocument(
                Change transition,
                Instant eventTimestamp,
                Metadata metadata) {
            EntityMetadata.RootConfiguration model = transition.configuration();
            String collection = documentCollection(transition);
            if (collection == null) {
                return null;
            }
            Object value = transition.after();
            if (value == null) {
                return new ModelDocumentMutation(collection, null);
            }
            Instant begin = parseTimeProperty(
                    blankToNull(model.timestampPath()), value, false, () -> eventTimestamp);
            Instant end = parseTimeProperty(
                    blankToNull(model.endPath()), value, true, () -> begin);
            return new ModelDocumentMutation(
                    collection,
                    documentSerializer.toDocument(
                            value, transition.modelId(), collection,
                            begin, end, metadata));
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private String documentCollection(Change transition) {
            return documentCollections.computeIfAbsent(
                    transition.metadata().type(),
                    ignored -> transition.metadata().modelDocumentCollection())
                    .orElse(null);
        }

        /** The one repository-owned carrier from prepared request through authoritative accepted result. */
        public static final class Outcome {
            private final CommitModels commit;
            private final Map<ModelCommitTarget, Change> changes;
            private final CommitModelsResult result;
            private final List<CommittedRevision> revisions;

            private Outcome(
                    CommitModels commit,
                    Map<ModelCommitTarget, Change> changes) {
                this(commit, changes, null, List.of());
            }

            private Outcome(
                    CommitModels commit,
                    Map<ModelCommitTarget, Change> changes,
                    CommitModelsResult result,
                    List<CommittedRevision> revisions) {
                this.commit = commit;
                this.changes = new IdentityHashMap<>(Objects.requireNonNull(changes));
                this.result = result;
                this.revisions = revisions;
            }

            public CommitModels commit() {
                return commit;
            }

            public Collection<Change> changes() {
                return Collections.unmodifiableCollection(changes.values());
            }

            public boolean hasCascadedDeletion() {
                return changes.values().stream().anyMatch(Change::cascadedDeletion);
            }

            public CommitModelsResult result() {
                return result;
            }

            Outcome accepted(CommitModelsResult accepted) {
                if (!accepted.isAccepted()) {
                    throw new IllegalArgumentException(
                            "A repository commit outcome requires an accepted result");
                }
                if (commit.getSubsteps().size() != accepted.getUpdates().size()) {
                    throw new IllegalStateException(
                            "Model commit returned a different number of substeps than requested");
                }
                List<CommittedRevision> revisions = new ArrayList<>();
                for (int substep = 0; substep < commit.getSubsteps().size(); substep++) {
                    ModelCommitStep requestStep = commit.getSubsteps().get(substep);
                    ModelUpdate resultStep = accepted.getUpdates().get(substep);
                    if (requestStep.getTargets().size() != resultStep.getTargets().size()) {
                        throw new IllegalStateException(
                                "Model commit returned a different number of targets than requested");
                    }
                    long timestamp = requestStep.getEvent() == null
                            ? System.currentTimeMillis()
                            : requestStep.getEvent().getTimestamp();
                    for (int target = 0; target < requestStep.getTargets().size(); target++) {
                        Change change = changes.get(requestStep.getTargets().get(target));
                        if (change != null && change.updateState()) {
                            revisions.add(new CommittedRevision(
                                    change, requestStep, resultStep,
                                    resultStep.getTargets().get(target), timestamp));
                        }
                    }
                }
                return new Outcome(
                        commit, changes, accepted, List.copyOf(revisions));
            }

            List<CommittedRevision> revisions() {
                return revisions;
            }

        }

        private record RelationshipUpdate(
                boolean update,
                List<ModelRelationship> relationships) {
            private static final RelationshipUpdate UNCHANGED =
                    new RelationshipUpdate(false, List.of());
            private static final RelationshipUpdate CLEARED =
                    new RelationshipUpdate(true, List.of());
        }
    }
}
