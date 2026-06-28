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

package io.fluxzero.common.handling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedExecutableInvocationsTest {

    @Test
    void restoresPreviousInvocationWhenLatestRegistrationCloses() {
        String executableId = "METHOD:handle()";

        try (var first = GeneratedExecutableInvocations.register(
                Handler.class, executableId, (target, parameterCount, parameterProvider) -> "first")) {
            assertEquals("first", GeneratedExecutableInvocations.find(Handler.class, executableId)
                    .orElseThrow().invoke(null));

            try (var second = GeneratedExecutableInvocations.register(
                    Handler.class, executableId, (target, parameterCount, parameterProvider) -> "second")) {
                assertEquals("second", GeneratedExecutableInvocations.find(Handler.class, executableId)
                        .orElseThrow().invoke(null));
            }

            assertEquals("first", GeneratedExecutableInvocations.find(Handler.class, executableId)
                    .orElseThrow().invoke(null));
        }

        assertTrue(GeneratedExecutableInvocations.find(Handler.class, executableId).isEmpty());
    }

    private static class Handler {
    }
}
