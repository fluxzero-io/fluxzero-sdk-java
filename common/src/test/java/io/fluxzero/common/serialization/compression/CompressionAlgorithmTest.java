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

package io.fluxzero.common.serialization.compression;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.GZIP;
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.NONE;
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.LZ4;
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.ZSTD;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompressionAlgorithmTest {

    @Test
    void safeLz4ReadsHistoricalAndFramedEnvelopes() {
        for (byte[] raw : List.of(new byte[0], "legacy ".repeat(1024).getBytes(StandardCharsets.UTF_8))) {
            byte[] legacy = LZ4.compress(raw);
            byte[] framed = new byte[legacy.length + 3];
            framed[0] = (byte) 0xff;
            framed[2] = 1;
            System.arraycopy(legacy, 0, framed, 3, legacy.length);
            assertArrayEquals(raw, LZ4.decompress(legacy));
            assertArrayEquals(raw, LZ4.decompress(framed));
            assertArrayEquals(raw, ZSTD.decompress(framed));
            byte[] container = new byte[framed.length + 10];
            System.arraycopy(framed, 0, container, 5, framed.length);
            assertArrayEquals(raw, LZ4.decompress(container, 5, framed.length));
        }
    }

    @Test
    void safeLz4RejectsTruncatedPayloadsAndIncorrectSizes() {
        byte[] raw = "legacy ".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] legacy = LZ4.compress(raw);
        for (int length = 0; length < legacy.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(legacy, length);
            assertThrows(RuntimeException.class, () -> LZ4.decompress(truncated));
        }
        for (int size : new int[]{-1, 0, raw.length - 1, raw.length + 1}) {
            byte[] incorrect = legacy.clone();
            java.nio.ByteBuffer.wrap(incorrect).putInt(size);
            assertThrows(RuntimeException.class, () -> LZ4.decompress(incorrect));
        }
        assertThrows(IllegalArgumentException.class, () -> LZ4.decompress(legacy, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> LZ4.decompress(legacy, 1, legacy.length));
    }

    @Test
    void gzipRoundTripsBytes() {
        byte[] bytes = "hello ".repeat(1024).getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(bytes, GZIP.decompress(GZIP.compress(bytes)));
    }

    @Test
    void zstdRoundTripsBytes() {
        byte[] bytes = "hello ".repeat(1024).getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(bytes, ZSTD.decompress(ZSTD.compress(bytes)));
    }

    @Test
    void zstdDecompressesAFramedByteRangeWithoutIncludingAdjacentBytes() {
        byte[] bytes = "hello ".repeat(1024).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = ZSTD.compress(bytes);
        byte[] container = new byte[compressed.length + 7];
        System.arraycopy(compressed, 0, container, 3, compressed.length);

        assertArrayEquals(
                bytes,
                ZSTD.decompress(
                        container,
                        3,
                        compressed.length));
    }

    @Test
    void zstdHandlesConcurrentRoundTripsThroughBoundedPool() throws Exception {
        byte[] bytes = "hello ".repeat(1024).getBytes(StandardCharsets.UTF_8);
        int threadCount = 64;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threadCount)) {
            List<java.util.concurrent.Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return ZSTD.decompress(ZSTD.compress(bytes));
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertArrayEquals(bytes, future.get());
            }
        }
    }

    @Test
    void zstdUsesFluxzeroRuntimeCompressionHeader() {
        byte[] bytes = "hello ".repeat(1024).getBytes(StandardCharsets.UTF_8);

        byte[] compressed = ZSTD.compress(bytes);

        assertEquals((byte) 0xFF, compressed[0]);
        assertEquals(0x00, compressed[1]);
        assertEquals(2, compressed[2]);
        assertEquals(bytes.length, ((compressed[3] & 0xff) << 24)
                                   | ((compressed[4] & 0xff) << 16)
                                   | ((compressed[5] & 0xff) << 8)
                                   | (compressed[6] & 0xff));
    }

    @Test
    void rejectsUnknownCompressionId() {
        byte[] retiredFrame = {(byte) 0xff, 0, 99, 0, 0, 0, 0};
        assertEquals("Unknown Fluxzero compression algorithm id: 99",
                     assertThrows(IllegalArgumentException.class, () -> ZSTD.decompress(retiredFrame)).getMessage());
    }

    @Test
    void zstdReadsRawFramesAndEmptyPayloads() {
        for (byte[] bytes : List.of(new byte[0], "raw frame".getBytes(StandardCharsets.UTF_8))) {
            assertArrayEquals(bytes, ZSTD.decompress(com.github.luben.zstd.Zstd.compress(bytes)));
            assertArrayEquals(bytes, ZSTD.decompress(ZSTD.compress(bytes)));
        }
    }

    @Test
    void noneKeepsOriginalBytes() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        assertSame(bytes, NONE.compress(bytes));
        assertSame(bytes, NONE.decompress(bytes));
    }

    @Test
    void gzipFallsBackToOriginalBytesForPlainInput() {
        byte[] bytes = "not gzip".getBytes(StandardCharsets.UTF_8);

        assertSame(bytes, GZIP.decompress(bytes));
    }
}
