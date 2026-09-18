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
package io.fluxzero.sdk.publishing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.LocalHandlerResult;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class WebResponseDeserializationTest {
    private final JacksonSerializer serializer = new JacksonSerializer();

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void decodesTypedAndByteResponsesExactlyOnce(boolean compressed, boolean typed) {
        Reply expected = new Reply("a".repeat(3000));
        var data = typed ? serializer.serialize(expected)
                : new Data<>(expected.text().getBytes(UTF_8), byte[].class.getName(), 0, "text/plain");
        var wire = compressed ? data.map(CompressionAlgorithm.GZIP::compress) : data;
        var headers = new java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Content-Length", List.of(String.valueOf(wire.getValue().length)));
        headers.put("X-Example", List.of("retained"));
        if (compressed) headers.put("content-encoding", List.of("gzip"));
        var source = new SerializedMessage(wire, WebResponse.asMetadata(201, headers).with("custom", "value"),
                                           "response", 0L);
        WebResponse response = (WebResponse) receive(source, MessageType.WEBREQUEST).join();
        if (typed) {
            assertEquals(expected, response.getPayload());
        } else {
            assertArrayEquals(data.getValue(), response.getPayload());
            assertSame(response.getPayload(), response.getPayload());
        }
        assertEquals(201, response.getStatus());
        assertEquals("retained", response.getHeader("X-Example"));
        assertEquals("value", response.getMetadata().get("custom"));
        assertNull(response.getHeader("Content-Encoding"));
        assertEquals(String.valueOf(data.getValue().length), response.getHeader("Content-Length"));
        assertEquals(compressed ? List.of("gzip") : null, WebResponse.getHeaders(source.getMetadata()).get("Content-Encoding"));
        assertArrayEquals(wire.getValue(), source.getData().getValue());
        assertEquals(expected.text(), typed ? ((Reply) serializer.deserialize(response.serialize(serializer))).text()
                : new String((byte[]) serializer.deserialize(response.serialize(serializer)), UTF_8));
    }

    @Test
    void malformedGzipFailsTheFuture() {
        var data = serializer.serialize(new Reply("bad")).map(CompressionAlgorithm.GZIP::compress)
                .map(bytes -> java.util.Arrays.copyOf(bytes, bytes.length - 3));
        var source = new SerializedMessage(data,
                WebResponse.asMetadata(200, Map.of("Content-Encoding", List.of("gzip"))), "response", 0L);
        var result = assertDoesNotThrow(() -> receive(source, MessageType.WEBREQUEST));
        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void ordinaryResultsDoNotInterpretHttpMetadata() {
        var source = new SerializedMessage(serializer.serialize(new Reply("ordinary")),
                Metadata.of(WebResponse.headersKey, Map.of("Content-Encoding", List.of("gzip"))), "response", 0L);
        assertEquals(new Reply("ordinary"), receive(source, MessageType.COMMAND).join().getPayload());
    }

    @Test
    void decompressesBeforeConfiguredUpcastersReadJsonAndMetadata() {
        var data = serializer.serialize(new Reply("old")).map(CompressionAlgorithm.GZIP::compress);
        var source = new SerializedMessage(data,
                WebResponse.asMetadata(200, Map.of("Content-Encoding", List.of("gzip")))
                        .with("replacement", "upcasted"), "response", 0L);
        var configured = new JacksonSerializer(List.of(new ReplyUpcaster()));
        assertEquals(new Reply("upcasted"), receive(source, MessageType.WEBREQUEST, configured).join().getPayload());
    }

    static class ReplyUpcaster {
        @Upcast(type = "io.fluxzero.sdk.publishing.WebResponseDeserializationTest$Reply", revision = 0)
        ObjectNode upcast(ObjectNode payload, Metadata metadata) {
            return payload.put("text", metadata.get("replacement"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1048576})
    void decodesEmptyAndLargeBinaryBodiesWithoutInventingContentLength(int size) {
        byte[] payload = new byte[size];
        new java.util.Random(17).nextBytes(payload);
        var data = new Data<>(CompressionAlgorithm.GZIP.compress(payload), byte[].class.getName(), 0,
                              "application/octet-stream");
        var source = new SerializedMessage(data,
                WebResponse.asMetadata(200, Map.of("Content-Encoding", List.of("gzip"))), "response", 0L);
        var response = (WebResponse) receive(source, MessageType.WEBREQUEST).join();
        assertArrayEquals(payload, response.getPayload());
        assertNull(response.getHeader("Content-Length"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "br", "GZIP", "gzip, br"})
    void doesNotExpandSupportedEncodingsOrAlterUncompressedSerializerInput(String encoding) {
        var source = new SerializedMessage(serializer.serialize(new Reply("untouched")),
                WebResponse.asMetadata(200, Map.of("Content-Encoding", List.of(encoding))), "response", 0L);
        Serializer configured = responseSerializer();
        when(configured.deserialize(source)).thenReturn(new Reply("untouched"));
        var response = (WebResponse) receive(source, MessageType.WEBREQUEST, configured).join();
        verify(configured).deserialize(source);
        assertEquals(encoding, response.getHeader("Content-Encoding"));
        assertEquals(new Reply("untouched"), response.getPayload());
    }

    @Test
    void customSerializerReceivesDecodedEnvelopeWithoutLosingTypeOrTransportContext() {
        var plain = serializer.serialize(new Reply("custom")).withRevision(7);
        var source = new SerializedMessage(plain.map(CompressionAlgorithm.GZIP::compress),
                WebResponse.asMetadata(200, Map.of("Content-Encoding", List.of("gzip")))
                        .with("custom", "retained"), "original-id", 123L);
        source.setRequestId(42);
        source.setSource("sender");
        source.setTarget("receiver");
        Serializer configured = responseSerializer();
        when(configured.deserialize(any())).thenAnswer(invocation -> {
            SerializedMessage decoded = invocation.getArgument(0);
            assertEquals(plain, decoded.getData());
            assertEquals(source.getMessageId(), decoded.getMessageId());
            assertEquals(source.getTimestamp(), decoded.getTimestamp());
            assertEquals(source.getRequestId(), decoded.getRequestId());
            assertEquals(source.getSource(), decoded.getSource());
            assertEquals(source.getTarget(), decoded.getTarget());
            assertEquals("retained", decoded.getMetadata().get("custom"));
            return new Reply("custom");
        });
        assertEquals(new Reply("custom"), receive(source, MessageType.WEBREQUEST, configured).join().getPayload());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 304})
    void emptyBodyPreservesRepresentationHeaders(int status) {
        var source = new SerializedMessage(new Data<>(new byte[0], byte[].class.getName(), 0, "text/plain"),
                WebResponse.asMetadata(status, Map.of("Content-Encoding", List.of("gzip"),
                                                     "Content-Length", List.of("1234"))), "response", 0L);
        var response = (WebResponse) receive(source, MessageType.WEBREQUEST).join();
        assertEquals("gzip", response.getHeader("Content-Encoding"));
        assertEquals("1234", response.getHeader("Content-Length"));
        assertEquals(status, response.getStatus());
    }

    @Test
    void alreadyDecodedPayloadKeepsExistingTolerantBehaviorAndHeaders() {
        var source = new SerializedMessage(serializer.serialize(new Reply("already decoded")),
                WebResponse.asMetadata(200, Map.of("Content-Encoding", List.of("gzip"),
                                                     "Content-Length", List.of("1234"))), "response", 0L);
        var response = (WebResponse) receive(source, MessageType.WEBREQUEST).join();
        assertEquals(new Reply("already decoded"), response.getPayload());
        assertEquals("gzip", response.getHeader("Content-Encoding"));
        assertEquals("1234", response.getHeader("Content-Length"));
    }

    @Test
    void deserializedThrowableStillFailsFutureWithOriginalCause() {
        var source = new SerializedMessage(serializer.serialize(new Reply("error")).map(CompressionAlgorithm.GZIP::compress),
                WebResponse.asMetadata(500, Map.of("Content-Encoding", List.of("gzip"))), "response", 0L);
        Serializer configured = responseSerializer();
        var failure = new IllegalStateException("response error");
        when(configured.deserialize(any())).thenReturn(failure);
        assertSame(failure, assertThrows(CompletionException.class,
                () -> receive(source, MessageType.WEBREQUEST, configured).join()).getCause());
    }

    @Test
    void readsLazyCompressedDataOnlyOnce() {
        var plain = serializer.serialize(new Reply("lazy"));
        byte[] encoded = CompressionAlgorithm.GZIP.compress(plain.getValue());
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var lazy = new Data<>(() -> {
            assertEquals(1, reads.incrementAndGet());
            return encoded;
        }, plain.getType(), plain.getRevision(), plain.getFormat());
        var source = new SerializedMessage(lazy,
                WebResponse.asMetadata(200, Map.of("Content-Encoding", List.of("gzip"))), "response", 0L);
        assertEquals(new Reply("lazy"), receive(source, MessageType.WEBREQUEST).join().getPayload());
        assertEquals(1, reads.get());
    }

    private CompletableFuture<Message> receive(SerializedMessage response, MessageType type) {
        return receive(response, type, serializer);
    }

    private Serializer responseSerializer() {
        Serializer result = mock(Serializer.class);
        when(result.serialize(any())).thenReturn(serializer.serialize("request"));
        return result;
    }

    private CompletableFuture<Message> receive(SerializedMessage response, MessageType type, Serializer serializer) {
        var requests = mock(RequestHandler.class);
        when(requests.sendRequest(any(), any(), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        var handlers = mock(HandlerRegistry.class);
        when(handlers.handleResult(any(DeserializingMessage.class))).thenReturn(LocalHandlerResult.notHandled());
        var gateway = new DefaultGenericGateway(mock(Client.class), mock(GatewayClient.class), requests,
                serializer, DispatchInterceptor.noOp, type, null, handlers, mock(ResponseMapper.class));
        return gateway.sendForMessage(new Message("request"), Duration.ofSeconds(1));
    }

    public record Reply(String text) {}
}
