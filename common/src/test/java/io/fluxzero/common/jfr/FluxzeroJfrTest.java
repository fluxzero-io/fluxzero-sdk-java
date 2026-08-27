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

package io.fluxzero.common.jfr;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluxzeroJfrTest {

    @TempDir
    Path testDirectory;

    @Test
    void recordsBatches() throws Exception {
        Path recordingFile = testDirectory.resolve("fluxzero.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("io.fluxzero.Batch");
            recording.start();

            FluxzeroJfr.Batch batch = FluxzeroJfr.startBatch(
                    "sdk", "handle", "COMMAND", 64, 1_024L, 3L, 2_048L);
            batch.preparationNanos = 123L;
            FluxzeroJfr.finish(batch, null);
            recording.stop();
            recording.dump(recordingFile);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile);
        RecordedEvent batch = events.stream()
                .filter(event -> event.getEventType().getName().equals("io.fluxzero.Batch"))
                .findFirst().orElseThrow();
        assertEquals("sdk", batch.getString("component"));
        assertEquals(64, batch.getInt("itemCount"));
        assertEquals(123L, batch.getLong("preparationNanos"));
        assertTrue(batch.getDuration().toNanos() >= 0L);
    }
}
