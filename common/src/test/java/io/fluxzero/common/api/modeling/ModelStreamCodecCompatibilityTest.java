/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ModelStreamCodecCompatibilityTest {
    @Test
    void completeVersionSevenStreamsMatchFrozenReaderIncludingCompressedSlices() throws Exception {
        Random random = new Random(3124);
        for (int sample = 0; sample < 512; sample++) {
            byte[] raw;
            try (var w = MessagePack.newDefaultBufferPacker()) {
                boolean shared = sample % 2 == 0;
                w.packInt(7).packArrayHeader(1 + sample % 33).packLong(random.nextLong())
                        .packLong(random.nextLong()).packLong(random.nextLong()).packBoolean(shared);
                if (shared) w.packString("type-é");
                for (int n = 0; n <= sample % 33; n++) {
                    w.packString("model-" + n).packLong(random.nextLong()).packLong(random.nextLong())
                            .packLong(random.nextLong()).packString("commit-" + n).packLong(random.nextLong())
                            .packBoolean(n % 2 == 0).packLong(random.nextLong() >>> 1);
                    if (!shared) w.packString("type-" + n);
                    if (n % 2 == 0) w.packNil(); else w.packString("collection");
                }
                raw = w.toByteArray();
            }
            for (byte[] bytes : List.of(raw, CompressionAlgorithm.ZSTD.compress(raw))) {
                byte[] backing = new byte[bytes.length + 11];
                System.arraycopy(bytes, 0, backing, 5, bytes.length);
                var block = new ModelEventDataBlock(backing, 5, bytes.length);
                var expected = LegacyModelStreamBatchDecoder.decode(block).stream().map(e -> new ModelStreamBatchDecoder.Entry(
                        e.modelId(), e.modelType(), e.stateIndex(), e.readStateIndex(), e.commitId(), e.substep(),
                        e.eventIndex(), e.sequenceNumber(), e.historyComplete(), e.payloadBytes(), e.documentCollection())).toList();
                assertEquals(expected, ModelStreamBatchDecoder.decode(block), "stream seed 3124, sample " + sample);
            }
            if (sample < 8) {
                for (int end = 0; end < raw.length; end++) {
                    byte[] truncated = Arrays.copyOf(raw, end);
                    assertThrows(Exception.class, () -> LegacyModelStreamBatchDecoder.decode(truncated));
                    assertThrows(Exception.class, () -> ModelStreamBatchDecoder.decode(truncated));
                }
            }
        }
    }
}
