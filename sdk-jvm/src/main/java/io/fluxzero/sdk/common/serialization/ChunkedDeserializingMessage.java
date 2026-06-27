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

package io.fluxzero.sdk.common.serialization;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.fluxzero.common.ObjectUtils.join;

/**
 * Deserializing view for a message whose payload is split across multiple indexed messages.
 * <p>
 * The first chunk can be handled immediately as an {@link InputStream}. Typed payload access waits until the final
 * chunk has been observed in the normal tracking stream.
 */
public class ChunkedDeserializingMessage extends DeserializingMessage {
    private final Serializer serializer;
    private final MessageType aggregatedMessageType;
    private final String topic;
    private final SerializedMessage representativeChunk;
    private final StreamingInputStream inputStream;
    private final List<byte[]> chunks = new ArrayList<>();
    private final NavigableMap<Long, SerializedMessage> pendingContinuations = new TreeMap<>();
    private final CompletableFuture<Void> completed = new CompletableFuture<>();

    private volatile SerializedMessage aggregatedSerializedMessage;
    private volatile DeserializingMessage aggregatedMessage;
    private volatile Message streamingMessage;
    private volatile boolean streamingOnly;
    private volatile long expectedChunkIndex;

    /**
     * Creates a chunked message view starting at the first observed chunk.
     *
     * @param firstChunk  the first chunk of the message
     * @param messageType the type of message being reconstructed
     * @param topic       the topic associated with the message, if any
     * @param serializer  the serializer used for typed payload access
     */
    public ChunkedDeserializingMessage(SerializedMessage firstChunk, MessageType messageType, String topic,
                                       Serializer serializer) {
        super(firstChunk, __ -> null, messageType, topic, serializer);
        if (!firstChunk.firstChunk()) {
            throw new IllegalArgumentException("ChunkedDeserializingMessage must start with the first chunk");
        }
        this.serializer = serializer;
        this.aggregatedMessageType = messageType;
        this.topic = topic;
        this.representativeChunk = firstChunk.withMetadata(cleanChunkMetadata(firstChunk.getMetadata()));
        this.inputStream = new StreamingInputStream(requestDeadlineMillis(firstChunk.getMetadata()));
        this.expectedChunkIndex = chunkIndex(firstChunk) + 1L;
        appendChunk(firstChunk);
    }

