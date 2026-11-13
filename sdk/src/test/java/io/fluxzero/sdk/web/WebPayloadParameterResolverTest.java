/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.web;

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebPayloadParameterResolverTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsOriginalFailureOnceDuringInvocation(boolean authorize) throws Exception {
        var method = Endpoint.class.getDeclaredMethod("handle", Input.class);
        var message = mock(HasMessage.class);
        when(message.toMessage()).thenReturn(new Message(new byte[0]));
        var failure = new DeserializationException("invalid date");
        when(message.getPayloadAs(Input.class)).thenThrow(failure).thenReturn(new Input(null));
        var resolver = new WebPayloadParameterResolver(true, authorize);
        var prepared = resolver.resolveIfPossible(method.getParameters()[0], method.getAnnotation(HandlePost.class), message);
        assertNotNull(prepared);
        assertSame(failure, assertThrows(DeserializationException.class, () -> prepared.apply(message)));
        verify(message, times(1)).getPayloadAs(Input.class);
    }

    @Test
    void retainsSilentAuthorizationForReadablePayload() throws Exception {
        var method = Endpoint.class.getDeclaredMethod("silent", SilentInput.class);
        var message = new Message(new SilentInput());
        User previous = User.getCurrent();
        try {
            User.current.remove();
            var resolver = new WebPayloadParameterResolver(true, true);
            assertNull(resolver.resolveIfPossible(method.getParameters()[0], method.getAnnotation(HandlePost.class), message));
        } finally {
            User.current.set(previous);
        }
    }

    @Test
    void doesNotConsumeStreamDuringSelection() throws Exception {
        var method = Endpoint.class.getDeclaredMethod("handle", Input.class);
        var body = new ByteArrayInputStream("{\"date\":\"2026-01-20\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var message = WebRequest.post("/typed").contentType("application/json").payload(body).build();
        var resolver = new WebPayloadParameterResolver(true, true);
        int available = body.available();
        var prepared = resolver.resolveIfPossible(method.getParameters()[0], method.getAnnotation(HandlePost.class), message);
        org.junit.jupiter.api.Assertions.assertEquals(available, body.available());
        org.junit.jupiter.api.Assertions.assertEquals(new Input(LocalDate.of(2026, 1, 20)), prepared.apply(message));
        org.junit.jupiter.api.Assertions.assertEquals(0, body.available());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictStreamingBodyIsConsumedOnlyDuringBinding(boolean valid) throws Exception {
        var method = Endpoint.class.getDeclaredMethod("strict", Input.class);
        String json = valid ? "{\"date\":\"2026-01-20\"}"
                : "{\"date\":\"2026-01-20\",\"date\":\"2026-01-21\"}";
        var body = new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var message = WebRequest.post("/typed").contentType("application/json").payload(body).build();
        var resolver = new WebPayloadParameterResolver(true, true);
        var prepared = resolver.resolveIfPossible(method.getParameters()[0], method.getAnnotation(HandlePost.class), message);
        org.junit.jupiter.api.Assertions.assertEquals(json.length(), body.available());
        if (valid) {
            org.junit.jupiter.api.Assertions.assertEquals(new Input(LocalDate.of(2026, 1, 20)), prepared.apply(message));
        } else {
            assertThrows(DeserializationException.class, () -> prepared.apply(message));
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, body.available());
    }

    @Test
    void strictLocalTypedObjectDoesNotInvokeSerializer() throws Exception {
        var method = Endpoint.class.getDeclaredMethod("strict", Input.class);
        var input = new Input(LocalDate.of(2026, 1, 20));
        var serializer = mock(io.fluxzero.sdk.common.serialization.Serializer.class);
        var message = new io.fluxzero.sdk.common.serialization.DeserializingMessage(
                new Message(input), io.fluxzero.common.MessageType.WEBREQUEST, serializer);
        var result = new WebPayloadParameterResolver(false, false)
                .resolve(method.getParameters()[0], method.getAnnotation(HandlePost.class)).apply(message);
        org.junit.jupiter.api.Assertions.assertEquals(input, result);
        org.mockito.Mockito.verifyNoInteractions(serializer);
    }

    @Test
    void strictWireStringIsNotParsedAgainAfterDeserialization() throws Exception {
        var method = Endpoint.class.getDeclaredMethod("choice", Choice.class);
        var wire = new io.fluxzero.common.api.SerializedMessage(
                new io.fluxzero.common.api.Data<>("\"FIRST\"".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        null, 0, "application/json"), io.fluxzero.common.api.Metadata.empty(), "id", 0L);
        var serializer = new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer();
        var message = serializer.deserializeMessage(wire, io.fluxzero.common.MessageType.WEBREQUEST);
        var result = new WebPayloadParameterResolver(false, false)
                .resolve(method.getParameters()[0], method.getAnnotation(HandlePost.class)).apply(message);
        org.junit.jupiter.api.Assertions.assertEquals(Choice.FIRST, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/cbor"})
    void strictBindingPreservesNonJsonWireSerializer(String format) throws Exception {
        var method = Endpoint.class.getDeclaredMethod("strict", Input.class);
        var input = new Input(LocalDate.of(2026, 1, 20));
        var wire = new io.fluxzero.common.api.SerializedMessage(
                new io.fluxzero.common.api.Data<>(new byte[]{1, 2, 3}, null, 0, format),
                io.fluxzero.common.api.Metadata.empty(), "custom", 0L);
        var message = new io.fluxzero.sdk.common.serialization.DeserializingMessage(
                wire, type -> type == Object.class ? "decoded non-JSON scalar" : input,
                io.fluxzero.common.MessageType.WEBREQUEST, null, null);
        var result = new WebPayloadParameterResolver(false, false)
                .resolve(method.getParameters()[0], method.getAnnotation(HandlePost.class)).apply(message);
        assertSame(input, result);
    }

    @Test
    void strictBindingPreservesExplicitNonJsonLocalConversion() throws Exception {
        var method = Endpoint.class.getDeclaredMethod("strict", Input.class);
        var input = new Input(LocalDate.of(2026, 1, 20));
        var message = mock(HasMessage.class);
        when(message.getMetadata()).thenReturn(WebRequest.post("/typed").contentType("text/plain")
                                                      .payload("custom text").build().getMetadata());
        when(message.getPayloadAs(Input.class)).thenReturn(input);
        var result = new WebPayloadParameterResolver(false, false)
                .resolve(method.getParameters()[0], method.getAnnotation(HandlePost.class)).apply(message);
        assertSame(input, result);
        org.mockito.Mockito.verify(message, org.mockito.Mockito.never()).getPayload();
    }

    @ParameterizedTest
    @MethodSource("bodyPolicies")
    void resolvesBodyPolicyForAllWebAnnotationsAndInheritedMethods(Method method) {
        var annotation = ReflectionUtils.getMethodAnnotation(method, HandleWeb.class).orElseThrow();
        var message = WebRequest.post("/typed").contentType("application/json")
                .payload("{\"date\":\"2026-01-20\"} {\"date\":\"2026-01-21\"}").build();
        var resolver = new WebPayloadParameterResolver(false, false);
        var prepared = resolver.resolveIfPossible(method.getParameters()[0], annotation, message);
        assertNotNull(prepared);
        if (method.getName().startsWith("permissive") || method.getDeclaringClass() == RelaxedPolicies.class) {
            org.junit.jupiter.api.Assertions.assertEquals(new Input(LocalDate.of(2026, 1, 20)), prepared.apply(message));
        } else {
            assertThrows(DeserializationException.class, () -> prepared.apply(message));
        }
        var valid = message.withPayload("{\"date\":\"2026-01-20\"}");
        org.junit.jupiter.api.Assertions.assertEquals(new Input(LocalDate.of(2026, 1, 20)),
                                                    prepared.apply(valid));
    }

    static Stream<Method> bodyPolicies() {
        return Stream.of(BodyPolicies.class, InheritedPolicies.class, RelaxedPolicies.class)
                .flatMap(type -> Stream.of(type.getDeclaredMethods()));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @HandleWeb(strictBody = true)
    @interface StrictBodyRoute {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @HandleWeb(strictBody = true)
    @interface ConfigurableBodyRoute {
        boolean strictBody() default false;
    }

    static class BodyPolicies {
        @HandleWeb(strictBody = true) void web(Input input) {}
        @HandleGet(strictBody = true) void get(Input input) {}
        @HandlePost(strictBody = true) void post(Input input) {}
        @HandlePut(strictBody = true) void put(Input input) {}
        @HandlePatch(strictBody = true) void patch(Input input) {}
        @HandleDelete(strictBody = true) void delete(Input input) {}
        @HandleHead(strictBody = true) void head(Input input) {}
        @HandleOptions(strictBody = true) void options(Input input) {}
        @HandleTrace(strictBody = true) void trace(Input input) {}
        @HandleSocketHandshake(strictBody = true) void handshake(Input input) {}
        @HandleSocketOpen(strictBody = true) void open(Input input) {}
        @HandleSocketMessage(strictBody = true) void message(Input input) {}
        @HandleSocketClose(strictBody = true) void close(Input input) {}
        @HandleSocketPong(strictBody = true) void pong(Input input) {}
        @StrictBodyRoute void composed(Input input) {}
        @ConfigurableBodyRoute(strictBody = true) void composedOverride(Input input) {}
        @ConfigurableBodyRoute void permissiveComposedOverride(Input input) {}
        @HandlePost void permissiveDefault(Input input) {}
    }

    static class InheritedPolicies extends BodyPolicies {
        @Override void post(Input input) {}
        @Override void composed(Input input) {}
    }

    static class RelaxedPolicies extends BodyPolicies {
        @Override @HandlePost(strictBody = false) void post(Input input) {}
    }

    enum Choice { FIRST }

    record Input(LocalDate date) {}

    @RequiresUser(throwIfUnauthorized = false)
    record SilentInput() {}

    static class Endpoint {
        @HandlePost(value = "/typed", strictBody = true)
        void strict(Input input) {}

        @HandlePost(value = "/choice", strictBody = true)
        void choice(Choice input) {}

        @HandlePost("/typed")
        void handle(Input input) {}

        @HandlePost("/silent")
        void silent(SilentInput input) {}
    }
}
