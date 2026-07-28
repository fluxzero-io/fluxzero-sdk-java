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

package io.fluxzero.common.websocket;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.BooleanResult;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.ErrorResult;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.api.VoidResult;
import io.fluxzero.common.api.modeling.AwaitModelGraphProjection;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.CompleteModelActionMaterialization;
import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetModelAncestors;
import io.fluxzero.common.api.modeling.GetModelActionMaterialization;
import io.fluxzero.common.api.modeling.GetModelActionMaterializationResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.MaterializeModelAction;
import io.fluxzero.common.api.modeling.ModelActionConflict;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionSubstepResult;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelActionTargetResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.RegisterModelGraphProjection;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.ModelDocumentMaterialization;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelSnapshotMaterialization;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.modeling.PlanModelDeletion;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.api.publishing.Append;
import io.fluxzero.common.api.search.GetSearchCollections;
import io.fluxzero.common.api.search.GetSearchCollectionsResult;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SearchCollection;
import io.fluxzero.common.api.search.SearchCollectionType;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchModelDocuments;
import io.fluxzero.common.api.search.SearchModelGraphDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.constraints.MatchConstraint;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.ReadFromIndex;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static io.fluxzero.common.api.search.SearchCollectionType.auditTrail;
import static io.fluxzero.common.api.search.SearchCollectionType.regular;
import static io.fluxzero.common.api.search.SearchCollectionType.unknown;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.CBOR;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.JSON;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketTransportCodecsTest {
    private static final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .findAndAddModules()
            .disable(WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final WebSocketTransportCodec jsonCodec = WebSocketTransportCodecs.json(objectMapper);
    private final WebSocketTransportCodec cborCodec = WebSocketTransportCodecs.cbor(objectMapper);

    @Test
    void forFormatDefaultsToJson() {
        assertEquals(JSON, WebSocketTransportCodecs.forFormat(null, objectMapper).format());
    }

    @Test
    void forFormatReturnsCborCodec() {
        assertEquals(CBOR, WebSocketTransportCodecs.forFormat(CBOR, objectMapper).format());
    }

    @Test
    void cborRoundTripsAppendWithSerializedMessageBytes() throws Exception {
        Append append = new Append(MessageType.EVENT, List.of(serializedMessage()), Guarantee.STORED);

        Append decoded = assertInstanceOf(Append.class, roundTrip(cborCodec, append));

        assertEquals(append.getRequestId(), decoded.getRequestId());
        assertEquals(MessageType.EVENT, decoded.getMessageType());
        assertEquals(Guarantee.STORED, decoded.getGuarantee());
        assertSerializedMessage(serializedMessage(), decoded.getMessages().getFirst());
    }

    @Test
    void cborRoundTripsReadResultAndMessageBatch() throws Exception {
        MessageBatch batch = new MessageBatch(new int[]{0, 7}, List.of(serializedMessage()), 99L, null, true);
        ReadResult result = new ReadResult(123L, batch);
        result.setRequestReceivedTimestamp(456L);

        ReadResult decoded = assertInstanceOf(ReadResult.class, roundTrip(cborCodec, result));

        assertEquals(result.getRequestId(), decoded.getRequestId());
        assertEquals(result.getTimestamp(), decoded.getTimestamp());
        assertEquals(result.getRequestReceivedTimestamp(), decoded.getRequestReceivedTimestamp());
        assertArrayEquals(batch.getSegment(), decoded.getMessageBatch().getSegment());
        assertEquals(batch.getLastIndex(), decoded.getMessageBatch().getLastIndex());
        assertEquals(batch.isCaughtUp(), decoded.getMessageBatch().isCaughtUp());
        assertSerializedMessage(serializedMessage(), decoded.getMessageBatch().getMessages().getFirst());
    }

    @Test
    void cborRoundTripsSearchCollectionsRequestAndResult() throws Exception {
        GetSearchCollections request = new GetSearchCollections();
        GetSearchCollections decodedRequest = assertInstanceOf(
                GetSearchCollections.class, roundTrip(cborCodec, request));
        assertEquals(request.getRequestId(), decodedRequest.getRequestId());

        GetSearchCollectionsResult result = new GetSearchCollectionsResult(
                request.getRequestId(), List.of(new SearchCollection("alpha", regular),
                                               new SearchCollection("audit", auditTrail)));
        GetSearchCollectionsResult decodedResult = assertInstanceOf(
                GetSearchCollectionsResult.class, roundTrip(cborCodec, result));

        assertEquals(result.getSearchCollections(), decodedResult.getSearchCollections());
        assertEquals(1, decodedResult.toMetric().getCollectionCount());
        assertEquals(1, decodedResult.toMetric().getAuditTrailCount());
        assertEquals(0, decodedResult.toMetric().getUnknownCount());
        assertEquals(decodedResult.getTimestamp(), decodedResult.toMetric().getTimestamp());
    }

    @Test
    void mapsFutureSearchCollectionTypesToUnknown() throws Exception {
        GetSearchCollectionsResult result = new GetSearchCollectionsResult(
                42L, List.of(new SearchCollection("future", regular)));

        for (WebSocketTransportCodec codec : List.of(jsonCodec, cborCodec)) {
            GetSearchCollectionsResult decoded = assertInstanceOf(
                    GetSearchCollectionsResult.class,
                    codec.decode(replaceBytes(codec.encode(result), "regular", "futureX")));

            assertEquals(unknown, decoded.getSearchCollections().getFirst().getType());
            assertEquals(0, decoded.toMetric().getCollectionCount());
            assertEquals(0, decoded.toMetric().getAuditTrailCount());
            assertEquals(1, decoded.toMetric().getUnknownCount());
        }
    }

    @Test
    void standardMapperRemainsStrictForEnumsWithoutExplicitDefault() throws Exception {
        assertEquals(unknown, JsonUtils.writer.readValue("\"futureX\"", SearchCollectionType.class));
        assertThrows(InvalidFormatException.class,
                     () -> JsonUtils.writer.readValue("\"futureX\"", StrictType.class));
    }

    @Test
    void cborRoundTripsRequestAndResultBatches() throws Exception {
        Append append = new Append(MessageType.EVENT, List.of(serializedMessage()), Guarantee.STORED);
        Read read = new Read(MessageType.EVENT, "consumer", "tracker", 32, 4096L, 100L, null,
                             false, false, false, false, null, null);
        RequestBatch<JsonType> requestBatch = new RequestBatch<>(List.of(append, read));

        RequestBatch<?> decodedRequests = assertInstanceOf(RequestBatch.class, roundTrip(cborCodec, requestBatch));

        assertEquals(append.getRequestId(),
                     assertInstanceOf(Append.class, decodedRequests.getRequests().getFirst()).getRequestId());
        assertEquals(read.getRequestId(),
                     assertInstanceOf(Read.class, decodedRequests.getRequests().get(1)).getRequestId());
        assertEquals(read.getMaxBytes(),
                     assertInstanceOf(Read.class, decodedRequests.getRequests().get(1)).getMaxBytes());

        VoidResult voidResult = new VoidResult(append.getRequestId());
        voidResult.setRequestReceivedTimestamp(111L);
        BooleanResult booleanResult = new BooleanResult(read.getRequestId(), true);
        ErrorResult errorResult = new ErrorResult(77L, "boom");
        StringResult stringResult = new StringResult(78L, "ok");
        ResultBatch resultBatch = new ResultBatch(List.of(voidResult, booleanResult, errorResult, stringResult));

        ResultBatch decodedResults = assertInstanceOf(ResultBatch.class, roundTrip(cborCodec, resultBatch));

        assertEquals(voidResult.getRequestId(), decodedResults.getResults().getFirst().getRequestId());
        assertEquals(111L, decodedResults.getResults().getFirst().getRequestReceivedTimestamp());
        assertEquals(true, assertInstanceOf(BooleanResult.class, decodedResults.getResults().get(1)).isSuccess());
        assertEquals("boom", assertInstanceOf(ErrorResult.class, decodedResults.getResults().get(2)).getMessage());
        assertEquals("ok", assertInstanceOf(StringResult.class, decodedResults.getResults().get(3)).getResult());
    }

    @Test
    void decodesReadRequestsWithoutMaxBytes() throws Exception {
        String readJson = """
                {"@type":"read","messageType":"EVENT","consumer":"consumer","trackerId":"tracker","maxSize":32,\
                "maxTimeout":100,"typeFilter":null,"filterMessageTarget":false,"ignoreSegment":false,\
                "singleTracker":false,"clientControlledIndex":false,"lastIndex":null,"purgeTimeout":null}""";
        String readFromIndexJson = """
                {"@type":"readFromIndex","minIndex":42,"maxSize":32}""";

        Read read = assertInstanceOf(Read.class, objectMapper.readValue(readJson, JsonType.class));
        ReadFromIndex readFromIndex = assertInstanceOf(
                ReadFromIndex.class, objectMapper.readValue(readFromIndexJson, JsonType.class));

        assertEquals(0L, read.getMaxBytes());
        assertEquals(0L, readFromIndex.getMaxBytes());
    }

    @Test
    void cborWritesSerializedMessageBytesAsNativeBinary() throws Exception {
        SerializedMessage message = serializedMessage();
        byte[] encoded = cborCodec.encode(new Append(MessageType.EVENT, List.of(message), Guarantee.STORED));

        assertTrue(containsBinaryValue(encoded, message.getData().getValue()));
    }

    @Test
    void jsonAndCborRoundTripModelActionRequestAndResult() throws Exception {
        ModelActionTarget storedTarget = ModelActionTarget.builder()
                .modelId("order-1")
                .modelType("com.example.Order")
                .storeEvent(true)
                .updateState(true)
                .document(new ModelDocumentMutation(
                        "orders",
                        new SerializedDocument(
                                "order-1", 123L, null,
                                "orders",
                                new Data<>(
                                        new byte[]{4, 5, 6},
                                        "com.example.Order",
                                        2, "application/json"),
                                "Order one", Set.of(),
                                Set.of())))
                .snapshot(new ModelSnapshotMutation(
                        new Data<>(
                                new byte[]{7, 8},
                                "com.example.Order",
                                2, "application/json"),
                        123L, 100, 2))
                .updateRelationships(true)
                .relationships(List.of(ModelRelationship.builder()
                                               .parentId("customer-1")
                                               .parentType("com.example.Customer")
                                               .path("orders")
                                               .build()))
                .build();
        ModelActionTarget nonStoredDelete = ModelActionTarget.builder()
                .modelId("reservation-1")
                .updateState(true)
                .delete(true)
                .updateRelationships(true)
                .relationships(List.of())
                .build();
        CommitModelAction request = new CommitModelAction(
                "action-1", 91L, List.of("order-1", "inventory-1"),
                List.of(
                        ModelActionSubstep.builder()
                                .event(serializedMessage())
                                .publishEvent(true)
                                .targets(List.of(storedTarget))
                                .build(),
                        ModelActionSubstep.builder()
                                .targets(List.of(nonStoredDelete))
                                .build()),
                ModelConflictPolicy.RETRY, Guarantee.STORED);
        CommitModelActionResult result = CommitModelActionResult.accepted(
                request.getRequestId(), request.getActionId(),
                List.of(
                        new ModelActionSubstepResult(
                                101L, 501L,
                                List.of(new ModelActionTargetResult(
                                        "order-1", 7L, true))),
                        new ModelActionSubstepResult(
                                102L, null,
                                List.of(new ModelActionTargetResult(
                                        "reservation-1", 2L, false)))))
                .withDocumentsApplied()
                .withSnapshotsApplied();
        result.setRequestReceivedTimestamp(123L);

        for (WebSocketTransportCodec codec : List.of(jsonCodec, cborCodec)) {
            CommitModelAction decodedRequest = assertInstanceOf(
                    CommitModelAction.class, roundTrip(codec, request));
            assertEquals(request.getRequestId(), decodedRequest.getRequestId());
            assertEquals("action-1", decodedRequest.getActionId());
            assertEquals(91L, decodedRequest.getReadStateIndex());
            assertEquals(
                    ModelConflictPolicy.RETRY,
                    decodedRequest.getConflictPolicy());
            assertEquals(
                    List.of("order-1", "inventory-1"),
                    decodedRequest.getReadModelIds());
            assertEquals(2, decodedRequest.getSubsteps().size());
            assertSerializedMessage(
                    serializedMessage(),
                    decodedRequest.getSubsteps().getFirst().getEvent());
            assertTrue(decodedRequest.getSubsteps().getFirst().isPublishEvent());
            assertNull(decodedRequest.getSubsteps().get(1).getEvent());
            assertTrue(decodedRequest.getSubsteps().get(1)
                               .getTargets().getFirst().isDelete());
            assertTrue(decodedRequest.getSubsteps().getFirst()
                               .getTargets().getFirst()
                               .isUpdateRelationships());
            assertEquals(
                    "orders",
                    decodedRequest.getSubsteps().getFirst().getTargets().getFirst()
                            .getRelationships().getFirst().getPath());
            assertEquals(
                    "Order one",
                    decodedRequest.getSubsteps().getFirst()
                            .getTargets().getFirst()
                            .getDocument().getDocument()
                            .getSummary());
            assertEquals(2, decodedRequest.toMetric().getSubstepCount());
            assertEquals(2, decodedRequest.toMetric().getTargetCount());
            assertEquals(1, decodedRequest.toMetric().getStoredTargetCount());
            assertEquals(1, decodedRequest.toMetric().getDirectDocumentCount());
            assertEquals(3L, decodedRequest.toMetric().getDirectDocumentBytes());
            assertEquals(1, decodedRequest.toMetric().getSnapshotCount());
            assertEquals(2L, decodedRequest.toMetric().getSnapshotBytes());
            assertEquals(1, decodedRequest.toMetric().getRelationCount());
            assertEquals(serializedMessage().getBytes(), decodedRequest.toMetric().getEventBytes());

            CommitModelActionResult decodedResult = assertInstanceOf(
                    CommitModelActionResult.class, roundTrip(codec, result));
            assertEquals(result.getRequestId(), decodedResult.getRequestId());
            assertEquals("action-1", decodedResult.getActionId());
            assertEquals(123L, decodedResult.getRequestReceivedTimestamp());
            assertEquals(101L, decodedResult.getSubsteps().getFirst().getStateIndex());
            assertEquals(501L, decodedResult.getSubsteps().getFirst().getEventIndex());
            assertNull(decodedResult.getSubsteps().get(1).getEventIndex());
            assertEquals(
                    7L,
                    decodedResult.getSubsteps().getFirst().getTargets().getFirst()
                            .getSequenceNumber());
            assertTrue(decodedResult.getSubsteps().getFirst().getTargets().getFirst()
                               .isHistoryComplete());
            assertTrue(decodedResult.isAccepted());
            assertTrue(decodedResult.getConflicts().isEmpty());
            assertFalse(decodedResult.isRetryAllowed());
            assertTrue(decodedResult.isDocumentsApplied());
            assertTrue(decodedResult.isSnapshotsApplied());
        }
    }

    @Test
    void modelUpdateTrackingRoundTripsActionsAndPrivacySafeDeletions()
            throws Exception {
        TrackModelUpdates request =
                new TrackModelUpdates(
                        100L, 512, 30_000L);
        TrackModelUpdatesResult result =
                new TrackModelUpdatesResult(
                        request.getRequestId(),
                        102L, 105L, 104L,
                        List.of(
                                new ModelUpdate(
                                        ModelUpdateKind.ACTION,
                                        "action-1", 0,
                                        101L, null,
                                        List.of(
                                                new ModelActionTargetResult(
                                                        "order-1",
                                                        4L,
                                                        true))),
                                new ModelUpdate(
                                        ModelUpdateKind.HARD_DELETE,
                                        "deletion-1", 0,
                                        102L, null,
                                        List.of())));

        for (WebSocketTransportCodec codec :
                List.of(jsonCodec, cborCodec)) {
            TrackModelUpdates decodedRequest =
                    assertInstanceOf(
                            TrackModelUpdates.class,
                            roundTrip(codec, request));
            TrackModelUpdatesResult decodedResult =
                    assertInstanceOf(
                            TrackModelUpdatesResult.class,
                            roundTrip(codec, result));

            assertEquals(
                    100L,
                    decodedRequest.getLastStateIndex());
            assertEquals(
                    30_000L,
                    decodedRequest.getMaxWaitMillis());
            assertEquals(
                    8L * 1_024L * 1_024L,
                    decodedRequest.getMaxBytes());
            assertEquals(
                    ModelUpdateKind.ACTION,
                    decodedResult.getUpdates()
                            .getFirst().getKind());
            assertEquals(
                    ModelUpdateKind.HARD_DELETE,
                    decodedResult.getUpdates()
                            .getLast().getKind());
            assertTrue(
                    decodedResult.getUpdates()
                            .getLast().getTargets()
                            .isEmpty());
            assertEquals(
                    102L,
                    decodedResult.getLastStateIndex());
            assertEquals(
                    104L,
                    decodedResult
                            .getMaterializedStateIndex());

            CompleteModelActionMaterialization completion =
                    assertInstanceOf(
                            CompleteModelActionMaterialization.class,
                            roundTrip(
                                    codec,
                                    new CompleteModelActionMaterialization(
                                            "action-1",
                                            102L)));
            assertEquals(
                    "action-1",
                    completion.getActionId());
            assertEquals(
                    102L,
                    completion.getLastStateIndex());
            assertEquals(
                    Guarantee.STORED,
                    completion.getGuarantee());
        }
    }

    @Test
    void modelMaterializationRepairRoundTripsExactMutations()
            throws Exception {
        ModelDocumentMaterialization document =
                new ModelDocumentMaterialization(
                        "order-1", 101L,
                        new ModelDocumentMutation(
                                "orders",
                                new SerializedDocument(
                                        "order-1", 123L,
                                        null, "orders",
                                        new Data<>(
                                                new byte[]{
                                                        4, 5, 6},
                                                "com.example.Order",
                                                2,
                                                "application/json"),
                                        "Order one",
                                        Set.of(),
                                        Set.of())));
        ModelSnapshotMaterialization snapshot =
                new ModelSnapshotMaterialization(
                        "order-1", 7L, 101L,
                        new ModelSnapshotMutation(
                                new Data<>(
                                        new byte[]{7, 8},
                                        "com.example.Order",
                                        2,
                                        "application/json"),
                                123L, 100, 2));
        GetModelActionMaterialization request =
                new GetModelActionMaterialization(
                        "action-1");
        GetModelActionMaterializationResult response =
                new GetModelActionMaterializationResult(
                        request.getRequestId(),
                        "action-1", 101L, false,
                        List.of(document),
                        List.of(snapshot));
        MaterializeModelAction materialize =
                new MaterializeModelAction(
                        "action-1", 101L,
                        List.of(document),
                        List.of(snapshot));

        for (WebSocketTransportCodec codec :
                List.of(jsonCodec, cborCodec)) {
            GetModelActionMaterialization decodedRequest =
                    assertInstanceOf(
                            GetModelActionMaterialization.class,
                            roundTrip(codec, request));
            GetModelActionMaterializationResult decodedResponse =
                    assertInstanceOf(
                            GetModelActionMaterializationResult.class,
                            roundTrip(codec, response));
            MaterializeModelAction decodedMaterialize =
                    assertInstanceOf(
                            MaterializeModelAction.class,
                            roundTrip(codec, materialize));

            assertEquals(
                    "action-1",
                    decodedRequest.getActionId());
            assertEquals(
                    101L,
                    decodedResponse
                            .getLastStateIndex());
            assertFalse(
                    decodedResponse.isComplete());
            assertEquals(
                    document,
                    decodedResponse.getDocuments()
                            .getFirst());
            assertEquals(
                    snapshot,
                    decodedResponse.getSnapshots()
                            .getFirst());
            assertEquals(
                    document,
                    decodedMaterialize
                            .getDocuments()
                            .getFirst());
            assertEquals(
                    snapshot,
                    decodedMaterialize
                            .getSnapshots()
                            .getFirst());
            assertEquals(
                    Guarantee.STORED,
                    decodedMaterialize
                            .getGuarantee());
        }
    }

    @Test
    void jsonAndCborRoundTripModelActionConflict() throws Exception {
        CommitModelActionResult result = CommitModelActionResult.conflict(
                42L, "action-1",
                List.of(
                        new ModelActionConflict("order-1", 101L, 90L),
                        new ModelActionConflict("inventory-1", 102L, 103L)),
                true);

        for (WebSocketTransportCodec codec : List.of(jsonCodec, cborCodec)) {
            CommitModelActionResult decoded = assertInstanceOf(
                    CommitModelActionResult.class, roundTrip(codec, result));

            assertFalse(decoded.isAccepted());
            assertTrue(decoded.isRetryAllowed());
            assertTrue(decoded.getSubsteps().isEmpty());
            assertEquals(result.getConflicts(), decoded.getConflicts());
            assertEquals(2, decoded.toMetric().getConflictCount());
        }
    }

    @Test
    void modelActionProtocolIgnoresFutureFields() throws Exception {
        CommitModelAction request = new CommitModelAction(
                "action-1", -1L, List.of(), List.of(), ModelConflictPolicy.ACCEPT, Guarantee.STORED);
        var json = (com.fasterxml.jackson.databind.node.ObjectNode)
                objectMapper.readTree(objectMapper.writeValueAsBytes(request));
        json.remove("conflictPolicy");
        json.put("futurePolicy", "future");

        CommitModelAction decoded = assertInstanceOf(
                CommitModelAction.class,
                objectMapper.readValue(objectMapper.writeValueAsBytes(json), JsonType.class));

        assertEquals(request.getRequestId(), decoded.getRequestId());
        assertEquals("action-1", decoded.getActionId());
        assertEquals(-1L, decoded.getReadStateIndex());
        assertNull(decoded.getConflictPolicy());
        assertEquals(ModelConflictPolicy.ACCEPT, decoded.toMetric().getConflictPolicy());

        CommitModelActionResult result = CommitModelActionResult.accepted(
                request.getRequestId(), request.getActionId(), List.of());
        var resultJson = (com.fasterxml.jackson.databind.node.ObjectNode)
                objectMapper.readTree(objectMapper.writeValueAsBytes(result));
        resultJson.remove("conflicts");
        resultJson.remove("retryAllowed");
        CommitModelActionResult decodedResult = assertInstanceOf(
                CommitModelActionResult.class,
                objectMapper.readValue(
                        objectMapper.writeValueAsBytes(resultJson), JsonType.class));
        assertTrue(decodedResult.isAccepted());
        assertTrue(decodedResult.getConflicts().isEmpty());
    }

    @Test
    void cborWritesModelActionEventBytesAsNativeBinary() throws Exception {
        CommitModelAction request = new CommitModelAction(
                "action-1", 1L, List.of("order-1"),
                List.of(ModelActionSubstep.builder()
                                .event(serializedMessage())
                                .publishEvent(true)
                                .targets(List.of(ModelActionTarget.builder()
                                                         .modelId("order-1")
                                                         .storeEvent(true)
                                                         .updateState(true)
                                                         .relationships(List.of())
                                                         .build()))
                                .build()),
                ModelConflictPolicy.ACCEPT, Guarantee.STORED);

        assertTrue(containsBinaryValue(
                cborCodec.encode(request), serializedMessage().getData().getValue()));
        var json = objectMapper.readTree(
                jsonCodec.encode(request));
        assertFalse(json.path("substeps").get(0)
                            .path("targets").get(0)
                            .has("updateRelationships"));
    }

    @Test
    void modelEventBatchRoundTripsSharedPayloadsAndMemberships() throws Exception {
        GetModelEvents request = new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("order-1", -1L, 100),
                        new ModelEventStreamRequest("inventory-1", 4L, 0)),
                null, "action-991", 3, 4_096L);
        GetModelEventsResult result = new GetModelEventsResult(
                request.getRequestId(), 91L,
                List.of(new ModelEventPayload(80L, serializedMessage())),
                List.of(
                        new ModelEventStream(
                                "order-1",
                                new ModelHeadState("order-1", "example.Order", 7L, 80L, true, false),
                                List.of(new ModelEventMembership(
                                        7L, 80L, 70L, "action-1", 2))),
                        new ModelEventStream(
                                "inventory-1",
                                new ModelHeadState("inventory-1", "example.Inventory", 4L, 79L, false, true),
                                List.of())));

        for (WebSocketTransportCodec codec : List.of(jsonCodec, cborCodec)) {
            GetModelEvents decodedRequest = assertInstanceOf(
                    GetModelEvents.class, roundTrip(codec, request));
            assertNull(decodedRequest.getMaxStateIndex());
            assertEquals(
                    "action-991",
                    decodedRequest.getBoundaryActionId());
            assertEquals(
                    3, decodedRequest.getBoundarySubstep());
            assertEquals(4_096L, decodedRequest.getMaxBytes());
            assertEquals(2, decodedRequest.getRequests().size());
            assertEquals(0, decodedRequest.getRequests().get(1).getMaxSize());

            GetModelEventsResult decodedResult = assertInstanceOf(
                    GetModelEventsResult.class, roundTrip(codec, result));
            assertEquals(91L, decodedResult.getStateIndex());
            assertEquals(1, decodedResult.getPayloads().size());
            assertSerializedMessage(
                    serializedMessage(), decodedResult.getPayloads().getFirst().getEvent());
            assertEquals(2, decodedResult.getStreams().size());
            assertEquals(
                    "action-1",
                    decodedResult.getStreams().getFirst().getMemberships().getFirst().getActionId());
            assertTrue(decodedResult.getStreams().get(1).getHead().isDeleted());
            assertEquals(1, decodedResult.toMetric().getPayloadCount());
            assertEquals(1, decodedResult.toMetric().getMembershipCount());
            assertEquals(serializedMessage().getBytes(), decodedResult.toMetric().getBytes());
        }
    }

    @Test
    void modelGraphRoundTripsTemporalEdgesAndGroupedStreams() throws Exception {
        GetModelGraph request = new GetModelGraph(
                "order-1", null, "action-991", 3,
                12, 1_000,
                128, 8_388_608L, true);
        GetModelGraphResult result = new GetModelGraphResult(
                request.getRequestId(), 91L,
                List.of(new ModelGraphEdge(
                        "line-1", "order-1", "example.Order",
                        "lines", 80L, null)),
                List.of(new ModelEventPayload(80L, serializedMessage())),
                List.of(new ModelEventStream(
                        "order-1",
                        new ModelHeadState(
                                "order-1", "example.Order",
                                7L, 80L, true, false),
                        List.of(new ModelEventMembership(
                                7L, 80L, 70L, "action-1", 2)))));

        for (WebSocketTransportCodec codec : List.of(jsonCodec, cborCodec)) {
            GetModelGraph decodedRequest = assertInstanceOf(
                    GetModelGraph.class, roundTrip(codec, request));
            assertEquals("order-1", decodedRequest.getRootId());
            assertNull(decodedRequest.getMaxStateIndex());
            assertEquals(
                    "action-991",
                    decodedRequest.getBoundaryActionId());
            assertEquals(
                    3, decodedRequest.getBoundarySubstep());
            assertTrue(decodedRequest.isComposableOnly());

            GetModelGraphResult decodedResult = assertInstanceOf(
                    GetModelGraphResult.class, roundTrip(codec, result));
            assertEquals(91L, decodedResult.getStateIndex());
            assertEquals("lines", decodedResult.getEdges().getFirst().getPath());
            assertEquals(
                    "example.Order",
                    decodedResult.getStreams().getFirst().getHead().getModelType());
            assertSerializedMessage(
                    result.getPayloads().getFirst().getEvent(),
                    decodedResult.getPayloads().getFirst().getEvent());
        }
    }

    @Test
    void modelAncestorsRoundTripMultipleRootsAndActionBoundary()
            throws Exception {
        GetModelAncestors request = new GetModelAncestors(
                List.of("line-1", "line-2"),
                null, "action-991", 3,
                12, 1_000, 0, 0L);

        for (WebSocketTransportCodec codec :
                List.of(jsonCodec, cborCodec)) {
            GetModelAncestors decoded = assertInstanceOf(
                    GetModelAncestors.class,
                    roundTrip(codec, request));

            assertEquals(
                    List.of("line-1", "line-2"),
                    decoded.getModelIds());
            assertEquals(
                    "action-991",
                    decoded.getBoundaryActionId());
            assertEquals(3, decoded.getBoundarySubstep());
            assertEquals(12, decoded.getMaxDepth());
            assertEquals(1_000, decoded.getMaxModels());
            assertEquals(
                    2, decoded.toMetric().getRootCount());
        }
    }

    @Test
    void modelGraphSearchRoundTripsBoundedRelationAndIdFilter()
            throws Exception {
        SearchModelDocuments request =
                new SearchModelDocuments(
                        SearchDocuments.builder()
                                .query(SearchQuery.builder()
                                               .collection("lines")
                                               .build())
                                .documentIds(List.of(
                                        "line-1", "line-2"))
                                .maxSize(50)
                                .build(),
                        List.of(ModelRelationConstraint.builder()
                                        .direction(
                                                ModelRelationConstraint
                                                        .Direction.ANCESTOR)
                                        .query(SearchQuery.builder()
                                                       .collection("orders")
                                                       .constraint(
                                                               MatchConstraint.match(
                                                                       "open",
                                                                       "status"))
                                                       .build())
                                        .minDepth(1)
                                        .maxDepth(3)
                                        .path("lines")
                                        .path("orders")
                                        .maxRelatedModels(500)
                                        .maxTraversedModels(5_000)
                                        .build()));

        for (WebSocketTransportCodec codec :
                List.of(jsonCodec, cborCodec)) {
            SearchModelDocuments decoded =
                    assertInstanceOf(
                            SearchModelDocuments.class,
                            roundTrip(codec, request));

            assertEquals(
                    List.of("line-1", "line-2"),
                    decoded.getSearch().getDocumentIds());
            ModelRelationConstraint relation =
                    decoded.getRelations().getFirst();
            assertEquals(1, relation.getMinDepth());
            assertEquals(3, relation.getMaxDepth());
            assertEquals(
                    List.of("lines", "orders"),
                    relation.getPaths());
            assertEquals(
                    ModelRelationConstraint.Direction.ANCESTOR,
                    relation.getDirection());
            assertEquals(
                    1,
                    decoded.toMetric().getRelationCount());
        }
    }

    @Test
    void modelGraphCompositionUsesDistinctBoundedWireAction()
            throws Exception {
        SearchModelGraphDocuments request =
                new SearchModelGraphDocuments(
                        SearchDocuments.builder()
                                .query(SearchQuery.builder()
                                               .collection("orders")
                                               .build())
                                .maxSize(20)
                                .build(),
                        List.of(),
                        ModelGraphComposition.builder()
                                .maxDepth(4)
                                .maxModels(2_000)
                                .maxPlacements(5_000)
                                .maxCollections(50)
                                .maxBytes(8_000_000L)
                                .build(),
                        List.of(
                                new ModelGraphPathOverride(
                                        "children",
                                        "projected/items")));

        for (WebSocketTransportCodec codec :
                List.of(jsonCodec, cborCodec)) {
            SearchModelGraphDocuments decoded =
                    assertInstanceOf(
                            SearchModelGraphDocuments.class,
                            roundTrip(codec, request));

            assertEquals(
                    "orders",
                    decoded.getSearch().getQuery()
                            .getCollections().getFirst());
            assertTrue(decoded.getRelations().isEmpty());
            assertEquals(
                    4,
                    decoded.getComposition()
                            .getMaxDepth());
            assertEquals(
                    5_000,
                    decoded.toMetric()
                            .getMaxPlacements());
            assertEquals(
                    50,
                    decoded.getComposition()
                            .getMaxCollections());
            assertEquals(
                    List.of(
                            new ModelGraphPathOverride(
                                    "children",
                                    "projected/items")),
                    decoded.getPathOverrides());
            assertEquals(
                    1,
                    decoded.toMetric()
                            .getPathOverrideCount());
        }
    }

    @Test
    void materializedModelGraphProjectionUsesDistinctWireActions()
            throws Exception {
        RegisterModelGraphProjection registration =
                new RegisterModelGraphProjection(
                        new ModelGraphProjectionConfiguration(
                                "example.Order",
                                "orders",
                                "orderGraphs",
                                ModelGraphComposition
                                        .builder()
                                        .maxDepth(4)
                                        .build(),
                                List.of(
                                        new ModelGraphPathOverride(
                                                "lines",
                                                "items"))),
                        true);
        GetModelGraphProjectionStatus statusRequest =
                new GetModelGraphProjectionStatus(
                        "orderGraphs");
        AwaitModelGraphProjection awaitRequest =
                new AwaitModelGraphProjection(
                        "orderGraphs", 10L,
                        8L,
                        List.of("line-1"));
        ModelGraphProjectionStatus status =
                new ModelGraphProjectionStatus(
                        statusRequest.getRequestId(),
                        "orderGraphs",
                        12L, 10L, 2L, 3L,
                        true);

        for (WebSocketTransportCodec codec :
                List.of(jsonCodec, cborCodec)) {
            RegisterModelGraphProjection decoded =
                    assertInstanceOf(
                            RegisterModelGraphProjection.class,
                            roundTrip(
                                    codec,
                                    registration));
            assertTrue(decoded.isRebuild());
            assertEquals(
                    "items",
                    decoded.getConfiguration()
                            .getPathOverrides()
                            .getFirst()
                            .getProjectionPath());
            assertEquals(
                    4,
                    decoded.getConfiguration()
                            .getComposition()
                            .getMaxDepth());

            GetModelGraphProjectionStatus
                    decodedRequest =
                    assertInstanceOf(
                            GetModelGraphProjectionStatus.class,
                            roundTrip(
                                    codec,
                                    statusRequest));
            assertEquals(
                    "orderGraphs",
                    decodedRequest.getCollection());

            AwaitModelGraphProjection decodedAwait =
                    assertInstanceOf(
                            AwaitModelGraphProjection.class,
                            roundTrip(
                                    codec,
                                    awaitRequest));
            assertEquals(
                    "orderGraphs",
                    decodedAwait.getCollection());
            assertEquals(
                    10L,
                    decodedAwait.getStateIndex());
            assertEquals(
                    8L,
                    decodedAwait.getFirstStateIndex());
            assertEquals(
                    List.of("line-1"),
                    decodedAwait.getModelIds());

            ModelGraphProjectionStatus decodedStatus =
                    assertInstanceOf(
                            ModelGraphProjectionStatus.class,
                            roundTrip(codec, status));
            assertEquals(
                    2L,
                    decodedStatus.getLag());
            assertEquals(
                    3L,
                    decodedStatus.getPendingRoots());
        }
    }

    @Test
    void modelDeletionPlanUsesDistinctWireActions()
            throws Exception {
        PlanModelDeletion request =
                new PlanModelDeletion(
                        "order-1",
                        ModelDeletionCascade.DESCENDANTS,
                        12, 5_000, 25);
        ModelDeletionPlan result =
                new ModelDeletionPlan(
                        request.getRequestId(),
                        "order-1",
                        ModelDeletionCascade.DESCENDANTS,
                        12,
                        5_000,
                        42L,
                        "aabbcc",
                        73,
                        2,
                        101L,
                        41L,
                        List.of(
                                "order-1",
                                "line-1"));

        for (WebSocketTransportCodec codec :
                List.of(jsonCodec, cborCodec)) {
            PlanModelDeletion decodedRequest =
                    assertInstanceOf(
                            PlanModelDeletion.class,
                            roundTrip(codec, request));
            assertEquals(
                    ModelDeletionCascade.DESCENDANTS,
                    decodedRequest.getCascade());
            assertEquals(
                    5_000,
                    decodedRequest.getMaxModels());

            ModelDeletionPlan decodedResult =
                    assertInstanceOf(
                            ModelDeletionPlan.class,
                            roundTrip(codec, result));
            assertEquals(
                    "aabbcc",
                    decodedResult.getFingerprint());
            assertEquals(
                    2,
                    decodedResult
                            .getExternallySharedModelCount());
            assertEquals(
                    41L,
                    decodedResult.getPublishedEventCount());

            DeleteModel deletion =
                    DeleteModel.builder()
                            .deletionId(
                                    "deletion-1")
                            .modelId("order-1")
                            .cascade(
                                    ModelDeletionCascade.DESCENDANTS)
                            .planFingerprint(
                                    result.getFingerprint())
                            .maxDepth(
                                    result.getMaxDepth())
                            .maxModels(
                                    result.getMaxModels())
                            .build();
            DeleteModel decodedDeletion =
                    assertInstanceOf(
                            DeleteModel.class,
                            roundTrip(codec, deletion));
            assertEquals(
                    "deletion-1",
                    decodedDeletion
                            .getDeletionId());
            ModelDeletionResult deletionResult =
                    new ModelDeletionResult(
                            deletion.getRequestId(),
                            "deletion-1",
                            ModelDeletionCascade.DESCENDANTS,
                            43L, 73, 101L, 41L,
                            false);
            assertEquals(
                    73,
                    assertInstanceOf(
                            ModelDeletionResult.class,
                            roundTrip(
                                    codec,
                                    deletionResult))
                            .getDeletedModelCount());
        }
    }

    @Test
    void materializedModelGraphProjectionRejectsUnsafeOrAmbiguousPaths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelGraphPathOverride(
                        "children/0", "items"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelGraphProjectionConfiguration(
                        "example.Order",
                        "orders",
                        "orderGraphs",
                        ModelGraphComposition
                                .builder()
                                .build(),
                        List.of(
                                new ModelGraphPathOverride(
                                        "lines", "items"),
                                new ModelGraphPathOverride(
                                        "discounts", "items"))));
    }

    private static JsonType roundTrip(WebSocketTransportCodec codec, JsonType value) throws Exception {
        return codec.decode(codec.encode(value));
    }

    private static byte[] replaceBytes(byte[] input, String before, String after) {
        byte[] expected = before.getBytes(UTF_8);
        byte[] replacement = after.getBytes(UTF_8);
        if (expected.length != replacement.length) {
            throw new IllegalArgumentException("Replacement must have the same byte length");
        }
        byte[] result = input.clone();
        for (int i = 0; i <= result.length - expected.length; i++) {
            int offset = i;
            if (java.util.stream.IntStream.range(0, expected.length)
                    .allMatch(j -> result[offset + j] == expected[j])) {
                System.arraycopy(replacement, 0, result, i, replacement.length);
                return result;
            }
        }
        throw new IllegalArgumentException("Value to replace was not found");
    }

    private static SerializedMessage serializedMessage() {
        return new SerializedMessage(
                new Data<>(new byte[]{1, 2, 3, 4, 5}, "com.example.BenchEvent", 7, "application/json"),
                Metadata.of("routingKey", "key-1").with("attempt", 2),
                3, 99L, "source", "target", 12, 1234L, "message-1", 6);
    }

    private static void assertSerializedMessage(SerializedMessage expected, SerializedMessage actual) {
        assertArrayEquals(expected.getData().getValue(), actual.getData().getValue());
        assertEquals(expected.getData().getType(), actual.getData().getType());
        assertEquals(expected.getData().getRevision(), actual.getData().getRevision());
        assertEquals(expected.getMetadata().getEntries(), actual.getMetadata().getEntries());
        assertEquals(expected.getSegment(), actual.getSegment());
        assertEquals(expected.getIndex(), actual.getIndex());
        assertEquals(expected.getSource(), actual.getSource());
        assertEquals(expected.getTarget(), actual.getTarget());
        assertEquals(expected.getRequestId(), actual.getRequestId());
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getMessageId(), actual.getMessageId());
        assertEquals(expected.getOriginalRevision(), actual.getOriginalRevision());
    }

    private static boolean containsBinaryValue(byte[] encoded, byte[] expected) throws Exception {
        try (JsonParser parser = new CBORFactory().createParser(encoded)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.VALUE_EMBEDDED_OBJECT
                    && Arrays.equals(expected, parser.getBinaryValue())) {
                    return true;
                }
            }
            return false;
        }
    }

    private enum StrictType {
        known
    }
}
