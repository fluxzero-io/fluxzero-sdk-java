/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetAggregateIds;
import io.fluxzero.common.api.modeling.GetModelChange;
import io.fluxzero.common.api.modeling.GetModelChangeResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Low-level client interface for interacting with the event store in Fluxzero.
 * <p>
 * This interface provides operations for storing, retrieving, and deleting event streams related to event-sourced
 * aggregates, as well as managing entity relationships such as aggregate references and links.
 * <p>
 * Users rarely interact with this interface directly. Instead, they typically use higher-level abstractions such as:
 * <ul>
 *   <li>{@link io.fluxzero.sdk.persisting.eventsourcing.EventStore}</li>
 *   <li>{@link io.fluxzero.sdk.persisting.repository.AggregateRepository}</li>
 *   <li>Static methods on {@link io.fluxzero.sdk.Fluxzero}, e.g. {@code Fluxzero.loadAggregate(...)}.</li>
 * </ul>
 *
 * <p>This interface is backed either by:
 * <ul>
 *   <li>a connection to the Fluxzero Runtime using a websocket-based implementation, or</li>
 *   <li>an in-memory store used for testing or standalone development purposes.</li>
 * </ul>
 *
 * @see io.fluxzero.sdk.persisting.eventsourcing.EventStore
 * @see io.fluxzero.sdk.persisting.repository.AggregateRepository
 */
public interface EventStoreClient extends AutoCloseable {

