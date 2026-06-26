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

package io.fluxzero.sdk.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DefaultTrackingDeserializationTest {

    @Test
    void nonChunkedSerializedMessageExpandsToAllSplitUpcastResultsInMessageStream() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerCasters(new SplittingStringUpcaster());
        DefaultTracking tracking = tracking(serializer);
        SerializedMessage serializedMessage = message(serializer, "first", 42L);

        var messages = tracking.deserializeMessageList(
                List.of(serializedMessage), null, mock(TrackingClient.class), new ConcurrentHashMap<>(),
                100);

        assertEquals(2, messages.size());
        assertEquals("first", messages.getFirst().getPayload());
        assertEquals("second", messages.get(1).getPayload());
        tracking.close();
    }

    @Test
    void nonChunkedSplitUpcastResultsKeepOrderAroundChunkedMessages() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerCasters(new SplittingStringUpcaster());
        DefaultTracking tracking = tracking(serializer);

        var messages = tracking.deserializeMessageList(
                List.of(
                        message(serializer, "before", 1L),
                        finalChunkedMessage(serializer, "chunked", 2L),
                        message(serializer, "after", 3L)),
                null, mock(TrackingClient.class), new ConcurrentHashMap<>(), 100);

        assertEquals(5, messages.size());
        assertEquals("before", messages.getFirst().getPayload());
        assertEquals("second", messages.get(1).getPayload());
        assertEquals("chunked", messages.get(2).getPayload());
        assertEquals("after", messages.get(3).getPayload());
        assertEquals("second", messages.get(4).getPayload());
        tracking.close();
    }

    private static DefaultTracking tracking(JacksonSerializer serializer) {
        return new DefaultTracking(
                MessageType.EVENT, mock(ResultGateway.class), List.of(), List.of(), serializer,
                mock(HandlerFactory.class));
    }

    private static SerializedMessage message(JacksonSerializer serializer, Object payload, long index) {
        SerializedMessage message = new SerializedMessage(
                serializer.serialize(payload), Metadata.empty(), "message-" + index, System.currentTimeMillis());
        message.setIndex(index);
        message.setSegment(0);
        return message;
    }

    private static SerializedMessage finalChunkedMessage(JacksonSerializer serializer, Object payload, long index) {
        SerializedMessage message = new SerializedMessage(
                serializer.serialize(payload),
                Metadata.of(
                        HasMetadata.FIRST_CHUNK, Boolean.TRUE.toString(),
                        HasMetadata.FINAL_CHUNK, Boolean.TRUE.toString(),
                        HasMetadata.CHUNK_INDEX, "0"),
                "message-" + index,
                System.currentTimeMillis());
        message.setIndex(index);
        message.setSegment(0);
        return message;
    }

    static class SplittingStringUpcaster {
        @Upcast(type = "java.lang.String", revision = 0)
        Stream<Data<JsonNode>> split(Data<JsonNode> input) {
            return Stream.of(
                    new Data<>(input.getValue(), input.getType(), 1, input.getFormat()),
                    new Data<>(TextNode.valueOf("second"), input.getType(), 1, input.getFormat()));
        }
    }
}
