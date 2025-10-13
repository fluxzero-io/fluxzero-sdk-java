/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.fluxzero.common.api.eventsourcing.AppendEvents;
import io.fluxzero.common.api.eventsourcing.DeleteEvents;
import io.fluxzero.common.api.eventsourcing.GetEvents;
import io.fluxzero.common.api.eventsourcing.GetEventsResult;
import io.fluxzero.common.api.keyvalue.DeleteValue;
import io.fluxzero.common.api.keyvalue.GetValue;
import io.fluxzero.common.api.keyvalue.GetValueResult;
import io.fluxzero.common.api.keyvalue.StoreValueIfAbsent;
import io.fluxzero.common.api.keyvalue.StoreValues;
import io.fluxzero.common.api.modeling.GetAggregateIds;
import io.fluxzero.common.api.modeling.GetAggregateIdsResult;
import io.fluxzero.common.api.modeling.GetRelationships;
import io.fluxzero.common.api.modeling.GetRelationshipsResult;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.common.api.publishing.Append;
import io.fluxzero.common.api.publishing.SetRetentionTime;
import io.fluxzero.common.api.scheduling.CancelSchedule;
import io.fluxzero.common.api.scheduling.GetSchedule;
import io.fluxzero.common.api.scheduling.GetScheduleResult;
import io.fluxzero.common.api.scheduling.Schedule;
import io.fluxzero.common.api.search.*;
import io.fluxzero.common.api.tracking.ClaimSegment;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.DisconnectTracker;
import io.fluxzero.common.api.tracking.GetPosition;
import io.fluxzero.common.api.tracking.GetPositionResult;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.ReadFromIndex;
import io.fluxzero.common.api.tracking.ReadFromIndexResult;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.api.tracking.ResetPosition;
import io.fluxzero.common.api.tracking.StorePosition;

