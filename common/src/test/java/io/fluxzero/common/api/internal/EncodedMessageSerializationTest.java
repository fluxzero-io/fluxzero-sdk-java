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

package io.fluxzero.common.api.internal;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.api.tracking.TrackingWebSocketCodec;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class EncodedMessageSerializationTest {
    @ParameterizedTest
    @EnumSource(WebSocketTransportFormat.class)
    void sendingRetainedEnvelopesDoesNotMaterializeTheirPayloadOrMetadata(WebSocketTransportFormat format)
            throws Exception {
        var original = message();
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(original), 4096);
        var codec = WebSocketTransportCodecs.forFormat(format, new ObjectMapper(),
                                                       List.of(TrackingWebSocketCodec.INSTANCE));
        var expected = response(original);
        var actual = response(retained);
        byte[] expectedBytes = codec.encode(expected);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 20).mapToObj(i ->
                    executor.submit(() -> codec.encode(actual))).toList();
            for (var task : tasks) {
                assertArrayEquals(expectedBytes, task.get());
            }
        }
        assertNull(field(retained, "decodedData"));
        assertNull(field(retained, "decodedMetadata"));
    }

    @ParameterizedTest
    @EnumSource(value = WebSocketTransportFormat.class, names = {"JSON", "CBOR"})
    void serializationPreservesAllMutatedPublicFieldsAndNullMetadata(WebSocketTransportFormat format)
            throws Exception {
        var original = message();
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(original), 4096);
        var codec = WebSocketTransportCodecs.forFormat(format, new ObjectMapper());
        for (var value : List.of(original, retained)) {
            value.setSegment(7);
            value.setIndex(123L);
            value.setRequestId(99);
            value.setTimestamp(456L);
            value.setSource("new source");
            value.setTarget(null);
            value.setMessageId("new message");
            value.setData(new Data<>(new byte[]{3, 4}, "changed", 9, "json"));
            value.setMetadata(Metadata.of("changed", "value"));
            value.setOriginalRevision(2);
        }
        assertArrayEquals(codec.encode(response(original)), codec.encode(response(retained)));
        original.setMetadata(null);
        retained.setMetadata(null);
        original.setOriginalRevision(null);
        retained.setOriginalRevision(null);
        assertArrayEquals(codec.encode(response(original)), codec.encode(response(retained)));
    }

    @Test
    void serializationPreservesChangesToAnAlreadyMaterializedPayload() throws Exception {
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(message()), 4096);
        retained.getData().getValue()[0] = 42;
        var mapper = new ObjectMapper();
        var decoded = mapper.readValue(mapper.writeValueAsBytes(retained), SerializedMessage.class);
        assertArrayEquals(new byte[]{42, 2}, decoded.getData().getValue());
    }

    @Test
    void inspectingDataBeforeSerializationDoesNotCauseRetainedPayloadMaterialization() throws Exception {
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(message()), 4096);
        var slice = retained.getData().byteArrayView();
        new ObjectMapper().writeValueAsBytes(retained);
        assertNull(field(slice, "materialized"));
        assertNull(field(retained, "decodedMetadata"));
    }

    @Test
    void customSerializerKeepsRuntimeTypeAndPropertyContext() throws Exception {
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(message()), 4096);
        var mapper = new ObjectMapper().registerModule(new SimpleModule().addSerializer(
                SerializedMessage.class, new ContextSerializer("root")));
        var tree = mapper.readTree(mapper.writeValueAsBytes(new Holder(retained)));
        assertEquals("value:" + retained.getClass().getName() + ":sample", tree.get("value").asText());
        assertNull(field(retained, "decodedData"));
        assertNull(field(retained, "decodedMetadata"));
        var custom = new CustomMessage();
        assertEquals("value:" + CustomMessage.class.getName() + ":sample",
                     mapper.readTree(mapper.writeValueAsBytes(new Holder(custom))).get("value").asText());
    }

    @Test
    void customPolymorphicTypeIdRemainsTheEncodedRuntimeClass() throws Exception {
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(message()), 4096);
        var mapper = new ObjectMapper().addMixIn(SerializedMessage.class, TypeInfo.class);
        var tree = mapper.readTree(mapper.writeValueAsBytes(retained));
        assertEquals(retained.getClass().getName(), tree.get("@class").asText());
        assertNull(field(retained, "decodedData"));
        assertNull(field(retained, "decodedMetadata"));
    }

    @Test
    void customEmptySerializerKeepsNonEmptySuppression() throws Exception {
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(message()), 4096);
        var mapper = new ObjectMapper().registerModule(new SimpleModule().addSerializer(
                SerializedMessage.class, new JsonSerializer<SerializedMessage>() {
                    @Override
                    public boolean isEmpty(SerializerProvider provider, SerializedMessage value) { return true; }
                    @Override
                    public void serialize(SerializedMessage value, JsonGenerator generator, SerializerProvider provider)
                            throws IOException { generator.writeString("nonempty"); }
                }));
        assertEquals("{}", mapper.writeValueAsString(new NonEmpty(retained)));
    }

    @Test
    void unwrappedMessagesKeepFlattenedFieldsWithoutMaterializingTheRetainedEnvelope() throws Exception {
        var source = message();
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(source), 4096);
        var mapper = new ObjectMapper();
        assertEquals(mapper.writeValueAsString(new Unwrapped(source)), mapper.writeValueAsString(new Unwrapped(retained)));
        assertNull(field(retained, "decodedData"));
        assertNull(field(retained, "decodedMetadata"));
    }

    @Test
    void explicitObjectIdentityKeepsReferencesToTheOriginalInstance() throws Exception {
        var retained = BinaryWire.decodeEnvelope(BinaryWire.encodeEnvelope(message()), 4096);
        var mapper = new ObjectMapper().addMixIn(SerializedMessage.class, IdentityInfo.class);
        var tree = mapper.readTree(mapper.writeValueAsBytes(List.of(retained, retained)));
        assertEquals(1, tree.get(0).get("@id").asInt());
        assertEquals(1, tree.get(1).asInt());
    }

    private static SerializedMessage message() {
        return new SerializedMessage(new Data<>(new byte[]{1, 2}, "type", 1, "json"),
                Metadata.of("first", "one", "second", "two"), 0, 10L, "source", "target", 1, 20L, "sample", 1);
    }

    private static ResultBatch response(SerializedMessage message) {
        var read = new ReadResult(7, new MessageBatch(new int[]{0, 128}, List.of(message), 10L,
                                                     Position.newPosition(), true), 30L);
        read.setRequestReceivedTimestamp(1L);
        read.setResponseQueuedTimestamp(2L);
        read.setResponseSendStartTimestamp(3L);
        return new ResultBatch(List.of(read));
    }

    private static Object field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private record Holder(SerializedMessage value) {}
    private record NonEmpty(@JsonInclude(JsonInclude.Include.NON_EMPTY) SerializedMessage value) {}
    private record Unwrapped(@JsonUnwrapped(prefix = "message_") SerializedMessage value) {}

    @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class, property = "@id")
    private abstract static class IdentityInfo {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    private abstract static class TypeInfo {}

    private static class CustomMessage extends SerializedMessage {
        CustomMessage() {
            super(new Data<>(new byte[]{1}, "type", 1, "json"), Metadata.empty(), "sample", 1L);
        }
    }

    private static class ContextSerializer extends JsonSerializer<SerializedMessage> implements ContextualSerializer {
        private final String property;
        ContextSerializer(String property) { this.property = property; }
        @Override
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property) {
            return new ContextSerializer(property == null ? "root" : property.getName());
        }
        @Override
        public void serialize(SerializedMessage value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(property + ":" + value.getClass().getName() + ":" + value.getMessageId());
        }
    }
}
