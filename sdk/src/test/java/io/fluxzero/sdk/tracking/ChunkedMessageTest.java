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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ChunkedMessageTest {

    @Test
    void streamChunkedEventPayloadIntoInputStreamHandler() {
        CompletableFuture<String> handled = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(DeserializingMessage message) throws Exception {
                InputStream stream = message.getPayloadAs(InputStream.class);
                handled.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }).whenExecuting(fc -> {
            long now = System.currentTimeMillis();
            SerializedMessage firstChunk = new SerializedMessage(
                    new Data<>("hello ".getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "false", HasMetadata.FIRST_CHUNK, "true"),
                    fc.identityProvider().nextTechnicalId(), now);
            firstChunk.setSegment(0);
            SerializedMessage secondChunk = new SerializedMessage(
                    new Data<>("world".getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "true", HasMetadata.FIRST_CHUNK, "false"),
                    firstChunk.getMessageId(), now + 1);
            secondChunk.setSegment(firstChunk.getSegment());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> assertEquals("hello world", handled.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void startsHandlingChunkedEventOnFirstChunk() throws Exception {
        CompletableFuture<String> firstBytesRead = new CompletableFuture<>();
        CompletableFuture<String> completed = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(DeserializingMessage message) throws Exception {
                InputStream stream = message.getPayloadAs(InputStream.class);
                byte[] firstPart = stream.readNBytes(6);
                firstBytesRead.complete(new String(firstPart, StandardCharsets.UTF_8));
                completed.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }).whenExecuting(fc -> {
            long now = System.currentTimeMillis();
            SerializedMessage firstChunk = new SerializedMessage(
                    new Data<>("hello ".getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "false", HasMetadata.FIRST_CHUNK, "true"),
                    fc.identityProvider().nextTechnicalId(), now);
            firstChunk.setSegment(0);
            SerializedMessage secondChunk = new SerializedMessage(
                    new Data<>("world".getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "true", HasMetadata.FIRST_CHUNK, "false"),
                    firstChunk.getMessageId(), now + 1);
            secondChunk.setSegment(firstChunk.getSegment());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            assertEquals("hello ", firstBytesRead.get(1, TimeUnit.SECONDS));
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> assertEquals("world", completed.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void skipsChunkWhenFirstChunkWasNotObserved() {
        CompletableFuture<String> handled = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(DeserializingMessage message) throws Exception {
                InputStream stream = message.getPayloadAs(InputStream.class);
                handled.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }).whenExecuting(fc -> {
            long now = System.currentTimeMillis();
            SerializedMessage orphanChunk = new SerializedMessage(
                    new Data<>("world".getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "true", HasMetadata.FIRST_CHUNK, "false"),
                    fc.identityProvider().nextTechnicalId(), now);
            orphanChunk.setSegment(0);
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, orphanChunk).get();
        }).expectThat(fz -> assertNull(handled.completeOnTimeout(null, 200, TimeUnit.MILLISECONDS).join()));
    }

    @Test
    void deserializesChunkedJsonPayloadIntoSingleObjectAfterFinalChunk() {
        LargePayload expected = new LargePayload("hello world", 42);
        CompletableFuture<LargePayload> handled = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(DeserializingMessage message) {
                handled.complete(message.getPayloadAs(LargePayload.class));
            }
        }).whenExecuting(fc -> {
            long now = System.currentTimeMillis();
            Data<byte[]> serialized = fc.serializer().serialize(expected);
            int split = serialized.getValue().length / 2;
            SerializedMessage firstChunk = new SerializedMessage(
                    new Data<>(Arrays.copyOfRange(serialized.getValue(), 0, split),
                               serialized.getType(), serialized.getRevision(), serialized.getFormat()),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "false", HasMetadata.FIRST_CHUNK, "true"),
                    fc.identityProvider().nextTechnicalId(), now);
            firstChunk.setSegment(0);
            SerializedMessage secondChunk = new SerializedMessage(
                    new Data<>(Arrays.copyOfRange(serialized.getValue(), split, serialized.getValue().length),
                               serialized.getType(), serialized.getRevision(), serialized.getFormat()),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "true", HasMetadata.FIRST_CHUNK, "false"),
                    firstChunk.getMessageId(), now + 1);
            secondChunk.setSegment(firstChunk.getSegment());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            assertFalse(handled.isDone());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> assertEquals(expected, handled.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void handlesChunkedJsonPayloadAsTypedHandlerParameter() {
        LargePayload expected = new LargePayload("hello world", 42);
        CompletableFuture<LargePayload> handled = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(LargePayload payload) {
                handled.complete(payload);
            }
        }).whenExecuting(fc -> {
            long now = System.currentTimeMillis();
            Data<byte[]> serialized = fc.serializer().serialize(expected);
            int split = serialized.getValue().length / 2;
            SerializedMessage firstChunk = new SerializedMessage(
                    new Data<>(Arrays.copyOfRange(serialized.getValue(), 0, split),
                               serialized.getType(), serialized.getRevision(), serialized.getFormat()),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "false", HasMetadata.FIRST_CHUNK, "true"),
                    fc.identityProvider().nextTechnicalId(), now);
            firstChunk.setSegment(0);
            SerializedMessage secondChunk = new SerializedMessage(
                    new Data<>(Arrays.copyOfRange(serialized.getValue(), split, serialized.getValue().length),
                               serialized.getType(), serialized.getRevision(), serialized.getFormat()),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "true", HasMetadata.FIRST_CHUNK, "false"),
                    firstChunk.getMessageId(), now + 1);
            secondChunk.setSegment(firstChunk.getSegment());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            assertFalse(handled.isDone());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> assertEquals(expected, handled.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void keepsThreadLocalContextForAsyncChunkedHandlers() {
        CompletableFuture<Boolean> trackerPresent = new CompletableFuture<>();
        CompletableFuture<Boolean> fluxzeroPresent = new CompletableFuture<>();
        CompletableFuture<Boolean> currentMessagePresent = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(DeserializingMessage message) throws Exception {
                InputStream stream = message.getPayloadAs(InputStream.class);
                trackerPresent.complete(Tracker.current().isPresent());
                fluxzeroPresent.complete(Fluxzero.getOptionally().isPresent());
                currentMessagePresent.complete(DeserializingMessage.getCurrent() == message);
                stream.readAllBytes();
            }
        }).whenExecuting(fc -> {
            long now = System.currentTimeMillis();
            SerializedMessage firstChunk = new SerializedMessage(
                    new Data<>("hello ".getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "false", HasMetadata.FIRST_CHUNK, "true"),
                    fc.identityProvider().nextTechnicalId(), now);
            firstChunk.setSegment(0);
            SerializedMessage secondChunk = new SerializedMessage(
                    new Data<>("world".getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FINAL_CHUNK, "true", HasMetadata.FIRST_CHUNK, "false"),
                    firstChunk.getMessageId(), now + 1);
            secondChunk.setSegment(firstChunk.getSegment());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> {
            assertEquals(true, trackerPresent.get(1, TimeUnit.SECONDS));
            assertEquals(true, fluxzeroPresent.get(1, TimeUnit.SECONDS));
            assertEquals(true, currentMessagePresent.get(1, TimeUnit.SECONDS));
        });
    }

    private record LargePayload(String value, int count) {
    }
}
