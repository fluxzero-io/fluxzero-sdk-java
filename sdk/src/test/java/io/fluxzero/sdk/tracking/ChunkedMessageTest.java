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
import io.fluxzero.common.TestTask;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkedMessageTest {

    @Test
    void startsHandlingChunkedEventOnFirstChunkAndReadsContinuationFromTrackingStream() throws Exception {
        CompletableFuture<String> firstBytesRead = new CompletableFuture<>();
        CompletableFuture<String> completed = new CompletableFuture<>();
        CompletableFuture<Boolean> fluxzeroPresent = new CompletableFuture<>();
        CompletableFuture<Boolean> currentMessagePresent = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(DeserializingMessage message) throws Exception {
                assertEquals(MessageType.EVENT, Tracker.current().orElseThrow().getMessageType());
                fluxzeroPresent.complete(Fluxzero.getOptionally().isPresent());
                currentMessagePresent.complete(DeserializingMessage.getCurrent() == message);
                InputStream stream = message.getPayloadAs(InputStream.class);
                byte[] firstPart = stream.readNBytes(6);
                firstBytesRead.complete(new String(firstPart, StandardCharsets.UTF_8));
                completed.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }).spy().whenExecuting(fc -> {
            SerializedMessage firstChunk = chunk(fc, "hello ", null, true, false, 0);
            SerializedMessage secondChunk = chunk(fc, "world", firstChunk.getMessageId(), false, true, 1);
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            assertEquals("hello ", firstBytesRead.get(1, TimeUnit.SECONDS));
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> {
            assertTrue(fluxzeroPresent.orTimeout(1, TimeUnit.SECONDS).join());
            assertTrue(currentMessagePresent.orTimeout(1, TimeUnit.SECONDS).join());
            assertEquals("world", completed.orTimeout(1, TimeUnit.SECONDS).join());
        });
    }

    @Test
    void deserializesChunkedJsonPayloadIntoSingleObjectAfterFinalChunk() {
        LargePayload expected = new LargePayload("hello world", 42);
        CompletableFuture<LargePayload> handled = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(LargePayload payload) {
                handled.complete(payload);
            }
        }).whenExecuting(fc -> {
            Data<byte[]> serialized = fc.serializer().serialize(expected);
            int split = serialized.getValue().length / 2;
            SerializedMessage firstChunk = chunk(fc, serialized, 0, split, null, true, false, 0);
            SerializedMessage secondChunk =
                    chunk(fc, serialized, split, serialized.getValue().length, firstChunk.getMessageId(), false, true,
                          1);
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            assertFalse(handled.isDone());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> assertEquals(expected, handled.orTimeout(1, TimeUnit.SECONDS).join()));
    }

    @Test
    void typedChunkedHandlerSelectionDoesNotBlockTrackerBeforeFinalChunk() {
        LargePayload expected = new LargePayload("hello world", 42);
        CompletableFuture<LargePayload> handled = new CompletableFuture<>();
        CompletableFuture<String> pingHandled = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(LargePayload payload) {
                handled.complete(payload);
            }

            @HandleEvent
            void handle(String payload) {
                pingHandled.complete(payload);
            }
        }).whenExecuting(fc -> {
            Data<byte[]> serialized = fc.serializer().serialize(expected);
            int split = serialized.getValue().length / 2;
            SerializedMessage firstChunk = chunk(fc, serialized, 0, split, null, true, false, 0);
            SerializedMessage secondChunk =
                    chunk(fc, serialized, split, serialized.getValue().length, firstChunk.getMessageId(), false, true,
                          1);
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, firstChunk).get();
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, message(fc, "ping")).get();

            assertEquals("ping", pingHandled.get(1, TimeUnit.SECONDS));
            assertFalse(handled.isDone());
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, secondChunk).get();
        }).expectThat(fz -> assertEquals(expected, handled.orTimeout(1, TimeUnit.SECONDS).join()));
    }

    @Test
    void skipsChunkWhenFirstChunkWasNotObserved() {
        CompletableFuture<String> handled = new CompletableFuture<>();
        CompletableFuture<Void> followingMessageHandled = new CompletableFuture<>();
        TestFixture.createAsync(new Object() {
            @HandleEvent
            void handle(DeserializingMessage message) throws Exception {
                if (message.getMetadata().containsKey("testChunkBarrier")) {
                    followingMessageHandled.complete(null);
                    return;
                }
                InputStream stream = message.getPayloadAs(InputStream.class);
                handled.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }).spy().whenExecuting(fc -> {
                    SerializedMessage barrier = message(fc, "after orphan");
                    barrier.setMetadata(barrier.getMetadata().with("testChunkBarrier", true));
                    fc.client().getGatewayClient(MessageType.EVENT)
                            .append(Guarantee.SENT, chunk(fc, "orphan", null, false, true, 1), barrier).get();
                    // Raw transport writes are not fixture dispatches. Await actual processing, not tracker startup.
                    followingMessageHandled.get(1, TimeUnit.SECONDS);
                })
                .expectThat(fc -> {
                    verify(fc.client().getTrackingClient(MessageType.EVENT), timeout(100))
                            .storePosition(any(), any(), anyLong());
                    assertFalse(handled.isDone());
                });
    }

    @Test
    void buffersOutOfSequenceObservedContinuationUntilMissingChunkArrives() {
        TestFixture.create().whenApplying(fc -> {
            SerializedMessage firstChunk = chunk(fc, "hello ", null, true, false, 0);
            firstChunk.setIndex(0L);
            SerializedMessage missing = chunk(fc, "middle ", firstChunk.getMessageId(), false, false, 1);
            missing.setIndex(1L);
            SerializedMessage outOfSequence = chunk(fc, "world", firstChunk.getMessageId(), false, true, 2);
            outOfSequence.setIndex(2L);

            ChunkedDeserializingMessage message =
                    new ChunkedDeserializingMessage(firstChunk, MessageType.EVENT, null, fc.serializer());

            message.appendObservedContinuation(outOfSequence);
            assertFalse(message.completion().isDone());
            message.appendObservedContinuation(missing);
            InputStream stream = message.getPayloadAs(InputStream.class);
            assertEquals("hello middle world",
                         new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return null;
        });
    }

    @Test
    void observedContinuationCompletesWithoutReadingMessageIndex() throws Exception {
        TestFixture.create().whenApplying(fc -> {
            SerializedMessage firstChunk = chunk(fc, "hello ", null, true, false, 0);
            firstChunk.setIndex(0L);
            SerializedMessage secondChunk = chunk(fc, "world", firstChunk.getMessageId(), false, true, 1);
            secondChunk.setIndex(1L);

            ChunkedDeserializingMessage message =
                    new ChunkedDeserializingMessage(firstChunk, MessageType.EVENT, null, fc.serializer());
            message.appendObservedContinuation(secondChunk);

            InputStream stream = message.getPayloadAs(InputStream.class);
            assertEquals("hello world", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return null;
        });
    }

    @Test
    void resolvesRegisteredSimpleTypeBeforeChunkedHandlerSelection() {
        TestFixture.create().whenApplying(fc -> {
            Data<byte[]> data = fc.serializer().serialize(new LargePayload("hello", 42))
                    .withType("LargePayload");
            SerializedMessage firstChunk = chunk(fc, data, 0, data.getValue().length, null, true, true, 0);

            ChunkedDeserializingMessage message =
                    new ChunkedDeserializingMessage(firstChunk, MessageType.EVENT, null, fc.serializer());

            assertEquals(LargePayload.class, message.getPayloadClass());
            assertEquals(new LargePayload("hello", 42), message.getPayload());
            return null;
        });
    }

    @Test
    void recoversFirstChunkFromPagedEarlierRangeWhenContinuationArrivesAfterRestart() throws Exception {
        TestFixture.create().whenApplying(fc -> {
            long timestamp = 1_764_000_000_000L;
            long timestampIndex = IndexUtils.indexFromMillis(timestamp);
            SerializedMessage noise1 = message(fc, "noise-1");
            noise1.setIndex(timestampIndex + 1L);
            noise1.setTimestamp(timestamp);
            SerializedMessage noise2 = message(fc, "noise-2");
            noise2.setIndex(timestampIndex + 2L);
            noise2.setTimestamp(timestamp);
            SerializedMessage firstChunk = chunk(fc, "hello ", null, true, false, 0);
            firstChunk.setIndex(timestampIndex + 3L);
            firstChunk.setTimestamp(timestamp);
            SerializedMessage otherFirstChunk = chunk(fc, "ignored ", null, true, false, 0);
            otherFirstChunk.setIndex(timestampIndex + 4L);
            otherFirstChunk.setTimestamp(timestamp);
            SerializedMessage middleChunk = chunk(fc, "middle ", firstChunk.getMessageId(), false, false, 1);
            middleChunk.setIndex(timestampIndex + 5L);
            middleChunk.setTimestamp(timestamp);
            SerializedMessage finalChunk = chunk(fc, "world", firstChunk.getMessageId(), false, true, 2);
            finalChunk.setIndex(timestampIndex + 10L);
            finalChunk.setTimestamp(timestamp);
            TrackingClient trackingClient = mock(TrackingClient.class);
            when(trackingClient.readRange(timestampIndex, finalChunk.getIndex(), 2))
                    .thenReturn(List.of(noise1, noise2));
            when(trackingClient.readRange(timestampIndex + 3L, finalChunk.getIndex(), 2))
                    .thenReturn(List.of(firstChunk, otherFirstChunk));
            when(trackingClient.readRange(timestampIndex + 5L, finalChunk.getIndex(), 2))
                    .thenReturn(List.of(middleChunk));
            DefaultTracking tracking = new DefaultTracking(
                    MessageType.EVENT, mock(ResultGateway.class), List.of(), List.of(), fc.serializer(),
                    mock(HandlerFactory.class));

            List<DeserializingMessage> messages = tracking.deserializeMessageList(
                    List.of(finalChunk), null, trackingClient, new ConcurrentHashMap<>(), 2);

            assertEquals(1, messages.size());
            InputStream stream = messages.getFirst().getPayloadAs(InputStream.class);
            assertEquals("hello middle world", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            verify(trackingClient).readRange(eq(timestampIndex), eq(finalChunk.getIndex()), eq(2));
            verify(trackingClient).readRange(eq(timestampIndex + 3L), eq(finalChunk.getIndex()), eq(2));
            verify(trackingClient).readRange(eq(timestampIndex + 5L), eq(finalChunk.getIndex()), eq(2));
            verify(trackingClient, never()).readRange(eq(timestampIndex + 6L), eq(finalChunk.getIndex()), eq(2));
            return null;
        });
    }

    @Test
    void failsStreamReadWhenFinalChunkDoesNotArriveBeforeRequestTimeout() {
        TestFixture.create().whenApplying(fc -> {
            SerializedMessage firstChunk = chunk(fc, "hello ", null, true, false, 0);
            firstChunk.setIndex(0L);
            firstChunk.setMetadata(firstChunk.getMetadata().with(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY, "50"));

            ChunkedDeserializingMessage message =
                    new ChunkedDeserializingMessage(firstChunk, MessageType.EVENT, null, fc.serializer());

            InputStream stream = message.getPayloadAs(InputStream.class);
            assertEquals("hello ", new String(stream.readNBytes(6), StandardCharsets.UTF_8));
            assertThrows(IOException.class, stream::read);
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingReleasesIncompleteStreamEvenWhenTrackerAwaitsHandler(boolean awaitAsyncResults) throws Exception {
        CompletableFuture<ChunkedDeserializingMessage> started = new CompletableFuture<>();
        CompletableFuture<IOException> failed = new CompletableFuture<>();
        var fixture = TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(
                MessageType.EVENT, c -> c.toBuilder().awaitAsyncResults(awaitAsyncResults).threads(2).build()),
                new Object() {
                    @HandleEvent
                    void handle(DeserializingMessage message) throws Exception {
                        InputStream stream = message.getPayloadAs(InputStream.class);
                        assertEquals("hello ", new String(stream.readNBytes(6), StandardCharsets.UTF_8));
                        started.complete((ChunkedDeserializingMessage) message);
                        failed.complete(assertThrows(IOException.class, stream::read));
                    }
                });
        fixture.whenExecuting(fc -> {
            SerializedMessage first = chunk(fc, "hello ", null, true, false, 0);
            fc.client().getGatewayClient(MessageType.EVENT).append(Guarantee.SENT, first).get();
            var message = started.get(5, TimeUnit.SECONDS);
            var tracking = fc.tracking(MessageType.EVENT);
            message.completion().whenComplete((ignored, error) -> tracking.close());
            try (var close = new TestTask(tracking::close, () -> message.fail(new IllegalStateException("test cleanup")))) {
                close.awaitCompletion(Duration.ofSeconds(5));
            }
            assertTrue(failed.get(5, TimeUnit.SECONDS).getCause().getMessage().contains("Tracking closed"));
            assertTrue(message.completion().isCompletedExceptionally());
            tracking.close();
        }).expectSuccessfulResult();
    }

    @Test
    void closeReleasesTypedPayloadAndPreservesCompleteBodies() {
        TestFixture.create().whenExecuting(fc -> {
            DefaultTracking tracking = new DefaultTracking(MessageType.EVENT, mock(ResultGateway.class),
                    List.of(), List.of(), fc.serializer(), mock(HandlerFactory.class));
            var active = new ConcurrentHashMap<String, ChunkedDeserializingMessage>();
            var first = chunk(fc, "hello ", null, true, false, 0);
            var completeFirst = chunk(fc, "complete ", null, true, false, 0);
            var completeFinal = chunk(fc, "body", completeFirst.getMessageId(), false, true, 1);
            var messages = tracking.deserializeMessageList(List.of(first, completeFirst, completeFinal), null,
                    mock(TrackingClient.class), active, 10);
            var incomplete = (ChunkedDeserializingMessage) messages.getFirst();
            var complete = (ChunkedDeserializingMessage) messages.getLast();
            try (var reader = new TestTask(
                    () -> assertThrows(CompletionException.class, incomplete::getAggregatedPayloadBytes),
                    () -> incomplete.fail(new IllegalStateException("test cleanup")))) {
                reader.awaitBlockedIn(ChunkedDeserializingMessage.class, "awaitCompletion", Duration.ofSeconds(5));
                tracking.close();
                reader.awaitCompletion(Duration.ofSeconds(5));
            }
            complete.fail(new IllegalStateException("late failure"));
            assertEquals("complete body", new String(complete.getAggregatedPayloadBytes(), StandardCharsets.UTF_8));
            assertEquals("complete body", new String(complete.<InputStream>getPayloadAs(InputStream.class).readAllBytes(),
                    StandardCharsets.UTF_8));
            var finalChunk = chunk(fc, "late final", first.getMessageId(), false, true, 1);
            first.setIndex(0L);
            finalChunk.setIndex(1L);
            var recoveryClient = mock(TrackingClient.class);
            when(recoveryClient.readRange(anyLong(), anyLong(), eq(10))).thenReturn(List.of(first));
            var lateContinuation = tracking.deserializeMessageList(List.of(finalChunk), null,
                    recoveryClient, active, 10);
            assertTrue(lateContinuation.isEmpty(), "A cancelled stream must not be recovered and handled again");
            verify(recoveryClient, never()).readRange(anyLong(), anyLong(), eq(10));
            assertTrue(active.isEmpty());
            // A batch that was already entering deserialization when close began must not escape cleanup.
            var late = (ChunkedDeserializingMessage) tracking.deserializeMessageList(
                    List.of(chunk(fc, "late", null, true, false, 0)), null,
                    mock(TrackingClient.class), active, 10).getFirst();
            assertTrue(late.completion().isCompletedExceptionally());
            assertTrue(active.values().stream().allMatch(m -> m.completion().isCompletedExceptionally()));
        }).expectSuccessfulResult();
    }

    private static SerializedMessage chunk(Fluxzero fluxzero, String payload, String messageId, boolean first,
                                           boolean last, long chunkIndex) {
        return chunk(fluxzero, new Data<>(payload.getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                     0, payload.getBytes(StandardCharsets.UTF_8).length, messageId, first, last, chunkIndex);
    }

    private static SerializedMessage chunk(Fluxzero fluxzero, Data<byte[]> data, int start, int end, String messageId,
                                           boolean first, boolean last, long chunkIndex) {
        SerializedMessage message = new SerializedMessage(
                new Data<>(Arrays.copyOfRange(data.getValue(), start, end), data.getType(), data.getRevision(),
                           data.getFormat()),
                Metadata.of(
                        HasMetadata.FIRST_CHUNK, Boolean.toString(first),
                        HasMetadata.FINAL_CHUNK, Boolean.toString(last),
                        HasMetadata.CHUNK_INDEX, Long.toString(chunkIndex)),
                messageId == null ? fluxzero.identityProvider().nextTechnicalId() : messageId,
                System.currentTimeMillis());
        message.setSegment(0);
        return message;
    }

    private static SerializedMessage message(Fluxzero fluxzero, Object payload) {
        SerializedMessage message = new SerializedMessage(
                fluxzero.serializer().serialize(payload), Metadata.empty(), fluxzero.identityProvider().nextTechnicalId(),
                System.currentTimeMillis());
        message.setSegment(0);
        return message;
    }

    @RegisterType
    private record LargePayload(String value, int count) {
    }
}
