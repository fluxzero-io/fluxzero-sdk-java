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

import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.StringResult;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebsocketRuntimeResultCrossVersionBenchmarkTest {

    @Test
    void keepsTheMeasuredResultCountEqualAcrossBatchSizes() {
        int targetResults = 2_097_152;

        assertEquals(targetResults, measuredResults(targetResults, 0));
        assertEquals(targetResults, measuredResults(targetResults, 1));
        assertEquals(targetResults, measuredResults(targetResults, 32));
        assertEquals(targetResults, measuredResults(targetResults, 128));
        assertEquals(targetResults, measuredResults(targetResults, 1_024));
    }

    @Test
    void requiresAnExactNumberOfCompleteResultMessages() {
        assertEquals(2_048,
                     WebsocketRuntimeResultCrossVersionBenchmark.messageIterations(
                             2_097_152, 1_024));
        assertThrows(IllegalArgumentException.class,
                     () -> WebsocketRuntimeResultCrossVersionBenchmark.messageIterations(1, 0));
        assertThrows(IllegalArgumentException.class,
                     () -> WebsocketRuntimeResultCrossVersionBenchmark.messageIterations(1_025, 1_024));
    }

    @Test
    void derivesWaveCapacityFromBothMessageAndByteBounds() {
        assertEquals(128, WebsocketRuntimeResultCrossVersionBenchmark.messagesPerSession(
                128, 16L << 20, 128 << 10));
        assertEquals(8, WebsocketRuntimeResultCrossVersionBenchmark.messagesPerSession(
                128, 16L << 20, 2 << 20));
        assertEquals(1, WebsocketRuntimeResultCrossVersionBenchmark.messagesPerSession(
                128, 16L << 20, 20 << 20));
    }

    @Test
    void createsDistinctSingletonAndResultBatchPayloads() throws Exception {
        byte[] singletonPayload = WebsocketRuntimeResultCrossVersionBenchmark.createPayload(
                CompressionAlgorithm.LZ4, 16, 0);
        byte[] singleResultBatchPayload = WebsocketRuntimeResultCrossVersionBenchmark.createPayload(
                CompressionAlgorithm.LZ4, 16, 1);
        byte[] largeResultBatchPayload = WebsocketRuntimeResultCrossVersionBenchmark.createPayload(
                CompressionAlgorithm.LZ4, 16, 1_024);

        assertInstanceOf(StringResult.class, decode(singletonPayload));
        assertEquals(1, assertInstanceOf(ResultBatch.class, decode(singleResultBatchPayload)).getResults().size());
        assertEquals(1_024, assertInstanceOf(ResultBatch.class, decode(largeResultBatchPayload)).getResults().size());
    }

    private static int measuredResults(int targetResults, int configuredBatchSize) {
        int resultsPerMessage = Math.max(1, configuredBatchSize);
        int messages = WebsocketRuntimeResultCrossVersionBenchmark.messageIterations(
                targetResults, resultsPerMessage);
        return Math.multiplyExact(messages, resultsPerMessage);
    }

    private static Object decode(byte[] compressedPayload) throws Exception {
        return WebSocketTransportCodecs.json(AbstractWebsocketClient.defaultObjectMapper)
                .decode(CompressionAlgorithm.LZ4.decompress(compressedPayload));
    }
}