    /**
     * Reconstructs a chunked message when tracking observes a continuation after the original first chunk was already
     * read by an earlier tracker run.
     *
     * @param continuation      the observed continuation chunk
     * @param messageType       the type of message being reconstructed
     * @param topic             the topic associated with the message, if any
     * @param serializer        the serializer used for typed payload access
     * @param minIndexInclusive the first index to inspect while looking for earlier chunks
     * @param pageSize          the maximum number of messages to request per range read
     * @param chunkReader       callback used to read earlier messages from the tracking stream
     * @return a reconstructed chunked message when the first chunk and required previous continuations were found
     */
    public static Optional<ChunkedDeserializingMessage> recoverFromContinuation(
            SerializedMessage continuation, MessageType messageType, String topic, Serializer serializer,
            long minIndexInclusive, int pageSize, ChunkReader chunkReader) {
        Long continuationChunkIndex = chunkIndexOrNull(continuation);
        if (continuationChunkIndex == null || continuationChunkIndex <= 0L || continuation.getIndex() == null) {
            return Optional.empty();
        }
        List<SerializedMessage> previousChunks = readPreviousChunks(
                continuation, continuationChunkIndex, minIndexInclusive, Math.max(1, pageSize), chunkReader);
        Optional<SerializedMessage> firstChunk = previousChunks.stream().filter(SerializedMessage::firstChunk)
                .findFirst();
        if (firstChunk.isEmpty()) {
            return Optional.empty();
        }
        ChunkedDeserializingMessage result =
                new ChunkedDeserializingMessage(firstChunk.get(), messageType, topic, serializer);
        previousChunks.stream().filter(message -> !message.firstChunk()).forEach(result::appendObservedContinuation);
        return Optional.of(result);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R getPayloadAs(Type type) {
        if (type instanceof Class<?> c && InputStream.class.isAssignableFrom(c)) {
            enableStreamingOnlyMode();
            return (R) inputStream;
        }
        return aggregatedMessage().getPayloadAs(type);
    }

    @Override
    public <V> V getPayload() {
        return aggregatedMessage().getPayload();
    }

    @Override
    public Message toMessage() {
        Message result = streamingMessage;
        if (result == null) {
            Message message = new Message(inputStream, getMetadata(), getMessageId(), getTimestamp());
            result = switch (aggregatedMessageType) {
                case WEBREQUEST -> new WebRequest(message);
                case WEBRESPONSE -> new WebResponse(message);
                default -> message;
            };
            streamingMessage = result;
        }
        return result;
    }

    @Override
    public Metadata getMetadata() {
        return representativeChunk.getMetadata();
    }

    @Override
    public Class<?> getPayloadClass() {
        if (InputStream.class.getName().equals(representativeChunk.getType())) {
            return InputStream.class;
        }
        return JvmComponentIntrospector.getInstance().classForName(serializer.upcastType(representativeChunk.getType()), Void.class);
    }

    @Override
    public SerializedMessage getSerializedObject() {
        return representativeChunk;
    }

    /**
     * Returns a future that completes when the final chunk has been observed.
     */
    public CompletableFuture<Void> completion() {
        return completed;
    }

    /**
     * Returns the fully aggregated payload bytes, waiting for the final chunk when necessary.
     */
    public byte[] getAggregatedPayloadBytes() {
        return aggregatedSerializedMessage().getData().getValue();
    }

    /**
     * Switches this message to streaming-only mode. Subsequent chunks are exposed through the input stream but are no
     * longer retained in heap for full-payload aggregation.
     */
    public synchronized void enableStreamingOnlyMode() {
        streamingOnly = true;
        chunks.clear();
        aggregatedSerializedMessage = null;
        aggregatedMessage = null;
    }

    /**
     * Appends an additional chunk to this message.
     */
    public synchronized void appendChunk(SerializedMessage chunk) {
        aggregatedSerializedMessage = null;
        aggregatedMessage = null;
        byte[] bytes = chunk.getData().getValue();
        if (!streamingOnly) {
            chunks.add(bytes);
        }
        inputStream.append(bytes);
        if (chunk.lastChunk()) {
            inputStream.complete();
            completed.complete(null);
        }
    }

    /**
     * Appends a continuation chunk that was already present in the current tracker batch.
     */
    public synchronized void appendObservedContinuation(SerializedMessage message) {
        if (!Objects.equals(getMessageId(), message.getMessageId())) {
            throw new IllegalArgumentException("Continuation chunk belongs to a different message");
        }
        appendContinuation(message);
    }

    /**
     * Fails the stream and any pending aggregated deserialization with the given error.
     */
    public void fail(Throwable error) {
        inputStream.fail(error);
        completed.completeExceptionally(error);
    }

    protected DeserializingMessage aggregatedMessage() {
        DeserializingMessage result = aggregatedMessage;
        if (result == null) {
            result = serializer.deserializeMessages(
                            java.util.stream.Stream.of(aggregatedSerializedMessage()), aggregatedMessageType, topic)
                    .findAny().orElseThrow();
            aggregatedMessage = result;
        }
        return result;
    }

    protected SerializedMessage aggregatedSerializedMessage() {
        if (streamingOnly) {
            throw new IllegalStateException("Chunked payload aggregation is disabled for this streaming-only message");
        }
        awaitCompletion();
        SerializedMessage result = aggregatedSerializedMessage;
        if (result == null) {
            byte[] bytes;
            synchronized (this) {
                bytes = join(chunks.toArray(byte[][]::new));
            }
            var first = representativeChunk;
            result = first.withData(new Data<>(
                    bytes, first.getData().getType(), first.getData().getRevision(), first.getData().getFormat()));
            result.setSource(first.getSource());
            result.setTarget(first.getTarget());
            result.setRequestId(first.getRequestId());
            result.setIndex(first.getIndex());
            result.setSegment(first.getSegment());
            aggregatedSerializedMessage = result;
        }
        return result;
    }

    private ChunkProgress appendContinuation(SerializedMessage message) {
        if (message.firstChunk()) {
            throw new IllegalStateException("Observed duplicate first chunk for message " + getMessageId());
        }
        long actualChunkIndex = chunkIndex(message);
        if (actualChunkIndex < expectedChunkIndex) {
            return ChunkProgress.SKIPPED;
        }
        if (actualChunkIndex > expectedChunkIndex) {
            pendingContinuations.putIfAbsent(actualChunkIndex, message);
            return ChunkProgress.GAP;
        }
        appendExpectedContinuation(message);
        while (!completed.isDone() && pendingContinuations.containsKey(expectedChunkIndex)) {
            appendExpectedContinuation(pendingContinuations.remove(expectedChunkIndex));
        }
        return ChunkProgress.APPENDED;
    }

    private void appendExpectedContinuation(SerializedMessage message) {
        expectedChunkIndex++;
        appendChunk(message);
    }

    private void awaitCompletion() {
        long deadline = requestDeadlineMillis(getMetadata());
        if (deadline < 0L) {
            completed.join();
            return;
        }
        long remainingMillis = deadline - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            TimeoutException timeout = timeoutException();
            fail(timeout);
            throw new CompletionException(timeout);
        }
        try {
            completed.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (TimeoutException e) {
            TimeoutException timeout = timeoutException();
            fail(timeout);
            throw new CompletionException(timeout);
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        }
    }

    private TimeoutException timeoutException() {
        return new TimeoutException("Timed out while waiting for final chunk of message " + getMessageId());
    }

    private static long requestDeadlineMillis(Metadata metadata) {
        String timeout = metadata.get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY);
        if (timeout == null) {
            return -1L;
        }
        try {
            return System.currentTimeMillis() + Math.max(1L, Long.parseLong(timeout));
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static Metadata cleanChunkMetadata(Metadata metadata) {
        return metadata.with(
                HasMetadata.FIRST_CHUNK, null,
                HasMetadata.FINAL_CHUNK, null,
                HasMetadata.CHUNK_INDEX, null);
    }

    private static List<SerializedMessage> readPreviousChunks(SerializedMessage continuation,
                                                              long continuationChunkIndex,
                                                              long minIndexInclusive, int pageSize,
                                                              ChunkReader chunkReader) {
        List<SerializedMessage> result = new ArrayList<>();
        long maxIndexExclusive = continuation.getIndex();
        long nextIndex = minIndexInclusive;
        while (nextIndex < maxIndexExclusive) {
            List<SerializedMessage> page = chunkReader.readRange(nextIndex, maxIndexExclusive, pageSize);
            if (page.isEmpty()) {
                break;
            }
            page.stream()
                    .filter(SerializedMessage::chunked)
                    .filter(message -> Objects.equals(message.getMessageId(), continuation.getMessageId()))
                    .filter(message -> {
                        Long messageChunkIndex = chunkIndexOrNull(message);
                        return messageChunkIndex != null && messageChunkIndex < continuationChunkIndex;
                    })
                    .forEach(result::add);
            if (hasRecoveredPreviousChunks(result, continuationChunkIndex)) {
                break;
            }
            Long lastIndex = page.getLast().getIndex();
            if (lastIndex == null || lastIndex < nextIndex) {
                break;
            }
            nextIndex = lastIndex + 1L;
        }
        return result;
    }

    private static boolean hasRecoveredPreviousChunks(List<SerializedMessage> chunks, long continuationChunkIndex) {
        if (chunks.size() < continuationChunkIndex) {
            return false;
        }
        Set<Long> recovered = new HashSet<>();
        for (SerializedMessage chunk : chunks) {
            Long chunkIndex = chunkIndexOrNull(chunk);
            if (chunkIndex != null && chunkIndex >= 0L && chunkIndex < continuationChunkIndex) {
                recovered.add(chunkIndex);
            }
        }
        return recovered.size() == continuationChunkIndex;
    }

    private static long chunkIndex(SerializedMessage message) {
        Long result = chunkIndexOrNull(message);
        if (result == null) {
            throw new IllegalArgumentException("Invalid chunk index for message " + message.getMessageId());
        }
        return result;
    }

    private static Long chunkIndexOrNull(SerializedMessage message) {
        try {
            String value = message.getMetadata().get(HasMetadata.CHUNK_INDEX);
            return value == null ? 0L : Long.valueOf(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Reads serialized messages from a bounded index range while recovering chunked messages.
     */
    @FunctionalInterface
    public interface ChunkReader {
        /**
         * Reads a page of messages from the given index range.
         *
         * @param minIndexInclusive the starting index (inclusive)
         * @param maxIndexExclusive the maximum index (exclusive)
         * @param maxSize           the maximum number of messages to retrieve
         * @return messages in the requested range, ordered by index
         */
        List<SerializedMessage> readRange(long minIndexInclusive, long maxIndexExclusive, int maxSize);
    }

    private enum ChunkProgress {
        APPENDED,
        GAP,
        SKIPPED
    }

    protected class StreamingInputStream extends InputStream {
        private final BlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
        private final long deadlineMillis;
        private byte[] current = new byte[0];
        private int position;

        protected StreamingInputStream(long deadlineMillis) {
            this.deadlineMillis = deadlineMillis;
        }

        public void append(byte[] bytes) {
            queue.add(new Chunk(bytes, null, false));
        }

        public void complete() {
            queue.add(new Chunk(null, null, true));
        }

        public void fail(Throwable error) {
            queue.add(new Chunk(null, error, true));
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read == -1 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (position >= current.length) {
                Chunk chunk;
                try {
                    chunk = takeChunk();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for request chunk", e);
                }
                if (chunk.error() != null) {
                    throw new IOException("Failed to read request stream", chunk.error());
                }
                if (chunk.end()) {
                    current = new byte[0];
                    position = 0;
                    return -1;
                }
                current = chunk.bytes();
                position = 0;
            }
            int read = Math.min(len, current.length - position);
            System.arraycopy(current, position, buffer, off, read);
            position += read;
            return read;
        }

        private Chunk takeChunk() throws InterruptedException, IOException {
            if (deadlineMillis < 0L) {
                return queue.take();
            }
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0L) {
                throw timeout();
            }
            Chunk result = queue.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (result == null) {
                throw timeout();
            }
            return result;
        }

        private IOException timeout() {
            TimeoutException timeout = timeoutException();
            ChunkedDeserializingMessage.this.fail(timeout);
            return new IOException("Timed out while waiting for request chunk", timeout);
        }
    }

    protected record Chunk(byte[] bytes, Throwable error, boolean end) {
    }
}
