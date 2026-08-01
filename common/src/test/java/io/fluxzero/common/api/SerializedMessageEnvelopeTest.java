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

package io.fluxzero.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedMessageEnvelopeTest {

    @Test
    void roundTripsOpaquePayloadMetadataAndUnicodeHeaders() throws Exception {
        SerializedMessage source = message();

        SerializedMessage encoded = SerializedMessage.encode(source);
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertArrayEquals(source.getData().getValue(), decoded.getData().getValue());
        assertEquals(source.getData().getType(), decoded.getData().getType());
        assertEquals(source.getData().getRevision(), decoded.getData().getRevision());
        assertEquals(source.getData().getFormat(), decoded.getData().getFormat());
        assertEquals("München-東京", decoded.getMetadata().get("tenant"));
        assertEquals(source.getSegment(), decoded.getSegment());
        assertEquals(source.getIndex(), decoded.getIndex());
        assertEquals(source.getSource(), decoded.getSource());
        assertEquals(source.getTarget(), decoded.getTarget());
        assertEquals(source.getRequestId(), decoded.getRequestId());
        assertEquals(source.getTimestamp(), decoded.getTimestamp());
        assertEquals(source.getMessageId(), decoded.getMessageId());
    }

    @Test
    void patchesFixedRuntimeHeadersWithoutReencodingApplicationBytes() throws Exception {
        SerializedMessage encoded = SerializedMessage.encode(message());
        encoded.setIndex(123_456L);
        encoded.setSegment(null);
        encoded.setRequestId(null);
        encoded.setTimestamp(987_654L);
        encoded.setOriginalRevision(1);

        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertEquals(123_456L, decoded.getIndex());
        assertNull(decoded.getSegment());
        assertNull(decoded.getRequestId());
        assertEquals(987_654L, decoded.getTimestamp());
        assertEquals(1, decoded.getOriginalRevision());
        assertTrue(encoded.isReusable());
        assertArrayEquals(message().getData().getValue(), decoded.getData().getValue());
        assertEquals("München-東京", decoded.getMetadata().get("tenant"));
    }

    @Test
    void independentlyDecodedEnvelopeDoesNotPatchTheInputArray() throws Exception {
        byte[] input = SerializedMessage.encode(message()).copyEnvelope();
        byte[] unchanged = input.clone();

        SerializedMessage decoded = SerializedMessage.decode(input, 0, input.length);
        decoded.setIndex(123_456L);

        assertArrayEquals(unchanged, input);
        assertEquals(123_456L, decoded.getIndex());
    }

    @Test
    void identicalFixedHeaderMutationsLeaveTheEnvelopeUntouched() {
        SerializedMessage encoded = SerializedMessage.encode(message());
        byte[] before = encoded.copyEnvelope();

        encoded.setSegment(encoded.getSegment());
        encoded.setIndex(encoded.getIndex());
        encoded.setRequestId(encoded.getRequestId());
        encoded.setTimestamp(encoded.getTimestamp());
        encoded.setOriginalRevision(null);

        assertTrue(encoded.isReusable());
        assertArrayEquals(before, encoded.copyEnvelope());
    }

    @Test
    void variableWidthMutationFallsBackToANewEnvelope() throws Exception {
        SerializedMessage encoded = SerializedMessage.encode(message());
        encoded.setTarget("other-target");

        assertFalse(encoded.isReusable());
        SerializedMessage replacement = SerializedMessage.encode(encoded);
        assertTrue(replacement.isReusable());
        assertEquals("other-target", SerializedMessage.decode(
                replacement.copyEnvelope(), 0, replacement.envelopeSize()).getTarget());
    }

    @Test
    void reencodingADirtyEnvelopeCopiesDirectlyFromTheDeferredPayloadSlice()
            throws Exception {
        SerializedMessage encoded =
                SerializedMessage.encode(
                        message());
        SerializedMessage decoded =
                SerializedMessage.decode(
                        encoded.copyEnvelope(), 0,
                        encoded.envelopeSize());
        decoded.setTarget("other-target");

        SerializedMessage replacement =
                SerializedMessage.encode(decoded);

        assertFalse(decoded.isPayloadMaterialized());
        assertArrayEquals(
                message().getData().getValue(),
                SerializedMessage.decode(
                                replacement.copyEnvelope(),
                                0,
                                replacement.envelopeSize())
                        .getData().getValue());
    }

    @Test
    void dataCopyRetainsEncodedHeadersButResetsTransportFields()
            throws Exception {
        SerializedMessage encodedSource =
                SerializedMessage.encode(
                        message());
        SerializedMessage source =
                SerializedMessage.decode(
                        encodedSource.copyEnvelope(),
                        0,
                        encodedSource.envelopeSize());
        SerializedMessage copy =
                new SerializedMessage(
                        source,
                        Metadata.of("copy", true),
                        "copy-id", 42L);
        SerializedMessage encodedCopy =
                SerializedMessage.encode(copy);

        SerializedMessage decodedCopy =
                SerializedMessage.decode(
                        encodedCopy.copyEnvelope(),
                        0,
                        encodedCopy.envelopeSize());

        assertFalse(source.isPayloadMaterialized());
        assertEquals("type-😀", decodedCopy.getType());
        assertEquals("application/json", decodedCopy.getData().getFormat());
        assertNull(decodedCopy.getSegment());
        assertNull(decodedCopy.getIndex());
        assertNull(decodedCopy.getSource());
        assertNull(decodedCopy.getTarget());
        assertNull(decodedCopy.getRequestId());
        assertEquals(42L, decodedCopy.getTimestamp());
        assertEquals("copy-id", decodedCopy.getMessageId());
        assertEquals(
                "true",
                decodedCopy.getMetadata().get("copy"));
    }

    @Test
    void dataCopyRetainsAnUnchangedEncodedMessageId()
            throws Exception {
        SerializedMessage encodedSource =
                SerializedMessage.encode(
                        message());
        SerializedMessage source =
                SerializedMessage.decode(
                        encodedSource.copyEnvelope(),
                        0, encodedSource.envelopeSize());
        String messageId = source.getMessageId();

        SerializedMessage encodedCopy =
                SerializedMessage.encode(
                        new SerializedMessage(
                                source, Metadata.empty(),
                                messageId, 42L));

        assertFalse(source.isPayloadMaterialized());
        assertEquals(
                messageId,
                SerializedMessage.decode(
                                encodedCopy.copyEnvelope(),
                                0, encodedCopy.envelopeSize())
                        .getMessageId());
    }

    @Test
    void readsOpaqueMetadataKeysAndChunkFlagsDirectly()
            throws Exception {
        SerializedMessage encoded = SerializedMessage.encode(
                new SerializedMessage(
                        new Data<>(new byte[]{1}, "type", 0),
                        Metadata.of(
                                "tenant", "München",
                                HasMetadata.FINAL_CHUNK, "false"),
                        "message-id", 1L));
        byte[] envelope = encoded.copyEnvelope();
        SerializedMessage decoded = SerializedMessage.decode(
                envelope, 0, envelope.length);

        assertTrue(decoded.metadataContainsKey("tenant"));
        assertFalse(decoded.metadataContainsKey("missing"));
        assertEquals("München", decoded.getMetadataValue("tenant"));
        assertNull(decoded.getMetadataValue("missing"));
        assertTrue(decoded.chunked());
        assertTrue(decoded.firstChunk());
        assertFalse(decoded.lastChunk());
        assertFalse(decoded.isMetadataMaterialized());
    }

    @Test
    void decodesConcatenatedMessagesIntoIndependentOpaqueEnvelopes() throws Exception {
        SerializedMessage first = SerializedMessage.encode(message());
        SerializedMessage second = SerializedMessage.encode(message().withSegment(7));
        byte[] sequence = new byte[first.envelopeSize() + second.envelopeSize()];
        first.copyEnvelopeTo(sequence, 0);
        second.copyEnvelopeTo(sequence, first.envelopeSize());

        List<SerializedMessage> decoded = SerializedMessage.decodeAll(sequence);
        java.util.Arrays.fill(sequence, (byte) 0);

        assertEquals(2, decoded.size());
        assertEquals(3, decoded.getFirst().getSegment());
        assertEquals(7, decoded.getLast().getSegment());
        assertSame(decoded.getFirst(), SerializedMessage.encode(decoded.getFirst()));
    }

    @Test
    void ownedSequenceViewsAvoidCopiesAndPatchOnlyTheirEnvelope() throws Exception {
        SerializedMessage first = SerializedMessage.encode(message());
        SerializedMessage second = SerializedMessage.encode(message().withSegment(7));
        byte[] sequence = new byte[first.envelopeSize() + second.envelopeSize()];
        first.copyEnvelopeTo(sequence, 0);
        second.copyEnvelopeTo(sequence, first.envelopeSize());
        List<SerializedMessage> independent = SerializedMessage.decodeAll(sequence);

        List<SerializedMessage> views = SerializedMessage.decodeAllViews(sequence);
        views.getLast().setIndex(321L);

        assertEquals(99L, independent.getLast().getIndex());
        assertEquals(99L, SerializedMessage.readIndex(sequence, 0, sequence.length));
        assertEquals(321L, SerializedMessage.readIndex(
                sequence, first.envelopeSize(), second.envelopeSize()));
        assertEquals("type-😀", views.getLast().getType());
        assertEquals("doel-😀", views.getLast().getTarget());
    }

    @Test
    void headerInspectionAndPatchingKeepApplicationBytesOpaque() throws Exception {
        SerializedMessage encoded = SerializedMessage.encode(message());
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertFalse(decoded.isPayloadMaterialized());
        assertFalse(decoded.isMetadataMaterialized());
        assertFalse(decoded.areDataHeadersMaterialized());
        assertFalse(decoded.isTargetMaterialized());
        assertEquals(99L, decoded.getIndex());
        assertFalse(decoded.areDataHeadersMaterialized());
        assertFalse(decoded.isTargetMaterialized());
        assertEquals("type-😀", decoded.getType());
        assertFalse(decoded.areDataHeadersMaterialized());
        assertFalse(decoded.isPayloadMaterialized());
        Data.ByteArrayView payloadView = decoded.getData().byteArrayView();
        assertNotNull(payloadView);
        assertEquals(4, payloadView.length());
        assertFalse(decoded.isPayloadMaterialized());
        assertEquals("application/json", decoded.getData().getFormat());
        assertTrue(decoded.areDataHeadersMaterialized());
        assertEquals("doel-😀", decoded.getTarget());
        assertTrue(decoded.isTargetMaterialized());
        assertEquals(4L, decoded.getBytes());
        decoded.setIndex(100L);
        decoded.setSegment(7);
        assertSame(decoded, SerializedMessage.encode(decoded));
        assertFalse(decoded.isPayloadMaterialized());
        assertFalse(decoded.isMetadataMaterialized());

        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.getData().getValue());
        assertTrue(decoded.isPayloadMaterialized());
        assertFalse(decoded.isMetadataMaterialized());
        assertEquals("München-東京", decoded.getMetadata().get("tenant"));
        assertFalse(decoded.isMetadataMaterialized());
    }

    @Test
    void comparesAsciiTargetWithoutMaterializingIt() throws Exception {
        SerializedMessage source = message();
        source.setTarget("tracker-123");
        SerializedMessage encoded = SerializedMessage.encode(source);
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertFalse(decoded.isTargetMaterialized());
        assertTrue(decoded.targetEquals("tracker-123"));
        assertFalse(decoded.targetEquals("tracker-456"));
        assertFalse(decoded.targetEquals(null));
        assertFalse(decoded.isTargetMaterialized());
    }

    @Test
    void comparesAsciiTypeWithoutMaterializingIt() throws Exception {
        SerializedMessage source = message().withData(
                new Data<>(new byte[]{1, 2, 3, 4}, "example.Command", 2, "application/json"));
        SerializedMessage encoded = SerializedMessage.encode(source);
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertFalse(decoded.isTypeMaterialized());
        assertTrue(decoded.typeEquals("example.Command"));
        assertFalse(decoded.typeEquals("example.Event"));
        assertFalse(decoded.typeEquals(null));
        assertFalse(decoded.isTypeMaterialized());
        assertFalse(decoded.isPayloadMaterialized());
    }

    @Test
    void unicodeComparisonsRemainCorrectViaLazyStringFallback() throws Exception {
        SerializedMessage encoded = SerializedMessage.encode(message());
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertTrue(decoded.typeEquals("type-😀"));
        assertTrue(decoded.targetEquals("doel-😀"));
        assertFalse(decoded.typeEquals("type-😃"));
        assertFalse(decoded.targetEquals("doel-😃"));
        assertFalse(decoded.isPayloadMaterialized());
        assertFalse(decoded.isMetadataMaterialized());
    }

    @Test
    void lazyHeadersPreserveMutationAndWithContracts() throws Exception {
        SerializedMessage source = message();
        SerializedMessage encoded = SerializedMessage.encode(source);
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        decoded.setTarget("changed-target");
        decoded.setOriginalRevision(null);
        SerializedMessage copy = decoded.withMetadata(Metadata.of("other", "value"));

        assertEquals(source.getData(), copy.getData());
        assertEquals(source.getSource(), copy.getSource());
        assertEquals("changed-target", copy.getTarget());
        assertEquals(source.getMessageId(), copy.getMessageId());
        assertEquals(source.getData().getRevision(), copy.getOriginalRevision());
        assertEquals("value", copy.getMetadata().get("other"));
    }

    @Test
    void nullPayloadHasTheSameZeroByteSizeBeforeAndAfterDecoding() throws Exception {
        SerializedMessage source = message().withData(
                new Data<byte[]>((byte[]) null, "type", 0, "application/json"));

        SerializedMessage encoded = SerializedMessage.encode(source);
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertEquals(0L, encoded.getBytes());
        assertEquals(0L, decoded.getBytes());
        assertNull(decoded.getData().getValue());
    }

    @Test
    void malformedSurrogatesUseTheStandardUtf8Replacement() throws Exception {
        SerializedMessage source = message();
        source.setSource("before\ud800after\udc00");

        SerializedMessage encoded = SerializedMessage.encode(source);
        SerializedMessage decoded = SerializedMessage.decode(
                encoded.copyEnvelope(), 0, encoded.envelopeSize());

        assertEquals("before?after?", decoded.getSource());
    }

    private static SerializedMessage message() {
        return new SerializedMessage(
                new Data<>(new byte[]{1, 2, 3, 4}, "type-😀", 2, "application/json"),
                Metadata.of("tenant", "München-東京"),
                3, 99L, "brön", "doel-😀", 12, 1234L,
                "bericht-東京", null);
    }
}
