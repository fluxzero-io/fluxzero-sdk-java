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
 *
 */

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.eventsourcing.AppendEvents;
import io.fluxzero.common.api.eventsourcing.DeleteEvents;
import io.fluxzero.common.api.eventsourcing.EventBatch;
import io.fluxzero.common.api.eventsourcing.GetEvents;
import io.fluxzero.common.api.eventsourcing.GetEventsResult;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetAggregateIds;
import io.fluxzero.common.api.modeling.GetAggregateIdsResult;
import io.fluxzero.common.api.modeling.GetModelChange;
import io.fluxzero.common.api.modeling.GetModelChangeResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.GetRelationshipsResult;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelWebSocketCodec;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
public class EventSourcingEndpoint extends WebsocketEndpoint {

    private final EventStoreClient eventStore;

    @Override
    protected List<ModelWebSocketCodec> payloadCodecs() {
        return List.of(ModelWebSocketCodec.INSTANCE);
    }

    public EventSourcingEndpoint(EventStoreClient eventStore, CommandIdempotencyStore commandIdempotencyStore) {
        super(commandIdempotencyStore);
        this.eventStore = eventStore;
    }

    @Handle
    CompletableFuture<Void> handle(AppendEvents appendEvents) {
        return CompletableFuture.allOf(appendEvents.getEventBatches().stream().map(b -> eventStore
                .storeEvents(b.getAggregateId(), b.getEvents(), b.isStoreOnly(), appendEvents.getGuarantee()))
                                               .toArray(CompletableFuture[]::new));
    }

    @Handle
    CompletableFuture<Void> handle(DeleteEvents deleteEvents) {
        return eventStore.deleteEvents(deleteEvents.getAggregateId(), deleteEvents.getGuarantee());
    }

    @Handle
    GetEventsResult handle(GetEvents getEvents) {
        AggregateEventStream<SerializedMessage> stream = eventStore
                .getEvents(getEvents.getAggregateId(), getEvents.getLastSequenceNumber());
        long lastSequenceNumber = stream.getLastSequenceNumber().orElse(-1L);
        return new GetEventsResult(getEvents.getRequestId(), new EventBatch(
                getEvents.getAggregateId(), stream.collect(Collectors.toList()), false), lastSequenceNumber);
    }

    @Handle
    CompletableFuture<Void> handle(UpdateRelationships request) {
        return eventStore.updateRelationships(request);
    }

    @Handle
    CompletableFuture<Void> handle(RepairRelationships request) {
        return eventStore.repairRelationships(request);
    }

    @Handle
    GetAggregateIdsResult handle(GetAggregateIds request) {
        return new GetAggregateIdsResult(request.getRequestId(), eventStore.getAggregateIds(request));
    }

    @Handle
    GetRelationshipsResult handle(GetRelationships request) {
        return new GetRelationshipsResult(request.getRequestId(), eventStore.getRelationships(request));
    }

    @Handle
    CompletableFuture<CommitModelsResult> handle(
            CommitModels request) {
        return eventStore.commitModels(
                request);
    }

    @Handle
    GetModelEventsResult handle(
            GetModelEvents request) {
        return eventStore.getModelEvents(
                request);
    }

    @Handle
    CompletableFuture<TrackModelUpdatesResult>
            handle(TrackModelUpdates request) {
        return eventStore.trackModelUpdates(
                request);
    }

    @Handle
    GetModelGraphResult handle(
            GetModelGraph request) {
        return eventStore.getModelGraph(
                request);
    }

    @Handle
    GetModelChangeResult handle(
            GetModelChange request) {
        return eventStore.getModelChange(request);
    }

    @Handle
    CompletableFuture<ModelGraphProjectionStatus>
            handle(
                    RegisterModelGraphProjection
                            request) {
        return eventStore
                .registerModelGraphProjection(
                        request);
    }

    @Handle
    ModelGraphProjectionStatus handle(
            GetModelGraphProjectionStatus
                    request) {
        return eventStore
                .getModelGraphProjectionStatus(
                        request);
    }

    @Handle
    CompletableFuture<ModelGraphProjectionStatus>
            handle(
                    AwaitModelGraphProjection request) {
        return eventStore
                .awaitModelGraphProjection(
                        request);
    }

    @Handle
    ModelDeletionPlan handle(
            PlanModelDeletion request) {
        return eventStore.planModelDeletion(
                request);
    }

    @Handle
    CompletableFuture<ModelDeletionResult> handle(
            DeleteModel request) {
        return eventStore.deleteModel(
                request);
    }
}
