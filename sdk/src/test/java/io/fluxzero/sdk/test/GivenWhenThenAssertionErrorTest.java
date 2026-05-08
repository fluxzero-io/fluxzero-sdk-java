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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void includesExpectedAndActualInMessageWithMavenProperty() {
        withMavenProperty("true", () -> {
            GivenWhenThenAssertionError error = new GivenWhenThenAssertionError(
                    "Published messages did not match", List.of("expected"), List.of("actual"));

            assertTrue(error.getMessage().contains("Expected:"));
            assertTrue(error.getMessage().contains("[0] expected"));
            assertTrue(error.getMessage().contains("Actual:"));
            assertTrue(error.getMessage().contains("[0] actual"));
        });
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
}
