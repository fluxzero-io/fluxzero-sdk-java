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
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import static io.fluxzero.common.ObjectUtils.join;

/**
 * Represents a chunked message that can expose its payload as an {@link InputStream} while additional chunks arrive.
 * <p>
 * Handlers can consume the payload as a stream immediately by requesting {@link InputStream}. They can also request a
 * regular payload type such as a POJO; in that case deserialization waits until the final chunk has arrived and then
 * materializes the full payload from the aggregated byte stream.
 */
public class ChunkedDeserializingMessage extends DeserializingMessage {
    private final Serializer serializer;
    private final MessageType aggregatedMessageType;
    private final SerializedMessage representativeChunk;
    private final StreamingInputStream inputStream = new StreamingInputStream();
    private final List<byte[]> chunks = new ArrayList<>();
    private final CompletableFuture<Void> completed = new CompletableFuture<>();

    private volatile SerializedMessage aggregatedSerializedMessage;
    private volatile DeserializingMessage aggregatedMessage;
    private volatile Message streamingMessage;
    private volatile boolean streamingOnly;

    /**
     * Creates a new chunked message view starting at the first observed chunk.
     */
    public ChunkedDeserializingMessage(SerializedMessage firstChunk, MessageType messageType, String topic,
                                       Serializer serializer) {
        super(firstChunk, __ -> null, messageType, topic, serializer);
        this.serializer = serializer;
        this.aggregatedMessageType = messageType;
        this.representativeChunk = firstChunk.withMetadata(firstChunk.getMetadata().with(HasMetadata.FINAL_CHUNK, null));
        appendChunk(firstChunk);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R getPayloadAs(Type type) {
        if (type instanceof Class<?> c && InputStream.class.isAssignableFrom(c)) {
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
        if (completed.isDone()) {
            return super.toMessage();
        }
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
    public Class<?> getPayloadClass() {
        return ReflectionUtils.classForName(serializer.upcastType(representativeChunk.getType()), Void.class);
    }

    @Override
    public SerializedMessage getSerializedObject() {
        return representativeChunk;
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
     * Fails the stream and any pending aggregated deserialization with the given error.
     */
    public void fail(Throwable error) {
        inputStream.fail(error);
        completed.completeExceptionally(error);
    }

    protected DeserializingMessage aggregatedMessage() {
        DeserializingMessage result = aggregatedMessage;
        if (result == null) {
            result = serializer.deserializeMessage(aggregatedSerializedMessage(), aggregatedMessageType);
            aggregatedMessage = result;
        }
        return result;
    }

    protected SerializedMessage aggregatedSerializedMessage() {
        if (streamingOnly) {
            throw new IllegalStateException("Chunked payload aggregation is disabled for this streaming-only message");
        }
        completed.join();
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

    protected static class StreamingInputStream extends InputStream {
        private final BlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int position;

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
                    chunk = queue.take();
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
    }

    protected record Chunk(byte[] bytes, Throwable error, boolean end) {
    }
}
