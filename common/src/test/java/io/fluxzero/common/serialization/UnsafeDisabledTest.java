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

package io.fluxzero.common.serialization;

import io.fluxzero.common.api.modeling.ModelStreamBatchDecoder;
import io.fluxzero.common.search.DefaultDocumentSerializer;
import io.fluxzero.common.search.Document;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UnsafeDisabledTest {
    @Test
    void productionCodecsWorkWithoutUpstreamInitializationOrUnsafe(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("process.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--sun-misc-unsafe-memory-access=deny", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Codec process did not exit");
            String log = Files.readString(output);
            assertEquals(0, process.exitValue(), log);
            assertFalse(log.contains("sun.misc.Unsafe"), log);
            assertTrue(log.contains("safe-codecs-ok"), log);
        } finally {
            process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (System.getProperty("msgpack.universal-buffer") != null) throw new AssertionError("Unexpected property");
            var document = Document.builder().id("id").type("type").collection("collection")
                    .entries(Map.of(new Document.Entry(Document.EntryType.TEXT, "é-value"),
                            List.of(new Document.Path("path")))).build();
            var encoded = DefaultDocumentSerializer.INSTANCE.serialize(document);
            if (!document.getEntries().equals(DefaultDocumentSerializer.INSTANCE.deserialize(encoded))) {
                throw new AssertionError("Document mismatch");
            }
            byte[] raw = CompressionAlgorithm.ZSTD.decompress(encoded.getValue());
            byte[] legacy = CompressionAlgorithm.LZ4.compress(raw);
            if (!java.util.Arrays.equals(raw, CompressionAlgorithm.LZ4.decompress(legacy))) {
                throw new AssertionError("LZ4 mismatch");
            }
            try (var writer = new MessagePackIO.Writer()) {
                writer.packInt(7).packArrayHeader(1).packLong(1).packLong(2).packLong(3).packBoolean(true)
                        .packString("type").packString("id").packLong(0).packLong(0).packLong(0)
                        .packString("commit").packLong(0).packBoolean(true).packLong(4).packNil();
                if (ModelStreamBatchDecoder.decode(writer.toByteArray()).size() != 1) {
                    throw new AssertionError("Model stream mismatch");
                }
            }
            System.out.println("safe-codecs-ok");
        }
    }
}
