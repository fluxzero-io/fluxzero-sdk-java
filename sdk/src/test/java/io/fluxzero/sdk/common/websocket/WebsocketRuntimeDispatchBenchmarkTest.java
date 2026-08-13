/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsocketRuntimeDispatchBenchmarkTest {

    @Test
    void configuredSingleSessionIsNotBenchmarkedTwice() {
        assertEquals(List.of(1), WebsocketRuntimeDispatchBenchmark.loadSessionCounts(1));
    }

    @Test
    void benchmarksSingleAndConfiguredMultiSessionLoad() {
        assertEquals(List.of(1, 4), WebsocketRuntimeDispatchBenchmark.loadSessionCounts(4));
    }

    @Test
    void rejectsInvalidSessionCount() {
        assertThrows(IllegalArgumentException.class,
                     () -> WebsocketRuntimeDispatchBenchmark.loadSessionCounts(0));
    }

    @Test
    void loadWorkerCountTracksConfiguredMessageConcurrency() {
        assertEquals(32, WebsocketRuntimeDispatchBenchmark.loadWorkerCount(4, 8));
        assertThrows(IllegalArgumentException.class,
                     () -> WebsocketRuntimeDispatchBenchmark.loadWorkerCount(4, 0));
        assertThrows(ArithmeticException.class,
                     () -> WebsocketRuntimeDispatchBenchmark.loadWorkerCount(Integer.MAX_VALUE, 2));
    }

    @Test
    void smallLoadPayloadsAreDeterministicDecodableAndIndependentlyOwned() throws Exception {
        for (int valueBytes : List.of(16, 320)) {
            for (CompressionAlgorithm compression : List.of(CompressionAlgorithm.LZ4, CompressionAlgorithm.ZSTD)) {
                byte[] first = WebsocketRuntimeDispatchBenchmark.compressedLoadPayload(compression, valueBytes);
                byte[] second = WebsocketRuntimeDispatchBenchmark.compressedLoadPayload(compression, valueBytes);

                assertArrayEquals(first, second);
                assertNotSame(first, second);
                assertTrue(first.length < 512,
                           () -> "%s payload with %d value bytes compressed to %d bytes"
                                   .formatted(compression, valueBytes, first.length));
                StringResult decoded = assertInstanceOf(
                        StringResult.class,
                        WebSocketTransportCodecs.json(AbstractWebsocketClient.defaultObjectMapper)
                                .decode(compression.decompress(first)));
                assertEquals(valueBytes, decoded.getResult().length());

                first[0] ^= 1;
                assertArrayEquals(second,
                                  WebsocketRuntimeDispatchBenchmark.compressedLoadPayload(compression, valueBytes));
            }
        }
    }
}
