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

package io.fluxzero.sdk.tracking.handling.authentication;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.LocalHandlerRegistry;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.sdk.publishing.DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Isolated
class AuthorizationInvocationTest {
    private User user = new MockUser("admin");
    private final Target target = new Target();

    @Test
    void idMetadataUsesTwoLookupsAndRechecksRevokedRoles() {
        for (int permitted : new int[]{Integer.MAX_VALUE, 1}) {
            AtomicInteger lookups = new AtomicInteger();
            UserProvider provider = new AbstractUserProvider(MockUser.class) {
                @Override public User getSystemUser() { return null; }
                @Override public User getUserById(Object id) {
                    return new MockUser(lookups.incrementAndGet() <= permitted ? "admin" : "member");
                }
            };
            try (Fluxzero fluxzero = DefaultFluxzero.builder().registerUserProvider(provider)
                    .replacePropertySource(source -> new SimplePropertySource(Map.of(
                            AbstractUserProvider.USE_USER_ID_METADATA_PROPERTY, "true",
                            SHUTDOWN_TIMEOUT_PROPERTY, "0")).andThen(source))
                    .build(LocalClient.newInstance(null))) {
                target.invocations = 0;
                fluxzero.commandGateway().registerHandler(target);
                var result = fluxzero.apply(f -> f.commandGateway().send(
                        new Message(new Request()).addMetadata("$user", "actor")));
                try {
                    assertEquals(2, lookups.get());
                    assertEquals(permitted == 1 ? 0 : 1, target.invocations);
                    if (permitted == 1) {
                        assertFalse(result.isDone(), "Silent refusal cannot complete as successful null");
                    } else {
                        assertEquals("handled", result.join());
                    }
                    assertNull(User.getCurrent());
                } finally {
                    result.cancel(false);
                }
            }
        }
    }

    @Test
    void reusedInvokerClearsSilentRefusalBeforeSuccessAndFailure() {
        var wrapped = authenticated(target);
        var invoker = wrapped.getInvokerOrNull(message());
        user = null;
        assertNull(invoker.invoke());
        assertTrue(invoker.wasSkipped());
        user = new MockUser("admin");
        assertEquals("handled", invoker.invoke());
        assertFalse(invoker.wasSkipped());
        user = null;
        invoker.invoke();
        user = new User() {
            @Override public String id() { return "actor"; }
            @Override public boolean hasRole(String role) { throw new IllegalStateException("lookup failed"); }
        };
        assertThrows(IllegalStateException.class, invoker::invoke);
        assertFalse(invoker.wasSkipped());
        assertNull(User.getCurrent());
    }

    @Test
    void customPayloadAndHandlerIdentityHooksRemainDistinct() {
        var auth = new AuthenticatingInterceptor(new FixedUserProvider(() -> null)) {
            @Override protected User getCurrentUser(DeserializingMessage message) { return new MockUser("admin"); }
            @Override protected User getAuthorizingUser(DeserializingMessage message) { return new MockUser("operator"); }
        };
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(new OperatorTarget(), HandleCommand.class,
                                                     List.of(new PayloadParameterResolver()));
        assertEquals("operator", auth.wrap(handler).getInvokerOrNull(message()).invoke());
    }

    @Test
    void deferredAuthorizationSuppressesLateDenialAndPreservesRealNull() {
        for (boolean allowed : new boolean[]{false, true}) {
            user = new MockUser("admin");
            CompletableFuture<Void> gate = new CompletableFuture<>();
            HandlerInterceptor delay = (next, invoker) -> m -> gate.thenApply(ignored -> next.apply(m));
            var registry = registry(delay.wrap(authenticated(target)));
            var result = registry.handle(message(), false).orElseThrow();
            user = allowed ? new MockUser("admin") : null;
            target.result = null;
            gate.complete(null);
            try {
                assertEquals(allowed, result.isDone());
                if (allowed) {
                    assertNull(result.join(), "A genuine null result must still be returned");
                }
            } finally {
                result.cancel(false);
            }
        }
        assertEquals(1, target.invocations);
        assertNull(User.getCurrent());
    }

