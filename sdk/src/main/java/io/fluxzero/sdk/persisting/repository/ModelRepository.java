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

import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.sdk.common.Namespaced;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.CommitAttempt;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.MutationPlan;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for loading independently stored {@link Model models}.
 * <p>
 * Model identity combines the repository representation of its {@code @EntityId} value with any explicit
 * {@link io.fluxzero.sdk.modeling.EntityId#prefix() prefix} and
 * {@link io.fluxzero.sdk.modeling.EntityId#postfix() postfix}. Loads first match that primary identity and then, when
 * no such model exists, a current value declared with {@link Alias @Alias}.
 */
public interface ModelRepository extends Namespaced<ModelRepository> {

    /**
     * Returns this repository scoped to the requested namespace.
     * <p>
     * Custom repositories that are not namespace-aware retain their behavior by returning the same instance.
     */
    @Override
    default ModelRepository forNamespace(String namespace) {
        return this;
    }

    /**
     * Loads a model using the type carried by a typed identifier.
     */
    default <T> Entity<T> load(@NonNull Id<T> modelId) {
        return load((Object) modelId, modelId.getType());
    }

    /**
     * Loads a model by ID, inferring its requested type when the ID is typed.
     * <p>
     * An untyped ID requests {@link Object}. Its concrete type is resolved from the durable model head. If no model
     * exists, an empty {@link Entity} of type {@link Object} is returned.
     */
    @SuppressWarnings("unchecked")
    default <T> Entity<T> load(@NotNull Object modelId) {
        return (Entity<T>) (modelId instanceof Id<?> id
                ? load((Object) id, (Class<Object>) id.getType())
                : load(modelId.toString(), Object.class));
    }

    /**
     * Loads a model using the string representation of the supplied ID, resolving a current model alias when no primary
     * model has that identity.
     */
    default <T> Entity<T> load(@NonNull Object modelId, @NonNull Class<T> modelType) {
        String functionalId = modelId.toString();
        EntityMetadata metadata = EntityMetadata.of(modelType);
        String primaryId = metadata.entityId().isEmpty()
                ? functionalId : metadata.repositoryId(modelId);
        Entity<T> result = load(primaryId, modelType);
        return result.isPresent() || primaryId.equals(functionalId) || !metadata.hasAliases()
                ? result : load(functionalId, modelType);
    }

    /**
     * Loads a parent-scoped model by its functional child ID and explicit parent type.
     * <p>
     * Ordinary model types ignore the parent and retain their normal identity. A parent-scoped model uses the same
     * collision-safe persisted identity as automatic apply handling.
     */
    default <T> Entity<T> load(
            @NonNull Object parentId, @NonNull Class<?> parentType,
            @NonNull Object modelId, @NonNull Class<T> modelType) {
        String primaryId = EntityMetadata.of(modelType)
                .repositoryId(modelId, parentId, parentType);
        return load(primaryId, modelType);
    }

    /**
     * Loads a model by primary ID or current alias and expected type. A primary model ID always takes precedence over
     * an alias with the same value.
     *
     * @param modelId   persisted model key or current alias; never decorated with model type metadata
     * @param modelType expected model type, or {@link Object} when it should be resolved from storage
     */
    <T> Entity<T> load(@NonNull String modelId, @NonNull Class<T> modelType);

    /**
     * Loads several models at one coherent state boundary, preserving input order.
     *
     * <p>Repositories without coherent multi-model reconstruction reject this capability instead of emulating it with
     * independent reads at different boundaries.</p>
     */
    default <T> List<Entity<T>> loadAll(
            @NonNull List<?> modelIds,
            @NonNull Class<T> modelType) {
        throw new UnsupportedOperationException(
                "Coherent multi-model reconstruction is not supported by this repository");
    }

    /**
     * Loads all model parameters for one selected message handler at one repository boundary.
     * <p>
     * Event and notification handlers carrying model-commit metadata must be reconstructed at that exact commit
     * boundary. Other handlers use one current load context. Implementations should batch direct targets and ancestor
     * traversal rather than loading each parameter independently.
     */
    default CommitAttempt loadContext(
            @NonNull MutationPlan.Resolution
                    resolution) {
        throw new UnsupportedOperationException(
                "Coherent model handler parameter loading is not supported by this repository");
    }

    /**
     * Creates a bounded, non-mutating plan for an explicit model hard deletion.
     * <p>
     * A descendant cascade must be planned and confirmed before execution. The returned published-event count makes
     * clear that globally published events are outside the model-stream erasure boundary.
     */
    default ModelDeletionPlan planDeletion(
            @NonNull Object modelId,
            @NonNull ModelDeletionCascade cascade) {
        throw new UnsupportedOperationException(
                "Independent model deletion planning is not supported by this repository");
    }

    /**
     * Hard-deletes exactly one model.
     * <p>
     * Passing {@link ModelDeletionCascade#DESCENDANTS} without a confirmed plan is rejected. Use
     * {@link #deleteModel(ModelDeletionPlan)} for descendant cascades.
     */
    default CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull Object modelId,
            @NonNull ModelDeletionCascade cascade) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Independent model hard deletion is not supported by this repository"));
    }

    /**
     * Executes or resumes an exact-model hard deletion using an explicit durable idempotency key.
     * Descendant deletion still requires a confirmed plan.
     */
    default CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull String deletionId,
            @NonNull Object modelId,
            @NonNull ModelDeletionCascade cascade) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Independent model hard deletion is not supported by this repository"));
    }

    /**
     * Executes a previously confirmed hard-deletion plan with a new durable idempotency key.
     */
    default CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull ModelDeletionPlan plan) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Independent model hard deletion is not supported by this repository"));
    }

    /**
     * Executes or resumes a confirmed plan using an explicit durable idempotency key.
     */
    default CompletableFuture<ModelDeletionResult> deleteModel(
            @NonNull String deletionId,
            @NonNull ModelDeletionPlan plan) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Independent model hard deletion is not supported by this repository"));
    }

    /**
     * Registers the graph projection declared by the supplied model type.
     *
     * @param modelType model carrying an enabled graph projection
     * @param rebuild whether all current roots should be scanned even if the definition is unchanged
     */
    default CompletableFuture<ModelGraphProjectionStatus>
            registerGraphProjection(
                    @NonNull Class<?> modelType,
                    boolean rebuild) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Materialized model graph projections are not supported by this repository"));
    }

    /**
     * Registers a changed graph definition and rebuilds all current roots.
     */
    default CompletableFuture<ModelGraphProjectionStatus>
            registerGraphProjection(
                    @NonNull Class<?> modelType) {
        return registerGraphProjection(
                modelType, true);
    }

    /**
     * Returns current graph-projection freshness for the supplied model type.
     */
    default ModelGraphProjectionStatus
            graphProjectionStatus(
                    @NonNull Class<?> modelType) {
        throw new UnsupportedOperationException(
                "Materialized model graph projections are not supported by this repository");
    }

    /**
     * Reconstructs a model and every related descendant at one state boundary. Pathless relationships participate in
     * typed traversal but remain absent from serialized graph documents.
     * Pending changes from earlier messages in the same ordered tracking segment are included. Use
     * {@link #loadGraphAt(Id, long)} when an exact durable historical boundary is required.
     */
    default <T> Graph<T> loadGraph(@NonNull Id<T> rootId) {
        return loadGraph(
                EntityMetadata.of(rootId.getType()).repositoryId(rootId),
                rootId.getType(), Graph.Options.DEFAULT);
    }

    /**
     * Reconstructs a model graph at an inclusive historical model-state boundary.
     */
    default <T> Graph<T> loadGraphAt(
            @NonNull Id<T> rootId,
            long stateIndex) {
        return loadGraphAt(
                rootId, stateIndex,
                Graph.Options.DEFAULT);
    }

    /**
     * Reconstructs a model graph at an inclusive historical model-state boundary with optional caller-imposed limits.
     */
    default <T> Graph<T> loadGraphAt(
            @NonNull Id<T> rootId,
            long stateIndex,
            @NonNull Graph.Options options) {
        return loadGraphAt(
                EntityMetadata.of(rootId.getType()).repositoryId(rootId), rootId.getType(),
                stateIndex, options);
    }

    /**
     * Reconstructs a model graph using exact persisted identity, root type, and optional caller-imposed limits.
     * Pending changes from earlier messages in the same ordered tracking segment are included.
     */
    default <T> Graph<T> loadGraph(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            @NonNull Graph.Options options) {
        return loadGraph(
                rootId, rootType,
                ModelReadBoundary.current(), options);
    }

    /**
     * Reconstructs one model graph at the supplied current, state, commit, event, or before boundary.
     */
    default <T> Graph<T> loadGraph(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            @NonNull ModelReadBoundary boundary,
            @NonNull Graph.Options options) {
        throw new UnsupportedOperationException(
                "Independent model graph reconstruction is not supported by this repository");
    }

    /**
     * Reconstructs a model graph using exact persisted identity, root type, an inclusive historical boundary, and
     * optional caller-imposed limits.
     */
    default <T> Graph<T> loadGraphAt(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long stateIndex,
            @NonNull Graph.Options options) {
        return loadGraph(
                rootId, rootType,
                ModelReadBoundary.state(stateIndex, false), options);
    }

    /**
     * Reconstructs the model graph that was current immediately before an opaque state boundary.
     */
    default <T> Graph<T> loadGraphBefore(
            @NonNull String rootId,
            @NonNull Class<T> rootType,
            long stateIndex,
            @NonNull Graph.Options options) {
        return loadGraph(
                rootId, rootType,
                ModelReadBoundary.state(stateIndex, false).asBefore(), options);
    }

}
