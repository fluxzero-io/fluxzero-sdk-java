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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GivenWhenThenAssertionErrorTest {

    @Test
    void keepsMessageCompactWithoutMavenProperty() {
        withMavenProperty(null, () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match", List.of("expected"), List.of("actual"));

            assertEquals("Published messages did not match", error.getMessage());
        });
    }

    @Test
    void includesTraceWithoutMavenProperty() {
        withMavenProperty(null, () -> withTrace("Test trace:\n\nwhen\n- COMMAND Example\n", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match", List.of("expected"), List.of("actual"));

            assertEquals("Published messages did not match\n\nTest trace:\n\nwhen\n- COMMAND Example\n",
                         error.getMessage());
        }));
    }

    @Test
    void includesExpectedAndActualInMessageWithMavenProperty() {
        withMavenProperty("true", () -> withSunJavaCommand("org.apache.maven.surefire.booter.ForkedBooter", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match", List.of("expected"), List.of("actual"));

            assertTrue(error.getMessage().contains("expected:"));
            assertTrue(error.getMessage().contains("expected"));
            assertFalse(error.getMessage().contains("Expected:"));
            assertFalse(error.getMessage().contains("[0]"));
            assertTrue(error.getMessage().contains("actual:"));
            assertTrue(error.getMessage().contains("actual"));
            assertFalse(error.getMessage().contains("Actual:"));
        }));
    }

    @Test
    void detectsManifestOnlySurefireBooterCommand() {
        withMavenProperty("true", () -> withSunJavaCommand("/tmp/surefirebooter-123.jar", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match", List.of("expected"), List.of("actual"));

            assertTrue(error.getMessage().contains("expected:"));
            assertTrue(error.getMessage().contains("actual:"));
        }));
    }

    @Test
    void keepsMessageCompactInDirectIntellijRunEvenWithMavenProperty() {
        withMavenProperty("true", () -> withSunJavaCommand("com.intellij.rt.junit.JUnitStarter", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match", List.of("expected"), List.of("actual"));

            assertEquals("Published messages did not match", error.getMessage());
        }));
    }

    @Test
    void movesTraceToSuppressedExceptionInDirectIntellijRun() {
        withSunJavaCommand("com.intellij.rt.junit.JUnitStarter",
                           () -> withTrace("Test trace:\n\nwhen\n- COMMAND Example\n", () -> {
                               GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                                       "Published messages did not match", List.of("expected"), List.of("actual"));

                               assertEquals("Published messages did not match", error.getMessage());
                               assertEquals(1, error.getSuppressed().length);
                               assertEquals("\n\nwhen\n- COMMAND Example\n", error.getSuppressed()[0].getMessage());
                               assertFalse(error.getSuppressed()[0].getMessage().contains("Test trace:"));
                           }));
    }

    @Test
    void rendersOnlyPayloadsWhenMessageMetadataIsNotPartOfExpectation() {
        withMavenProperty("true", () -> withSunJavaCommand("org.apache.maven.surefire.booter.ForkedBooter", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match",
                    List.of(new Message(new ExpectedPayload())),
                    List.of(new Message(new ActualPayload(), Metadata.of("ignored", "actual"))));

            assertTrue(error.getMessage().contains(ExpectedPayload.class.getName()));
            assertTrue(error.getMessage().contains(ActualPayload.class.getName()));
            assertTrue(error.getMessage().contains("expected:\n{"));
            assertTrue(error.getMessage().contains("actual:\n{"));
            assertFalse(error.getMessage().contains("[0]"));
            assertFalse(error.getMessage().contains("\"payload\""));
            assertFalse(error.getMessage().contains("\"metadata\""));
            assertFalse(error.getMessage().contains("\"payloadType\""));
        }));
    }

    @Test
    void rendersMessageEnvelopeWhenExpectedMetadataDiffers() {
        withMavenProperty("true", () -> withSunJavaCommand("org.apache.maven.surefire.booter.ForkedBooter", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match",
                    List.of(new Message(new ExpectedPayload(), Metadata.of("tenant", "expected"))),
                    List.of(new Message(new ExpectedPayload(), Metadata.of("tenant", "actual"))));

            assertTrue(error.getMessage().contains("\"payload\""));
            assertTrue(error.getMessage().contains("\"metadata\""));
            assertTrue(error.getMessage().contains("\"tenant\" : \"expected\""));
            assertTrue(error.getMessage().contains("\"tenant\" : \"actual\""));
            assertFalse(error.getMessage().contains("\"payloadType\""));
        }));
    }

    @Test
    void rendersMultipleMessagesWithoutBlankLinesBetweenItems() {
        withMavenProperty("true", () -> withSunJavaCommand("org.apache.maven.surefire.booter.ForkedBooter", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match",
                    List.of(new Message(new ExpectedPayload())),
                    List.of(new Message(new ExpectedPayload()), new Message(new ActualPayload())));

            assertTrue(error.getMessage().contains("}\n{"));
            assertFalse(error.getMessage().contains("}\n\n{"));
        }));
    }

    @Test
    void snapshotsExpectedAndActualAtConstruction() {
        List<String> actual = new ArrayList<>(List.of("actual"));
        GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                "Published messages did not match", List.of("expected"), actual);

        actual.add("late");

        assertEquals(List.of("actual"), error.getActual().getValue());
    }

    private static void withMavenProperty(String value, Runnable action) {
        String previous = System.getProperty("fluxzero.maven.enabled");
        try {
            if (value == null) {
                System.clearProperty("fluxzero.maven.enabled");
            } else {
                System.setProperty("fluxzero.maven.enabled", value);
            }
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty("fluxzero.maven.enabled");
            } else {
                System.setProperty("fluxzero.maven.enabled", previous);
            }
        }
    }

    private static void withSunJavaCommand(String value, Runnable action) {
        try {
            GivenWhenThenAssertionError.useJavaCommand(value);
            action.run();
        } finally {
            GivenWhenThenAssertionError.useJavaCommand(null);
        }
    }

    private static void withTrace(String trace, Runnable action) {
        try {
            GivenWhenThenAssertionError.useTrace(() -> trace);
            action.run();
        } finally {
            GivenWhenThenAssertionError.clearTrace();
        }
    }

    private record ExpectedPayload() {
    }

    private record ActualPayload() {
    }
}
