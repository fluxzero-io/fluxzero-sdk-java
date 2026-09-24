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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelEventPageDecoderTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parallelBlocksRetainPayloadOrderAndExactValues(boolean compressed) {
        GetModelEventsResult result = ModelEventPageDecoder.expand(request(), page(compressed, false));
        assertEquals(16, result.getPayloads().size());
        for (int i = 0; i < 16; i++) {
            ModelEventPayload payload = result.getPayloads().get(i);
            assertEquals(i, payload.getStateIndex());
            assertEquals("message-" + i, payload.getEvent().getMessageId());
            assertEquals(i, payload.getEvent().getData().getValue()[0]);
        }
    }

    @Test
    void malformedBackgroundBlockRetainsDecodingFailure() {
        assertThrows(IllegalArgumentException.class,
                     () -> ModelEventPageDecoder.expand(request(), page(false, true)));
    }

    private GetModelEvents request() {
        return new GetModelEvents(List.of(new ModelEventStreamRequest("model", -1L, 16)),
                                  ModelReadBoundary.at(null), 1024L);
    }

    private GetModelEventsResult page(boolean compressed, boolean malformedLastBlock) {
        List<ModelEventPayloadBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            SerializedMessage message = new SerializedMessage(new Data<>(new byte[]{(byte) i}, "event", 0),
                                                              Metadata.empty(), "message-" + i, 1L);
            message.setIndex((long) i);
            byte[] bytes = BinaryWire.encodeEnvelope(message);
            if (compressed) {
                bytes = CompressionAlgorithm.ZSTD.compress(bytes);
            }
            if (malformedLastBlock && i == 15) {
                bytes = new byte[]{0};
            }
            blocks.add(new ModelEventPayloadBlock(i, 1, compressed, bytes));
        }
        long[] indices = LongStream.range(0, 16).toArray();
        return new GetModelEventsResult(1L, 15L, true, List.of(), List.of(), indices, blocks, indices, List.of());
    }
}