/**
 * Marker interface for all low-level request and response types in the Fluxzero protocol.
 * <p>
 * Each implementation of {@code JsonType} represents a command, query, or result that can be sent to or received from
 * the Fluxzero Runtime. These types are serialized using polymorphic JSON with the {@code type} discriminator, enabling
 * dynamic dispatch and flexible message routing.
 *
 * <h2>Serialization</h2>
 * Implementations of this interface are serialized with {@code @JsonTypeInfo} and {@code @JsonSubTypes} annotations,
 * allowing automatic deserialization on both the Java SDK and Runtime side.
 *
 * <h2>Metrics Logging</h2>
 * Implementations may override {@link #toMetric()} to emit a smaller, structured representation of the object to the
 * metrics log for observability and auditing. This is especially useful for requests or results that carry large or
 * sensitive payloads.
 *
 * <h2>Examples</h2>
 * <ul>
 *     <li>{@link io.fluxzero.common.api.tracking.Read}</li>
 *     <li>{@link io.fluxzero.common.api.search.SearchDocuments}</li>
 *     <li>{@link io.fluxzero.common.api.scheduling.Schedule}</li>
 *     <li>{@link io.fluxzero.common.api.keyvalue.GetValueResult}</li>
 * </ul>
 *
 * @see io.fluxzero.common.api.Request
 * @see io.fluxzero.common.api.RequestResult
 * @see io.fluxzero.common.api.Command
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({
        //common
        @JsonSubTypes.Type(value = VoidResult.class, name = "void"),
        @JsonSubTypes.Type(value = ErrorResult.class, name = "error"),
        @JsonSubTypes.Type(value = BooleanResult.class, name = "boolean"),
        @JsonSubTypes.Type(value = StringResult.class, name = "string"),
        @JsonSubTypes.Type(value = ConnectEvent.class, name = "connectEvent"),
        @JsonSubTypes.Type(value = DisconnectEvent.class, name = "disconnectEvent"),
        @JsonSubTypes.Type(value = RequestBatch.class, name = "requestBatch"),
        @JsonSubTypes.Type(value = ResultBatch.class, name = "resultBatch"),

        //publishing
        @JsonSubTypes.Type(value = Append.class, name = "append"),
        @JsonSubTypes.Type(value = SetRetentionTime.class, name = "setRetentionTime"),

        //tracking
        @JsonSubTypes.Type(value = Read.class, name = "read"),
        @JsonSubTypes.Type(value = ReadResult.class, name = "readResult"),
        @JsonSubTypes.Type(value = StorePosition.class, name = "storePosition"),
        @JsonSubTypes.Type(value = ResetPosition.class, name = "resetPosition"),
        @JsonSubTypes.Type(value = DisconnectTracker.class, name = "disconnectTracker"),
        @JsonSubTypes.Type(value = ReadFromIndex.class, name = "readFromIndex"),
        @JsonSubTypes.Type(value = ReadFromIndexResult.class, name = "readFromIndexResult"),
        @JsonSubTypes.Type(value = GetPosition.class, name = "getPosition"),
        @JsonSubTypes.Type(value = GetPositionResult.class, name = "getPositionResult"),
        @JsonSubTypes.Type(value = ClaimSegment.class, name = "claimSegment"),
        @JsonSubTypes.Type(value = ClaimSegmentResult.class, name = "claimSegmentResult"),

        //event sourcing
        @JsonSubTypes.Type(value = AppendEvents.class, name = "appendEvents"),
        @JsonSubTypes.Type(value = GetEvents.class, name = "getEvents"),
        @JsonSubTypes.Type(value = GetEventsResult.class, name = "getEventsResult"),
        @JsonSubTypes.Type(value = DeleteEvents.class, name = "deleteEvents"),

        //modeling
        @JsonSubTypes.Type(value = UpdateRelationships.class, name = "updateRelationships"),
        @JsonSubTypes.Type(value = RepairRelationships.class, name = "repairRelationships"),
        @JsonSubTypes.Type(value = GetAggregateIds.class, name = "getAggregateIds"),
        @JsonSubTypes.Type(value = GetAggregateIdsResult.class, name = "getAggregateIdsResult"),
        @JsonSubTypes.Type(value = GetRelationships.class, name = "getRelationships"),
        @JsonSubTypes.Type(value = GetRelationshipsResult.class, name = "getRelationshipsResult"),

        //scheduling
        @JsonSubTypes.Type(value = Schedule.class, name = "schedule"),
        @JsonSubTypes.Type(value = CancelSchedule.class, name = "cancelSchedule"),
        @JsonSubTypes.Type(value = GetSchedule.class, name = "getSchedule"),
        @JsonSubTypes.Type(value = GetScheduleResult.class, name = "getScheduleResult"),

        //key-value
        @JsonSubTypes.Type(value = StoreValues.class, name = "storeValues"),
        @JsonSubTypes.Type(value = GetValue.class, name = "getValue"),
        @JsonSubTypes.Type(value = GetValueResult.class, name = "getValueResult"),
        @JsonSubTypes.Type(value = DeleteValue.class, name = "deleteValue"),
        @JsonSubTypes.Type(value = StoreValueIfAbsent.class, name = "storeValueIfAbsent"),

        //search
        @JsonSubTypes.Type(value = IndexDocuments.class, name = "indexDocuments"),
        @JsonSubTypes.Type(value = SearchDocuments.class, name = "searchDocuments"),
        @JsonSubTypes.Type(value = GetSearchHistogram.class, name = "getSearchHistogram"),
        @JsonSubTypes.Type(value = GetSearchHistogramResult.class, name = "getSearchHistogramResult"),
        @JsonSubTypes.Type(value = GetDocument.class, name = "getDocument"),
        @JsonSubTypes.Type(value = GetDocuments.class, name = "getDocuments"),
        @JsonSubTypes.Type(value = HasDocument.class, name = "hasDocument"),
        @JsonSubTypes.Type(value = GetDocumentResult.class, name = "getDocumentResult"),
        @JsonSubTypes.Type(value = GetDocumentsResult.class, name = "getDocumentsResult"),
        @JsonSubTypes.Type(value = DeleteCollection.class, name = "deleteCollection"),
        @JsonSubTypes.Type(value = DeleteDocuments.class, name = "deleteDocuments"),
        @JsonSubTypes.Type(value = MoveDocuments.class, name = "moveDocuments"),
        @JsonSubTypes.Type(value = DeleteDocumentById.class, name = "deleteDocumentById"),
        @JsonSubTypes.Type(value = MoveDocumentById.class, name = "moveDocumentById"),
        @JsonSubTypes.Type(value = BulkUpdateDocuments.class, name = "bulkUpdateDocuments"),
        @JsonSubTypes.Type(value = GetDocumentStats.class, name = "getDocumentStats"),
        @JsonSubTypes.Type(value = SearchDocumentsResult.class, name = "searchDocumentsResult"),
        @JsonSubTypes.Type(value = GetDocumentStatsResult.class, name = "getDocumentStatsResult"),
        @JsonSubTypes.Type(value = CreateAuditTrail.class, name = "createAuditTrail"),
        @JsonSubTypes.Type(value = GetFacetStats.class, name = "getFacetStats"),
        @JsonSubTypes.Type(value = GetFacetStatsResult.class, name = "getFacetStatsResult"),
})
public interface JsonType {
    /**
     * Converts this object into a compact metric representation for logging or monitoring.
     * <p>
     * Used by the Fluxzero Java SDK to avoid logging large payloads directly while still tracking platform usage.
     *
     * @return a safe and compact object suitable for serialization to the metrics log
     */
    @JsonIgnore
    default Object toMetric() {
        return this;
    }
}
