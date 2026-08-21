/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommitWireCodecTest {

    @Test
    void binaryCodecRetainsSelfContainedEventsAndRejectsPreviewVersions() throws Exception {
        CommitModels commit = commit("native-😀", false);

        byte[] nativeBytes = ModelCommitWireCodec.tryEncode(new RequestBatch<>(List.of(commit)));

        RequestBatch<?> decoded = assertInstanceOf(
                RequestBatch.class, ModelCommitWireCodec.tryDecode(nativeBytes));
        SerializedMessage decodedEvent = assertInstanceOf(CommitModels.class, decoded.getRequests().getFirst())
                .getSubsteps().getFirst().getEvent();
        SerializedMessage expectedEvent = commit.getSubsteps().getFirst().getEvent();
        assertEquals(expectedEvent, decodedEvent);
        assertEquals(expectedEvent.getOriginalRevision(), decodedEvent.getOriginalRevision());
        assertTrue(decodedEvent.getData().byteArrayView() != null);

        nativeBytes[Integer.BYTES]--;
        assertThrows(IOException.class, () -> ModelCommitWireCodec.tryDecode(nativeBytes));
    }

    @Test
    void roundTripsSupportedRequestBatchWithoutChangingRequestIdentityOrMessageData() throws Exception {
        CommitModels first = commit("één-😀", false);
        CommitModels second = commit("two", true);

        byte[] encoded =
                ModelCommitWireCodec.tryEncode(new RequestBatch<>(List.of(first, second)));
        RequestBatch<?> decoded =
                assertInstanceOf(RequestBatch.class, ModelCommitWireCodec.tryDecode(encoded));

        CommitModels decodedFirst =
                assertInstanceOf(CommitModels.class, decoded.getRequests().getFirst());
        CommitModels decodedSecond =
                assertInstanceOf(CommitModels.class, decoded.getRequests().get(1));
        assertEquals(first.getRequestId(), decodedFirst.getRequestId());
        assertEquals(first.getCommitId(), decodedFirst.getCommitId());
        assertEquals(first.getReadStateIndex(), decodedFirst.getReadStateIndex());
        assertEquals(first.getReadModelIds(), decodedFirst.getReadModelIds());
        assertFalse(decodedFirst.isPossibleDuplicate());
        assertEquals(
                first.getSubsteps().getFirst().getTargets().getFirst(),
                decodedFirst.getSubsteps().getFirst().getTargets().getFirst());
        SerializedMessage expected = first.getSubsteps().getFirst().getEvent();
        SerializedMessage actual = decodedFirst.getSubsteps().getFirst().getEvent();
        assertArrayEquals(expected.getData().getValue(), actual.getData().getValue());
        assertEquals(expected.getData().getType(), actual.getData().getType());
        assertEquals(expected.getData().getRevision(), actual.getData().getRevision());
        assertEquals(expected.getData().getFormat(), actual.getData().getFormat());
        assertEquals(expected.getMetadata(), actual.getMetadata());
        assertEquals(expected.getSegment(), actual.getSegment());
        assertEquals(expected.getIndex(), actual.getIndex());
        assertEquals(expected.getSource(), actual.getSource());
        assertEquals(expected.getTarget(), actual.getTarget());
        assertEquals(expected.getRequestId(), actual.getRequestId());
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getMessageId(), actual.getMessageId());
        assertTrue(decodedSecond.isPossibleDuplicate());
    }

    @Test
    void roundTripsRequestBatchWhenDescriptorFieldsAreNotShared() throws Exception {
        CommitModels first = commit("one", false);
        CommitModels second =
                commit(
                        "two",
                        "other/type",
                        "OtherModel",
                        "other-source",
                        "other-target",
                        List.of("separately-read"));

        byte[] encoded =
                ModelCommitWireCodec.tryEncode(new RequestBatch<>(List.of(first, second)));
        RequestBatch<?> decoded =
                assertInstanceOf(RequestBatch.class, ModelCommitWireCodec.tryDecode(encoded));

        CommitModels actual =
                assertInstanceOf(CommitModels.class, decoded.getRequests().get(1));
        assertEquals(second.getReadModelIds(), actual.getReadModelIds());
        assertEquals(
                second.getSubsteps().getFirst().getTargets().getFirst(),
                actual.getSubsteps().getFirst().getTargets().getFirst());
        SerializedMessage expectedMessage = second.getSubsteps().getFirst().getEvent();
        SerializedMessage actualMessage = actual.getSubsteps().getFirst().getEvent();
        assertEquals(expectedMessage.getData().getFormat(), actualMessage.getData().getFormat());
        assertEquals(expectedMessage.getSource(), actualMessage.getSource());
        assertEquals(expectedMessage.getTarget(), actualMessage.getTarget());
    }

    @Test
    void roundTripsBatchWhenEventMessageIdsEqualCommitIds() throws Exception {
        CommitModels first = withEventMessageIdMatchingCommitId(commit("one", false));
        CommitModels second = withEventMessageIdMatchingCommitId(commit("één-😀", false));

        RequestBatch<?> decoded = assertInstanceOf(
                RequestBatch.class,
                ModelCommitWireCodec.tryDecode(
                        ModelCommitWireCodec.tryEncode(
                                new RequestBatch<>(List.of(first, second)))));

        for (int index = 0; index < decoded.getRequests().size(); index++) {
            CommitModels actual = assertInstanceOf(
                    CommitModels.class, decoded.getRequests().get(index));
            CommitModels expected = index == 0 ? first : second;
            assertEquals(
                    expected.getSubsteps().getFirst().getEvent().getMessageId(),
                    actual.getSubsteps().getFirst().getEvent().getMessageId());
        }
    }

    @Test
    void roundTripsSupportedAcceptedResultBatchIncludingTransportTimings() throws Exception {
        CommitModelsResult first = compactResult(11L, "one", 101L, 501L);
        first.setRequestReceivedTimestamp(10L);
        first.setResponseQueuedTimestamp(20L);
        first.setResponseSendStartTimestamp(30L);
        CommitModelsResult second = result(12L, "two", 102L, null);

        byte[] encoded =
                ModelCommitWireCodec.tryEncode(new ResultBatch(List.of(first, second)));
        ResultBatch decoded =
                assertInstanceOf(ResultBatch.class, ModelCommitWireCodec.tryDecode(encoded));

        CommitModelsResult decodedFirst =
                assertInstanceOf(CommitModelsResult.class, decoded.getResults().getFirst());
        assertTrue(decodedFirst.hasSingleTargetResult());
        assertEquals(101L, decodedFirst.getSingleTargetStateIndex());
        assertEquals(501L, decodedFirst.getSingleTargetEventIndex());
        assertEquals(7L, decodedFirst.getSingleTargetSequenceNumber());
        assertTrue(decodedFirst.isSingleTargetHistoryComplete());
        assertEquals(first.getRequestId(), decodedFirst.getRequestId());
        assertNull(decodedFirst.getCommitId());
        assertNull(decodedFirst.getUpdates().getFirst().getTargets().getFirst().getModelId());
        assertEquals(
                first.getUpdates().getFirst().getStateIndex(),
                decodedFirst.getUpdates().getFirst().getStateIndex());
        assertEquals(
                first.getUpdates().getFirst().getEventIndex(),
                decodedFirst.getUpdates().getFirst().getEventIndex());
        assertEquals(
                first.getUpdates().getFirst().getTargets().getFirst().getSequenceNumber(),
                decodedFirst.getUpdates().getFirst().getTargets().getFirst().getSequenceNumber());
        assertEquals(10L, decodedFirst.getRequestReceivedTimestamp());
        assertEquals(20L, decodedFirst.getResponseQueuedTimestamp());
        assertEquals(30L, decodedFirst.getResponseSendStartTimestamp());
        decodedFirst.restoreTransportIdentities(
                first.getCommitId(),
                first.getUpdates().getFirst()
                        .getTargets().getFirst().getModelId());
        assertEquals(first.getCommitId(), decodedFirst.getCommitId());
        assertEquals(
                first.getCommitId(),
                decodedFirst.getUpdates().getFirst().getCommitId());
        assertEquals(
                first.getUpdates().getFirst().getTargets().getFirst().getModelId(),
                decodedFirst.getUpdates().getFirst().getTargets().getFirst().getModelId());
        assertThrows(
                IllegalStateException.class,
                () -> decodedFirst.restoreTransportIdentities(
                        "another-commit", "another-model"));
        CommitModelsResult decodedSecond = assertInstanceOf(
                CommitModelsResult.class, decoded.getResults().get(1));
        assertNull(decodedSecond.getCommitId());
        assertNull(decodedSecond.getUpdates().getFirst().getTargets().getFirst().getModelId());
        assertEquals(
                second.getUpdates().getFirst().getStateIndex(),
                decodedSecond.getUpdates().getFirst().getStateIndex());
    }

    @Test
    void supportsDuplicateHintAndFallsBackForRicherCommitShapes() throws Exception {
        CommitModels duplicate = commit("duplicate", false);
        duplicate.markPossibleDuplicate();
        byte[] duplicateBytes = ModelCommitWireCodec.tryEncode(
                new RequestBatch<>(List.of(duplicate)));
        RequestBatch<?> decodedDuplicate = assertInstanceOf(
                RequestBatch.class, ModelCommitWireCodec.tryDecode(duplicateBytes));
        assertTrue(assertInstanceOf(
                CommitModels.class,
                decodedDuplicate.getRequests().getFirst())
                           .isPossibleDuplicate());

        CommitModels withRelationships = commit("relationships", false);
        ModelCommitTarget target =
                withRelationships.getSubsteps().getFirst().getTargets().getFirst().toBuilder()
                        .updateRelationships(true)
                        .build();
        CommitModels rich =
                new CommitModels(
                        "rich",
                        1L,
                        List.of("rich"),
                        List.of(
                                ModelCommitStep.builder()
                                        .event(withRelationships.getSubsteps().getFirst().getEvent())
                                        .publishEvent(true)
                                        .targets(List.of(target))
                                        .build()),
                        ModelConflictPolicy.ACCEPT,
                        Guarantee.STORED,
                        false);
        assertNull(ModelCommitWireCodec.tryEncode(new RequestBatch<>(List.of(rich))));

        CommitModels base = commit("aliases", false);
        ModelCommitStep baseStep = base.getSubsteps().getFirst();
        CommitModels withAliases = new CommitModels(
                base.getCommitId(), base.getReadStateIndex(),
                base.getReadModelIds(),
                List.of(baseStep.toBuilder()
                        .targets(List.of(baseStep.getTargets().getFirst()
                                .toBuilder()
                                .aliases(List.of("alias"))
                                .build()))
                        .build()),
                base.getConflictPolicy(), base.getGuarantee(),
                base.isPossibleDuplicate());
        assertNull(ModelCommitWireCodec.tryEncode(
                new RequestBatch<>(List.of(withAliases))));

        CommitModels cascade = commit("cascade", false);
        ModelCommitStep cascadeStep = cascade.getSubsteps().getFirst();
        assertNull(ModelCommitWireCodec.tryEncode(new RequestBatch<>(List.of(
                new CommitModels(
                        cascade.getCommitId(), cascade.getReadStateIndex(),
                        cascade.getReadModelIds(),
                        List.of(cascadeStep.toBuilder()
                                .targets(List.of(cascadeStep.getTargets().getFirst()
                                        .toBuilder()
                                        .delete(true)
                                        .cascadeDelete(true)
                                        .build()))
                                .build()),
                        cascade.getConflictPolicy(), cascade.getGuarantee(),
                        cascade.isPossibleDuplicate())))));
    }

    @Test
    void rejectsTruncatedCompactPayload() throws Exception {
        byte[] encoded =
                ModelCommitWireCodec.tryEncode(new RequestBatch<>(List.of(commit("one", false))));

        assertThrows(
                IOException.class,
                () -> ModelCommitWireCodec.tryDecode(
                        java.util.Arrays.copyOf(encoded, encoded.length - 1)));
    }

    private static CommitModels commit(String id, boolean possibleDuplicate) {
        return commit(
                id,
                "application/octet-stream",
                "ModelType",
                "source",
                "target",
                List.of("model-" + id),
                possibleDuplicate);
    }

    private static CommitModels commit(
            String id,
            String format,
            String modelType,
            String source,
            String messageTarget,
            List<String> readModelIds) {
        return commit(id, format, modelType, source, messageTarget, readModelIds, false);
    }

    private static CommitModels commit(
            String id,
            String format,
            String modelType,
            String source,
            String messageTarget,
            List<String> readModelIds,
            boolean possibleDuplicate) {
        SerializedMessage event =
                new SerializedMessage(
                        new Data<>(new byte[] {1, 2, 3}, "type-" + id, 2, format),
                        Metadata.of("tenant", "demo", "trace", id),
                        3,
                        4L,
                        source,
                        messageTarget,
                        5,
                        6L,
                        "message-" + id,
                        null);
        ModelCommitTarget target =
                ModelCommitTarget.builder()
                        .modelId("model-" + id)
                        .modelType(modelType)
                        .expectedSequenceNumber(-1L)
                        .storeEvent(true)
                        .updateState(true)
                        .relationships(List.of())
                        .build();
        return new CommitModels(
                "commit-" + id,
                42L,
                readModelIds,
                List.of(
                        ModelCommitStep.builder()
                                .event(event)
                                .publishEvent(true)
                                .targets(List.of(target))
                                .build()),
                ModelConflictPolicy.ACCEPT,
                Guarantee.STORED,
                possibleDuplicate);
    }

    private static CommitModelsResult result(
            long requestId, String id, long stateIndex, Long eventIndex) {
        return CommitModelsResult.accepted(
                requestId,
                "commit-" + id,
                List.of(
                        new ModelUpdate(
                                ModelUpdateKind.COMMIT,
                                "commit-" + id,
                                0,
                                stateIndex,
                                eventIndex,
                                List.of(
                                        new ModelCommitTargetResult(
                                                "model-" + id,
                                                7L,
                                                true)))));
    }

    private static CommitModelsResult compactResult(
            long requestId, String id, long stateIndex, Long eventIndex) {
        return CommitModelsResult.acceptedSingleTarget(
                requestId, "commit-" + id, stateIndex, eventIndex,
                "model-" + id, 7L, true);
    }

    private static CommitModels withEventMessageIdMatchingCommitId(
            CommitModels commit) {
        ModelCommitStep step = commit.getSubsteps().getFirst();
        SerializedMessage event = step.getEvent();
        SerializedMessage matchingEvent = new SerializedMessage(
                event.getData(), event.getMetadata(), event.getSegment(),
                event.getIndex(), event.getSource(), event.getTarget(),
                event.getRequestId(), event.getTimestamp(), commit.getCommitId(),
                event.getOriginalRevision());
        return new CommitModels(
                commit.getCommitId(), commit.getReadStateIndex(),
                commit.getReadModelIds(),
                List.of(step.toBuilder().event(matchingEvent).build()),
                commit.getConflictPolicy(), commit.getGuarantee(),
                commit.isPossibleDuplicate());
    }
}