    @Test
    void deferredResponseCancellationPropagatesToTheSource() {
        var source = new CompletableFuture<Object>();
        target.result = source;
        var result = registry(authenticated(target)).handle(message(), false).orElseThrow();
        assertTrue(result.cancel(true));
        assertTrue(source.isCancelled());
    }

    @Test
    void deferredFailureIsStillReported() {
        var source = new CompletableFuture<Object>();
        target.result = source;
        var result = registry(authenticated(target)).handle(message(), false).orElseThrow();
        var error = new IllegalStateException("handler failed");
        source.completeExceptionally(error);
        assertSame(error, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    @Test
    void deferredMirroringUsesTheOriginalApplicationAndMessageContext() throws Exception {
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(LocalClient.newInstance(null))) {
            var gate = new CompletableFuture<Void>();
            AtomicReference<Fluxzero> observedApp = new AtomicReference<>();
            AtomicReference<DeserializingMessage> observedMessage = new AtomicReference<>();
            AtomicInteger mirrors = new AtomicInteger();
            DispatchInterceptor dispatch = new DispatchInterceptor() {
                @Override public Message interceptDispatch(Message m, MessageType type, String topic) { return m; }
                @Override public io.fluxzero.common.api.SerializedMessage modifySerializedMessage(
                        io.fluxzero.common.api.SerializedMessage serialized, Message m, MessageType type, String topic) {
                    observedApp.set(Fluxzero.get());
                    observedMessage.set(DeserializingMessage.getCurrent());
                    mirrors.incrementAndGet();
                    return null;
                }
            };
            HandlerInterceptor delay = (next, invoker) -> m -> gate.thenApply(ignored -> next.apply(m));
            var registry = new LocalHandlerRegistry(mock(HandlerFactory.class), dispatch);
            registry.registerHandler(delay.wrap(authenticated(new LoggingTarget())), (t, m) -> true);
            var message = message();
            var result = fluxzero.apply(f -> registry.handle(message).orElseThrow());
            Thread worker = new Thread(() -> gate.complete(null));
            worker.start();
            worker.join();
            assertEquals("handled", result.join());
            assertSame(fluxzero, observedApp.get());
            assertSame(message, observedMessage.get());
            assertEquals(1, mirrors.get());
        }
    }

    @Test
    void authorizedAsyncHandlerMirrorsBeforeItsResultCompletes() {
        for (boolean deferred : new boolean[]{false, true}) {
            try (Fluxzero fluxzero = DefaultFluxzero.builder().build(LocalClient.newInstance(null))) {
                AtomicInteger mirrors = new AtomicInteger();
                DispatchInterceptor dispatch = new DispatchInterceptor() {
                    @Override public Message interceptDispatch(Message m, MessageType type, String topic) { return m; }
                    @Override public io.fluxzero.common.api.SerializedMessage modifySerializedMessage(
                            io.fluxzero.common.api.SerializedMessage serialized, Message m, MessageType type, String topic) {
                        mirrors.incrementAndGet();
                        return null;
                    }
                };
                var source = new CompletableFuture<Object>();
                var handler = new LoggingTarget();
                handler.result = source;
                var registry = new LocalHandlerRegistry(mock(HandlerFactory.class), dispatch);
                var gate = new CompletableFuture<Void>();
                HandlerInterceptor delay = (next, invoker) -> m -> gate.thenCompose(
                        ignored -> (CompletableFuture<Object>) next.apply(m));
                var wrapped = authenticated(handler);
                registry.registerHandler(deferred ? delay.wrap(wrapped) : wrapped, (t, m) -> true);
                var result = fluxzero.apply(f -> registry.handle(message()).orElseThrow());
                if (deferred) {
                    assertEquals(0, mirrors.get());
                    gate.complete(null);
                }
                assertEquals(1, mirrors.get(), "A handler may wait for another consumer of its mirrored command");
                assertFalse(result.isDone());
                source.complete("handled");
                assertEquals("handled", result.join());
                assertEquals(1, mirrors.get());
            }
        }
    }

    @Test
    void deferredDenialDoesNotMirrorTheLocalCommand() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder().build(LocalClient.newInstance(null))) {
            var gate = new CompletableFuture<Void>();
            AtomicInteger mirrors = new AtomicInteger();
            DispatchInterceptor dispatch = new DispatchInterceptor() {
                @Override public Message interceptDispatch(Message m, MessageType type, String topic) { return m; }
                @Override public io.fluxzero.common.api.SerializedMessage modifySerializedMessage(
                        io.fluxzero.common.api.SerializedMessage serialized, Message m, MessageType type, String topic) {
                    mirrors.incrementAndGet();
                    return null;
                }
            };
            HandlerInterceptor delay = (next, invoker) -> m -> gate.thenApply(ignored -> next.apply(m));
            var registry = new LocalHandlerRegistry(mock(HandlerFactory.class), dispatch);
            registry.registerHandler(delay.wrap(authenticated(new LoggingTarget())), (t, m) -> true);
            var result = fluxzero.apply(f -> registry.handle(message()).orElseThrow());
            user = null;
            gate.complete(null);
            assertFalse(result.isDone());
            assertEquals(0, mirrors.get());
            result.cancel(false);
        }
    }

    @Test
    void customInterceptionKeepsItsAsyncCompletionAndMirroringContract() {
        for (boolean prepared : new boolean[]{false, true}) {
            var provider = new FixedUserProvider(() -> user);
            AuthenticatingInterceptor auth = prepared ? new AuthenticatingInterceptor(provider) {
                @Override public PreparedHandlerInterceptor prepare(io.fluxzero.common.handling.HandlerDescriptor handler) {
                    return PreparedHandlerInterceptor.noOp;
                }
            } : new AuthenticatingInterceptor(provider) {
                @Override public java.util.function.Function<DeserializingMessage, Object> interceptHandling(
                        java.util.function.Function<DeserializingMessage, Object> next,
                        io.fluxzero.common.handling.HandlerInvoker invoker) { return next; }
            };
            var source = new CompletableFuture<Object>();
            var handler = new LoggingTarget();
            handler.result = source;
            Handler<DeserializingMessage> underlying = HandlerInspector.createHandler(
                    handler, HandleCommand.class, List.of(new PayloadParameterResolver()));
            var invoker = auth.wrap(underlying).getInvokerOrNull(message());
            assertSame(source, invoker.invoke());
            assertFalse(invoker.isSkipPending(), "Overridden interception must not leave a framework decision pending");
            assertFalse(invoker.maySkip());
            source.complete("handled");
        }
    }

    @Test
    void unrelatedOverloadsRetainSilentDenialTracking() {
        var auth = new AuthenticatingInterceptor(new FixedUserProvider(() -> user)) {
            public void prepare(String unused) {}
            public void interceptHandling(String unused) {}
        };
        Handler<DeserializingMessage> underlying = HandlerInspector.createHandler(
                target, HandleCommand.class, List.of(new PayloadParameterResolver()));
        var invoker = auth.wrap(underlying).getInvokerOrNull(message());
        assertTrue(invoker.maySkip());
        user = null;
        assertNull(invoker.invoke());
        assertTrue(invoker.wasSkipped());
        assertFalse(invoker.isSkipPending());
        assertEquals(0, target.invocations);
    }

    private Handler<DeserializingMessage> authenticated(Object target) {
        return new AuthenticatingInterceptor(new FixedUserProvider(() -> user)).wrap(
                HandlerInspector.createHandler(target, HandleCommand.class, List.of(new PayloadParameterResolver())));
    }

    private LocalHandlerRegistry registry(Handler<DeserializingMessage> handler) {
        var registry = new LocalHandlerRegistry(mock(HandlerFactory.class), DispatchInterceptor.noOp);
        registry.registerHandler(handler, (t, m) -> true);
        return registry;
    }

    private DeserializingMessage message() {
        return new DeserializingMessage(new Message(new Request()), MessageType.COMMAND, new JacksonSerializer());
    }

    @RequiresAnyRole(value = "admin", throwIfUnauthorized = false)
    record Request() {}

    @LocalHandler
    static class Target {
        int invocations;
        Object result = "handled";
        @HandleCommand Object handle(Request request) { invocations++; return result; }
    }

    static class OperatorTarget {
        @HandleCommand @RequiresAnyRole("operator")
        String handle(Request request) { return "operator"; }
    }

    @LocalHandler(logMessage = true)
    static class LoggingTarget {
        Object result = "handled";
        @HandleCommand Object handle(Request request) { return result; }
    }
}
