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
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.LZ4;
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.NONE;
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.ZSTD;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CompressionAlgorithmTest {

    @Test
    void lz4RoundTripsBytes() {
        byte[] bytes = "hello ".repeat(1024).getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(bytes, LZ4.decompress(LZ4.compress(bytes)));
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
    void lz4CanReadFluxzeroRuntimeCompressionHeader() {
        byte[] bytes = "hello ".repeat(1024).getBytes(StandardCharsets.UTF_8);
        byte[] legacyLz4 = LZ4.compress(bytes);
        byte[] runtimeLz4 = new byte[legacyLz4.length + 3];
        runtimeLz4[0] = (byte) 0xFF;
        runtimeLz4[1] = 0x00;
        runtimeLz4[2] = 1;
        System.arraycopy(legacyLz4, 0, runtimeLz4, 3, 4);
        System.arraycopy(legacyLz4, 4, runtimeLz4, 7, legacyLz4.length - 4);

        assertArrayEquals(bytes, LZ4.decompress(runtimeLz4));
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
