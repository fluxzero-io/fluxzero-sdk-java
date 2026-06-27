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

package io.fluxzero.sdk.web;

import io.fluxzero.common.Guarantee;
import io.fluxzero.sdk.publishing.Timeout;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Request;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocketSessionTest {
    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForRequestTimeout() throws Exception {
        CapturingSocketSession session = new CapturingSocketSession();

        GeneratedOnlyMetadataMode.run(() -> session.sendRequest(new UnregisteredTimeoutRequest()));

        assertEquals(Duration.ofSeconds(30), session.timeout.get());
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForRequestTimeout() throws Exception {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredTimeoutRequest.class).registry());
            CapturingSocketSession session = new CapturingSocketSession();

            GeneratedOnlyMetadataMode.run(() -> session.sendRequest(new RegisteredTimeoutRequest()));

            assertEquals(Duration.ofSeconds(2), session.timeout.get());
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    static class UnregisteredTimeoutRequest implements Request<String> {
    }

    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    static class RegisteredTimeoutRequest implements Request<String> {
    }

    static class CapturingSocketSession implements SocketSession {
        private final AtomicReference<Duration> timeout = new AtomicReference<>();

        @Override
        public String sessionId() {
            return "test";
        }

        @Override
        public CompletableFuture<Void> sendMessage(Object value, Guarantee guarantee) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <R> CompletionStage<R> sendRequest(Request<R> request, Duration timeout) {
            this.timeout.set(timeout);
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Void> sendPing(Object value, Guarantee guarantee) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> close(int closeReason, Guarantee guarantee) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
