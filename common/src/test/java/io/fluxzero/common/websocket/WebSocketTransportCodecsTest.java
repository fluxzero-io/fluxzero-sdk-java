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
import io.fluxzero.common.api.publishing.Append;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.ReadFromIndex;
import io.fluxzero.common.api.tracking.ReadResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.CBOR;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.JSON;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketTransportCodecsTest {
    private static final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndAddModules()
            .disable(WRITE_DATES_AS_TIMESTAMPS)
            .build();

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

    private static JsonType roundTrip(WebSocketTransportCodec codec, JsonType value) throws Exception {
        return codec.decode(codec.encode(value));
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
}
