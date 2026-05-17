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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.client.LocalTrackingClient;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutTest {
    @Test
    void testHandleSelf() {
        @Timeout(10)
        class HandleSelfRequest {
            @HandleQuery
            CompletableFuture<String> handle() {
                return new CompletableFuture<>();
            }
        }

        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> Fluxzero.queryAndWait(new HandleSelfRequest()))
                .expectExceptionalResult(TimeoutException.class);
        assertEmptyEventually(gatewayCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testHandleSelfAsync() {
        @Timeout(10)
        class HandleSelfRequest {
            @HandleQuery
            CompletableFuture<String> handle() {
                return new CompletableFuture<>();
            }
        }

        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> fc.queryGateway().send(new HandleSelfRequest()))
                .expectExceptionalResult(java.util.concurrent.TimeoutException.class);
        assertEmptyEventually(gatewayCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testUnhandled() {
        @Timeout(10)
        class UnhandledRequest { }

        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> Fluxzero.queryAndWait(new UnhandledRequest()))
                .verifyExceptionalResult((TimeoutException e) -> {
                    assertTrue(e.getMessage().contains("FZ-SDK-0002"));
                    assertTrue(e.getMessage().contains("What happened:"));
                    assertTrue(e.getMessage().contains("How to fix:"));
                    assertTrue(e.getMessage().contains("docs/errors#FZ-SDK-0002"));
                });
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testUnhandledAsync() {
        @Timeout(10)
        class UnhandledRequest { }

        TestFixture fixture = TestFixture.create();
        fixture.whenApplying(fc -> fc.queryGateway().send(new UnhandledRequest()))
                .expectExceptionalResult(java.util.concurrent.TimeoutException.class);
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testTimeoutMetadataIsStoredForAsyncRequest() {
        @Timeout(10)
        class UnhandledRequest { }

        TestFixture fixture = TestFixture.create();
        AtomicReference<SerializedMessage> appended = new AtomicReference<>();
        Registration registration = ((LocalTrackingClient) fixture.getFluxzero().client()
                .getTrackingClient(MessageType.QUERY)).registerMonitor(messages -> appended.set(messages.getFirst()));
        fixture.getFluxzero().queryGateway().send(new UnhandledRequest());
        try {
            assertNotNull(appended.get());
            assertEquals("10", appended.get().getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
        } finally {
            registration.cancel();
        }
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testCustomRequestTimeoutOverridesAnnotatedTimeoutMetadata() {
        @Timeout(10)
        class UnhandledRequest { }

        TestFixture fixture = TestFixture.create();
        AtomicReference<SerializedMessage> appended = new AtomicReference<>();
        Registration registration = ((LocalTrackingClient) fixture.getFluxzero().client()
                .getTrackingClient(MessageType.QUERY)).registerMonitor(messages -> appended.set(messages.getFirst()));
        DefaultGenericGateway gateway = getField(fixture.getFluxzero().queryGateway(), "delegate");
        gateway.sendForMessage(new Message(new UnhandledRequest()), Duration.ofMillis(25));
        try {
            assertNotNull(appended.get());
            assertEquals("25", appended.get().getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
        } finally {
            registration.cancel();
        }
        assertEmptyEventually(requestHandlerCallbacks(fixture.getFluxzero().queryGateway()));
    }

    @Test
    void testDefaultRequestHandlerTimeoutMetadataIsStored() {
        class UnhandledRequest { }

        TestFixture fixture = TestFixture.create();
        DefaultRequestHandler requestHandler = new DefaultRequestHandler(
                fixture.getFluxzero().client(), MessageType.RESULT);
        SerializedMessage request = new SerializedMessage(
                new Data<>(new byte[0], UnhandledRequest.class.getName(), 0),
                Metadata.empty(), "message-id", System.currentTimeMillis());
        CompletableFuture<SerializedMessage> future = requestHandler.prepareRequest(request, null, null);
        try {
            assertEquals("200000", request.getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
        } finally {
            future.cancel(true);
            requestHandler.close();
        }
    }

    private static Map<?, ?> gatewayCallbacks(Object gateway) {
        return getField(getField(gateway, "delegate"), "callbacks");
    }

    private static Map<?, ?> requestHandlerCallbacks(Object gateway) {
        return getField(getField(getField(gateway, "delegate"), "requestHandler"), "callbacks");
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("No field '%s' on %s".formatted(name, target.getClass().getName()));
    }

    private static void assertEmptyEventually(Map<?, ?> callbacks) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (callbacks.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertTrue(callbacks.isEmpty());
    }
}
