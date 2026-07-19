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

package io.fluxzero.sdk.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFixtureObservationRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void reset() {
        System.clearProperty(TestFixtureObservationRecorder.ENABLED_PROPERTY);
        System.clearProperty(TestFixtureObservationRecorder.DIRECTORY_PROPERTY);
        TestFixtureObservationRecorder.resetForTests();
    }

    @Test
    void writesImpactIndexForObservedTest(@TempDir Path directory) throws Exception {
        System.setProperty(TestFixtureObservationRecorder.ENABLED_PROPERTY, "true");
        System.setProperty(TestFixtureObservationRecorder.DIRECTORY_PROPERTY, directory.toString());

        TestFixtureObservationRecorder.testStarted("com.acme.OrderHandlerTest#createsOrder");
        TestFixtureObservationRecorder.record(null, new TestFixtureObservation(
                null, TestFixtureObservation.Kind.HANDLING,
                "com.acme.OrderHandler", "handle",
                "com.acme.CreateOrder", "COMMAND", null, null,
                null, null, null, null, 1L));
        TestFixtureObservationRecorder.testFinished("com.acme.OrderHandlerTest#createsOrder");

        Path testImpactFile = directory.resolve(TestFixtureObservationRecorder.TEST_IMPACT_FILE);
        JsonNode testImpact = objectMapper.readTree(testImpactFile.toFile())
                .path("tests").path("com.acme.OrderHandlerTest#createsOrder");
        assertTrue(testImpact.path("handlers").toString().contains("com.acme.OrderHandler#handle"));
        assertTrue(testImpact.path("payloads").toString().contains("com.acme.CreateOrder"));
        assertEquals("COMMAND", testImpact.path("messages").get(0).path("type").asText());
        Files.deleteIfExists(testImpactFile);
    }
}
