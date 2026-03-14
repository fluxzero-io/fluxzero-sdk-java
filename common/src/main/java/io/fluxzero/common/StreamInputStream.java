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

package io.fluxzero.common;

import lombok.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Exposes the mapped contents of a {@link Stream} as an {@link InputStream} without buffering the full stream in
 * memory.
 */
public class StreamInputStream<T> extends InputStream {
    private final Stream<? extends T> stream;
    private final Iterator<? extends T> iterator;
    private final ThrowingBiConsumer<? super T, ? super OutputStream> writer;

    private byte[] currentChunk = new byte[0];
    private int currentIndex;
    private boolean closed;

    /**
     * Constructs a {@code StreamInputStream} instance that maps the elements of the given {@link Stream}
     * to an {@link InputStream} representation using a provided writer function.
     *
     * @param stream the stream containing the elements to be exposed as an {@link InputStream}; must not be null
     * @param writer a {@code ThrowingBiConsumer} that writes each element of the stream to an {@link OutputStream};
     *               must not be null
     */
    public StreamInputStream(@NonNull Stream<? extends T> stream,
                             @NonNull ThrowingBiConsumer<? super T, ? super OutputStream> writer) {
        this.stream = stream;
        this.iterator = stream.iterator();
        this.writer = writer;
    }

    @Override
    public int read() throws IOException {
        if (!ensureChunk()) {
            return -1;
        }
        return currentChunk[currentIndex++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("Target buffer is null");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (!ensureChunk()) {
            return -1;
        }
        int totalRead = 0;
        while (len > 0 && ensureChunk()) {
            int copied = Math.min(len, currentChunk.length - currentIndex);
            System.arraycopy(currentChunk, currentIndex, b, off, copied);
            currentIndex += copied;
            off += copied;
            len -= copied;
            totalRead += copied;
        }
        return totalRead;
    }

    @Override
    public int available() {
        return currentChunk.length - currentIndex;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            currentChunk = new byte[0];
            currentIndex = 0;
            stream.close();
        }
    }

    private boolean ensureChunk() throws IOException {
        while (!closed && currentIndex >= currentChunk.length) {
            if (!iterator.hasNext()) {
                close();
                return false;
            }
            currentChunk = nextChunk(iterator.next());
            currentIndex = 0;
        }
        return !closed;
    }

    private byte[] nextChunk(T value) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            writer.accept(value, outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to write stream element", e);
        }
    }
}
