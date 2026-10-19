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

import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;

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

    record Input(LocalDate date) {}

    @RequiresUser(throwIfUnauthorized = false)
    record SilentInput() {}

    static class Endpoint {
        @HandlePost("/typed")
        void handle(Input input) {}

        @HandlePost("/silent")
        void silent(SilentInput input) {}
    }
}