    /**
     * Atomically commits the ordered state transitions of one independent-model commit.
     * <p>
     * Unlike aggregate event appends, this operation returns the runtime-assigned state, event, and per-model stream
     * positions. Implementations predating independent models remain source-compatible and fail only when this
     * capability is invoked.
     *
     * @param commit complete model commit with its durable idempotency key
     * @return durable result containing the positions assigned by the event store
     */
    default CompletableFuture<CommitModelsResult> commitModels(CommitModels commit) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Independent model commits are not supported by this event store"));
    }

    /**
     * Batch-loads independent model heads and event memberships at one pinned state boundary.
     */
    default GetModelEventsResult getModelEvents(GetModelEvents request) {
        throw new UnsupportedOperationException("Independent model event loading is not supported by this event store");
    }

    /**
     * Long-polls committed independent-model commit substeps after a client-controlled state cursor.
     */
    default CompletableFuture<TrackModelUpdatesResult> trackModelUpdates(
            TrackModelUpdates request) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Independent model update tracking is not supported by this event store"));
    }

    /**
     * Loads one bounded temporal model graph and an optional first page of its grouped model streams.
     */
    default GetModelGraphResult getModelGraph(GetModelGraph request) {
        throw new UnsupportedOperationException("Independent model graph loading is not supported by this event store");
    }

    /** Resolves the durable targets of one model-commit substep. */
    default GetModelChangeResult getModelChange(GetModelChange request) {
        throw new UnsupportedOperationException(
                "Independent model change lookup is not supported by this event store");
    }

    /**
     * Idempotently registers an asynchronous materialized model-graph projection.
     */
    default CompletableFuture<ModelGraphProjectionStatus>
            registerModelGraphProjection(
                    RegisterModelGraphProjection request) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Materialized model graph projections are not supported by this event store"));
    }

    /**
     * Returns the current high-watermark and backlog for one materialized graph projection.
     */
    default ModelGraphProjectionStatus
            getModelGraphProjectionStatus(
                    GetModelGraphProjectionStatus request) {
        throw new UnsupportedOperationException(
                "Materialized model graph projection status is not supported by this event store");
    }

    /**
     * Completes after every affected root in a graph projection has crossed the requested committed state boundary.
     */
    default CompletableFuture<ModelGraphProjectionStatus>
            awaitModelGraphProjection(
                    AwaitModelGraphProjection request) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Materialized model graph projection completion is not supported by this event store"));
    }

    /**
     * Creates a bounded, non-mutating plan for an explicit independent-model hard deletion.
     */
    default ModelDeletionPlan planModelDeletion(
            PlanModelDeletion request) {
        throw new UnsupportedOperationException(
                "Independent model deletion planning is not supported by this event store");
    }

    /**
     * Executes or resumes an idempotent independent-model hard deletion.
     */
    default CompletableFuture<ModelDeletionResult> deleteModel(
            DeleteModel request) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Independent model hard deletion is not supported by this event store"));
    }

    /**
     * Stores a list of serialized events for a given aggregate identifier.
     *
     * @param aggregateId The ID of the aggregate.
     * @param events      The serialized events to store.
     * @param storeOnly   Whether to store the events without publishing them.
     * @return A future that completes when the operation is acknowledged.
     */
    default CompletableFuture<Void> storeEvents(String aggregateId, List<SerializedMessage> events, boolean storeOnly) {
        return storeEvents(aggregateId, events, storeOnly, Guarantee.STORED);
    }

    /**
     * Stores events for a given aggregate with an explicit guarantee.
     *
     * @param aggregateId The aggregate ID.
     * @param events      Events to store.
     * @param storeOnly   If {@code true}, events will not be published.
     * @param guarantee   The guarantee level for this operation.
     * @return A future representing completion of the store operation.
     */
    CompletableFuture<Void> storeEvents(String aggregateId, List<SerializedMessage> events, boolean storeOnly,
                                        Guarantee guarantee);

    /**
     * Retrieves the full event stream for a given aggregate.
     *
     * @param aggregateId The aggregate ID.
     * @return A stream of serialized events.
     */
    default AggregateEventStream<SerializedMessage> getEvents(String aggregateId) {
        return getEvents(aggregateId, -1L);
    }

    /**
     * Retrieves the event stream for an aggregate starting after the given sequence number.
     *
     * @param aggregateId        The aggregate ID.
     * @param lastSequenceNumber The sequence number to start from (exclusive).
     * @return A stream of serialized events.
     */
    default AggregateEventStream<SerializedMessage> getEvents(String aggregateId, long lastSequenceNumber) {
        return getEvents(aggregateId, lastSequenceNumber, -1);
    }

    /**
     * Retrieves the event stream for an aggregate with control over size and offset.
     *
     * @param aggregateId        The aggregate ID.
     * @param lastSequenceNumber Sequence number to resume after.
     * @param maxSize            Maximum number of events to return (or -1 for unlimited).
     * @return A stream of serialized events.
     */
    AggregateEventStream<SerializedMessage> getEvents(String aggregateId, long lastSequenceNumber, int maxSize);

    /**
     * Deletes all events for a specific aggregate.
     *
     * @param aggregateId The aggregate ID.
     * @return A future that completes when deletion is acknowledged.
     */
    default CompletableFuture<Void> deleteEvents(String aggregateId) {
        return deleteEvents(aggregateId, Guarantee.STORED);
    }

    /**
     * Deletes all events for a specific aggregate with a given delivery guarantee.
     *
     * @param aggregateId The aggregate ID.
     * @param guarantee   The guarantee to apply.
     * @return A future that completes when deletion is acknowledged.
     */
    CompletableFuture<Void> deleteEvents(String aggregateId, Guarantee guarantee);

    /**
     * Updates entity relationships in the event store (e.g. parent-child, references).
     *
     * @param request The update request.
     * @return A future that completes when the operation is acknowledged.
     */
    CompletableFuture<Void> updateRelationships(UpdateRelationships request);

    /**
     * Repairs entity relationships, e.g. by forcing re-evaluation of existing relationships.
     *
     * @param request The repair request.
     * @return A future that completes when the repair is done.
     */
    CompletableFuture<Void> repairRelationships(RepairRelationships request);

    /**
     * Gets a map of aggregate IDs that reference a given entity ID.
     *
     * @param entityId The entity identifier.
     * @return A map of aggregate IDs and corresponding reference names.
     */
    default Map<String, String> getAggregatesFor(String entityId) {
        return getAggregateIds(new GetAggregateIds(entityId));
    }

    /**
     * Gets aggregate IDs based on a {@link GetAggregateIds} request.
     *
     * @param request The request containing filtering options.
     * @return A map of aggregate IDs referencing the target entity.
     */
    Map<String, String> getAggregateIds(GetAggregateIds request);

    /**
     * Gets relationships for the given entity.
     *
     * @param entityId The entity ID.
     * @return A list of matching relationships.
     */
    default List<Relationship> getRelationships(String entityId) {
        return getRelationships(new GetRelationships(entityId));
    }

    /**
     * Gets relationships based on a {@link GetRelationships} request.
     *
     * @param request The request containing filter parameters.
     * @return A list of matching relationships.
     */
    List<Relationship> getRelationships(GetRelationships request);

    /**
     * Closes the client and releases any open resources or connections.
     */
    @Override
    void close();
}
