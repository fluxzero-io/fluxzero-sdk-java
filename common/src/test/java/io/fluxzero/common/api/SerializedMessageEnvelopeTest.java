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

import io.fluxzero.common.api.internal.BinaryWire;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedMessageEnvelopeTest {

    @Test
    void binaryWireRoundTripsTheCompleteMessageValue() throws Exception {
        SerializedMessage source = message();

        Encoded decoded = roundTrip(source);

        assertMessageEquals(source, decoded.message());
        assertEquals(source.getBytes(), decoded.bytes().length);
    }

    @Test
    void binaryWireKeepsPayloadAndMetadataAsViewsUntilTheyAreRead() throws Exception {
        Encoded decoded = roundTrip(message());

        Data.ByteArrayView payload = decoded.message().getData().byteArrayView();
        Data.ByteArrayView metadata = decoded.message().getMetadata().toData().byteArrayView();
        assertNotNull(payload);
        assertNotNull(metadata);
        assertEquals(4, payload.length());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.message().getData().getValue());
        assertEquals("München-東京", decoded.message().getMetadataValue("tenant"));
    }

    @Test
    void preparedEnvelopeRetainsTheMessageContract() throws Exception {
        SerializedMessage source = message();

        SerializedMessage prepared = BinaryWire.prepareEnvelope(source);

        assertMessageEquals(source, prepared);
        assertArrayEquals(encode(source), encode(prepared));
        assertEquals(source, prepared);
        assertEquals(source.hashCode(), prepared.hashCode());
    }

    @Test
    void preparedEnvelopeNormalizesAbsentMetadata() throws Exception {
        SerializedMessage source = message();
        source.setMetadata(null);

        SerializedMessage prepared = BinaryWire.prepareEnvelope(source);

        assertEquals(Metadata.empty(), prepared.getMetadata());
        assertEquals(Metadata.empty(), roundTrip(prepared).message().getMetadata());
    }

    @Test
    void bulkPreparationRetainsAReusableEnvelopeSequence() throws Exception {
        SerializedMessage prepared = BinaryWire.prepareEnvelope(message());
        prepared.setIndex(123_456L);
        List<SerializedMessage> messages = List.of(prepared);

        List<SerializedMessage> result = BinaryWire.prepareEnvelopes(messages);

        assertSame(messages, result);
        assertEquals(123_456L, roundTrip(result.getFirst()).message().getIndex());
    }

    @Test
    void messageMutationsDoNotPatchTransportInputAndAreReflectedOnReencoding() throws Exception {
        Encoded decoded = roundTrip(message());
        byte[] unchanged = decoded.bytes().clone();

        decoded.message().setIndex(123_456L);
        decoded.message().setTarget("a-longer-target-😀");
        decoded.message().setMetadata(decoded.message().getMetadata().with("extra", "value-東京"));

        assertArrayEquals(unchanged, decoded.bytes());
        SerializedMessage replacement = roundTrip(decoded.message()).message();
        assertEquals(123_456L, replacement.getIndex());
        assertEquals("a-longer-target-😀", replacement.getTarget());
        assertEquals("value-東京", replacement.getMetadataValue("extra"));
        assertTrue(decoded.message().getBytes() > message().getBytes());
    }

    @Test
    void fixedRuntimeHeaderMutationsRoundTripWithoutChangingEnvelopeSize() throws Exception {
        SerializedMessage decoded = roundTrip(message()).message();
        long size = decoded.getBytes();

        decoded.setSegment(null);
        decoded.setIndex(123_456L);
        decoded.setRequestId(null);
        decoded.setTimestamp(987_654L);
        decoded.setOriginalRevision(7);

        SerializedMessage replacement = roundTrip(decoded).message();
        assertNull(replacement.getSegment());
        assertEquals(123_456L, replacement.getIndex());
        assertNull(replacement.getRequestId());
        assertEquals(987_654L, replacement.getTimestamp());
        assertEquals(7, replacement.getOriginalRevision());
        assertEquals(size, replacement.getBytes());
    }

    @Test
    void identicalFixedHeaderMutationsKeepTheEncodingStable() {
        SerializedMessage source = BinaryWire.prepareEnvelope(message());
        byte[] before = encode(source);

        source.setSegment(source.getSegment());
        source.setIndex(source.getIndex());
        source.setRequestId(source.getRequestId());
        source.setTimestamp(source.getTimestamp());
        source.setOriginalRevision(source.getOriginalRevision());

        assertArrayEquals(before, encode(source));
    }

    @Test
    void allVariableWidthMutationsAreReflectedOnReencoding() throws Exception {
        SerializedMessage decoded = roundTrip(message()).message();
        decoded.setData(new Data<>(new byte[]{9, 8}, "changed.Type", 5, "changed/format"));
        decoded.setMetadata(Metadata.of("changed", "yes"));
        decoded.setSource("changed-source");
        decoded.setTarget("changed-target");
        decoded.setMessageId("changed-message-id");

        SerializedMessage replacement = roundTrip(decoded).message();
        assertArrayEquals(new byte[]{9, 8}, replacement.getData().getValue());
        assertEquals("changed.Type", replacement.getType());
        assertEquals("changed/format", replacement.getData().getFormat());
        assertEquals("yes", replacement.getMetadataValue("changed"));
        assertEquals("changed-source", replacement.getSource());
        assertEquals("changed-target", replacement.getTarget());
        assertEquals("changed-message-id", replacement.getMessageId());
    }

    @Test
    void logicalDataCopyResetsTransportFields() throws Exception {
        SerializedMessage source = roundTrip(message()).message();
        SerializedMessage copy = new SerializedMessage(
                source.getData(), Metadata.of("copy", true), "copy-id", 42L);

        SerializedMessage decoded = roundTrip(copy).message();
        assertEquals("type-😀", decoded.getType());
        assertEquals("application/json", decoded.getData().getFormat());
        assertNull(decoded.getSegment());
        assertNull(decoded.getIndex());
        assertNull(decoded.getSource());
        assertNull(decoded.getTarget());
        assertNull(decoded.getRequestId());
        assertEquals(42L, decoded.getTimestamp());
        assertEquals("copy-id", decoded.getMessageId());
        assertEquals("true", decoded.getMetadataValue("copy"));
    }

    @Test
    void readsEncodedMetadataAndHonorsReplacementMetadata() throws Exception {
        SerializedMessage source = message();
        source.setMetadata(Metadata.of("tenant", "München-東京", "sequence", "123", "malformed", "x"));
        SerializedMessage decoded = roundTrip(source).message();

        assertTrue(decoded.metadataContainsKey("tenant"));
        assertFalse(decoded.metadataContainsKey("missing"));
        assertEquals("München-東京", decoded.getMetadataValue("tenant"));
        assertEquals(123L, decoded.getMetadataLongValue("sequence", -1));
        assertEquals(-1L, decoded.getMetadataLongValue("malformed", -1));

        decoded.setMetadata(Metadata.of("replacement", "value"));
        assertFalse(decoded.metadataContainsKey("tenant"));
        assertEquals("value", decoded.getMetadataValue("replacement"));
    }

    @Test
    void runtimeNumericFieldsDoNotChangeTheCompleteMessageSize() {
        SerializedMessage source = new SerializedMessage(
                new Data<>(new byte[]{1}, "type", 0, null), Metadata.empty(),
                null, null, null, null, null, null, "message", null);
        long size = source.getBytes();

        source.setSegment(1);
        source.setIndex(2L);
        source.setRequestId(3);
        source.setTimestamp(4L);

        assertEquals(size, source.getBytes());
    }

    @Test
    void clearingOriginalRevisionTracksAReplacementPayloadRevision() throws Exception {
        SerializedMessage decoded = roundTrip(message()).message();

        decoded.setOriginalRevision(null);
        decoded.setData(new Data<>(new byte[]{5}, "replacement", 7, Data.JSON_FORMAT));

        assertEquals(7, decoded.getOriginalRevision());
        assertEquals(7, roundTrip(decoded).message().getOriginalRevision());
    }

    @Test
    void roundTripsNullPayloadAndEmptyMetadata() throws Exception {
        SerializedMessage source = new SerializedMessage(
                new Data<byte[]>((byte[]) null, "type", 0, null), Metadata.empty(),
                null, null, null, null, null, null, "message", null);

        SerializedMessage decoded = roundTrip(source).message();

        assertNull(decoded.getData().getValue());
        assertEquals(Metadata.empty(), decoded.getMetadata());
        assertEquals(Data.JSON_FORMAT, decoded.getData().getFormat());
        assertEquals(source.getBytes(), decoded.getBytes());
    }

    @Test
    void retainsChunkSemanticsThroughOrdinaryMetadata() throws Exception {
        SerializedMessage source = message();
        source.setMetadata(Metadata.of(
                HasMetadata.FIRST_CHUNK, "false",
                HasMetadata.FINAL_CHUNK, "false",
                HasMetadata.CHUNK_INDEX, "2"));

        SerializedMessage decoded = roundTrip(source).message();

        assertTrue(decoded.chunked());
        assertFalse(decoded.firstChunk());
        assertFalse(decoded.lastChunk());
        assertEquals(2L, decoded.getMetadataLongValue(HasMetadata.CHUNK_INDEX, -1));
    }

    @Test
    void retainsDefaultAndFinalChunkSemantics() throws Exception {
        SerializedMessage ordinary = roundTrip(message()).message();
        assertFalse(ordinary.chunked());
        assertTrue(ordinary.firstChunk());
        assertTrue(ordinary.lastChunk());

        SerializedMessage finalChunk = message();
        finalChunk.setMetadata(Metadata.of(
                HasMetadata.FIRST_CHUNK, "false",
                HasMetadata.FINAL_CHUNK, "true"));
        finalChunk = roundTrip(finalChunk).message();
        assertTrue(finalChunk.chunked());
        assertFalse(finalChunk.firstChunk());
        assertTrue(finalChunk.lastChunk());
    }

    @Test
    void rejectsReservedEnvelopeFlags() {
        byte[] encoded = encode(message());
        encoded[5] |= 1 << 4;

        assertThrows(IOException.class,
                     () -> BinaryWire.decodeEnvelope(encoded, encoded.length));
    }

    @Test
    void rejectsTruncatedOversizedAndTrailingEnvelopeContent() {
        byte[] encoded = encode(message());
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(IOException.class,
                     () -> BinaryWire.decodeEnvelope(truncated, truncated.length));

        byte[] oversized = encoded.clone();
        oversized[11]++;
        assertThrows(IOException.class,
                     () -> BinaryWire.decodeEnvelope(oversized, oversized.length));

        byte[] trailing = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, trailing, 0, encoded.length);
        assertThrows(IOException.class,
                     () -> BinaryWire.decodeEnvelope(trailing, trailing.length));
    }

    @Test
    void nestedEnvelopeAddsOnlyItsOwningLengthBoundary() throws Exception {
        SerializedMessage source = message();
        int envelopeSize = Math.toIntExact(source.getBytes());
        BinaryWire.Writer writer = new BinaryWire.Writer(
                Integer.BYTES + envelopeSize, Integer.BYTES + envelopeSize);

        writer.writeEnvelope(source);

        byte[] nested = writer.toExactByteArray();
        assertEquals(envelopeSize, BinaryWire.peekInt(nested, 0));
        BinaryWire.Reader reader = new BinaryWire.Reader(nested, nested.length);
        assertMessageEquals(source, reader.readEnvelope());
        assertEquals(0, reader.available());
    }

    @Test
    void concatenatedEnvelopeSequenceRetainsEachMessageContract() throws Exception {
        SerializedMessage first = message();
        SerializedMessage second = message().withSegment(7);
        byte[] sequence = BinaryWire.encodeEnvelopes(List.of(first, second));
        byte[] unchanged = sequence.clone();

        List<SerializedMessage> decoded = BinaryWire.decodeEnvelopes(sequence, sequence.length);
        decoded.getLast().setIndex(321L);

        assertEquals(2, decoded.size());
        assertEquals(3, decoded.getFirst().getSegment());
        assertEquals(7, decoded.getLast().getSegment());
        assertEquals(321L, decoded.getLast().getIndex());
        assertArrayEquals(unchanged, sequence);
    }

    @Test
    void comparesRoutingValuesWithoutAlternativeRepresentations() {
        SerializedMessage message = roundTripUnchecked(message());

        assertTrue(message.typeEquals("type-😀"));
        assertFalse(message.typeEquals("other"));
        assertTrue(message.targetEquals("doel-😀"));
        assertFalse(message.targetEquals(null));
    }

    @Test
    void withMethodsPreserveTheOtherLazyMessageValues() throws Exception {
        SerializedMessage decoded = roundTrip(message()).message();
        decoded.setTarget("changed-target");
        decoded.setOriginalRevision(null);

        SerializedMessage copy = decoded.withMetadata(Metadata.of("other", "value"));

        assertEquals(message().getData(), copy.getData());
        assertEquals(message().getSource(), copy.getSource());
        assertEquals("changed-target", copy.getTarget());
        assertEquals(message().getMessageId(), copy.getMessageId());
        assertEquals(message().getData().getRevision(), copy.getOriginalRevision());
        assertEquals("value", copy.getMetadataValue("other"));
    }

    @Test
    void reportsExactSizeAfterEveryVariableWidthMutation() {
        SerializedMessage source = message();
        assertEquals(encode(source).length, source.getBytes());

        source.setTarget("a-longer-target-😀");
        source.setMetadata(source.getMetadata().with("extra", "metadata-value-東京"));
        assertEquals(encode(source).length, source.getBytes());

        source.setData(new Data<>(new byte[37], "longer.type.Name", 4, "application/custom"));
        assertEquals(encode(source).length, source.getBytes());
        source.setSource("a-longer-source");
        assertEquals(encode(source).length, source.getBytes());
        source.setMessageId("a-longer-message-id");
        assertEquals(encode(source).length, source.getBytes());
    }

    @Test
    void subclassOverridesAreNotHiddenByCachedBaseValues() {
        String[] sourceValue = {"short"};
        SerializedMessage source = new SerializedMessage(
                new Data<>(new byte[1], "type", 0, "format"),
                Metadata.empty(), "message-id", 1L) {
            @Override
            public String getSource() {
                return sourceValue[0];
            }
        };

        long initialSize = source.getBytes();
        sourceValue[0] = "a-much-longer-overridden-source";

        assertEquals(encode(source).length, source.getBytes());
        assertTrue(source.getBytes() > initialSize);
    }

    @Test
    void malformedSurrogatesUseTheStandardUtf8Replacement() throws Exception {
        SerializedMessage source = message();
        source.setSource("before\ud800after\udc00");

        SerializedMessage decoded = roundTrip(source).message();

        assertEquals("before?after?", decoded.getSource());
        assertEquals(encode(source).length, source.getBytes());
    }

    @Test
    void repeatedRoutingHeadersRetainExactUtf8Bytes() throws Exception {
        for (int i = 0; i < 3; i++) {
            SerializedMessage source = message();
            source.setSource("repeated-source-😀");
            source.setTarget("repeated-target-東京");

            SerializedMessage decoded = roundTrip(source).message();

            assertEquals(source.getSource(), decoded.getSource());
            assertEquals(source.getTarget(), decoded.getTarget());
        }
    }

    private static Encoded roundTrip(SerializedMessage source) throws IOException {
        byte[] bytes = encode(source);
        return new Encoded(bytes, BinaryWire.decodeEnvelope(bytes, bytes.length));
    }

    private static SerializedMessage roundTripUnchecked(SerializedMessage source) {
        try {
            return roundTrip(source).message();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] encode(SerializedMessage source) {
        return BinaryWire.encodeEnvelope(source);
    }

    private static void assertMessageEquals(SerializedMessage expected, SerializedMessage actual) {
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
        assertEquals(expected.getOriginalRevision(), actual.getOriginalRevision());
    }

    private static SerializedMessage message() {
        return new SerializedMessage(
                new Data<>(new byte[]{1, 2, 3, 4}, "type-😀", 2, "application/json"),
                Metadata.of("tenant", "München-東京"),
                3, 99L, "brön", "doel-😀", 12, 1234L,
                "bericht-東京", 1);
    }

    private record Encoded(byte[] bytes, SerializedMessage message) {
    }
}
