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
 *
 */

package io.fluxzero.proxy;

import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JettyProxyWebsocketAdapterTest {

    @Test
    void acknowledgesCloseFrameBeforeNotifyingApplication() {
        ProxyWebsocketEndpoint endpoint = mock(ProxyWebsocketEndpoint.class);
        Session jettySession = mock(Session.class);
        when(jettySession.isOpen()).thenReturn(true);
        JettyProxyWebsocketAdapter adapter = new JettyProxyWebsocketAdapter(endpoint, Map.of(), 8, null);
        adapter.onWebSocketOpen(jettySession);

        AtomicBoolean acknowledged = new AtomicBoolean();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            ProxyWebsocketSession session = invocation.getArgument(0);
            WebsocketCloseReason closeReason = invocation.getArgument(1);
            assertTrue(acknowledged.get());
            assertFalse(session.isOpen());
            assertEquals(new WebsocketCloseReason(1000, "done"), closeReason);
            return null;
        }).when(endpoint).onClose(any(), any());

        adapter.onWebSocketClose(1000, "done", Callback.from(
                () -> acknowledged.set(true), callbackFailure::set));

        assertTrue(acknowledged.get());
        assertNull(callbackFailure.get());
        verify(endpoint).onClose(any(), any());
    }

    @Test
    void notificationFailureCannotChangeAcknowledgedClose() {
        ProxyWebsocketEndpoint endpoint = mock(ProxyWebsocketEndpoint.class);
        Session jettySession = mock(Session.class);
        when(jettySession.isOpen()).thenReturn(true);
        JettyProxyWebsocketAdapter adapter = new JettyProxyWebsocketAdapter(endpoint, Map.of(), 8, null);
        adapter.onWebSocketOpen(jettySession);
        RuntimeException failure = new RuntimeException("notification failed");
        doThrow(failure).when(endpoint).onClose(any(), any());

        AtomicBoolean acknowledged = new AtomicBoolean();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        adapter.onWebSocketClose(1000, "done", Callback.from(
                () -> acknowledged.set(true), callbackFailure::set));

        assertTrue(acknowledged.get());
        assertNull(callbackFailure.get());
        var session = ArgumentCaptor.forClass(ProxyWebsocketSession.class);
        var error = ArgumentCaptor.forClass(Throwable.class);
        verify(endpoint).onError(session.capture(), error.capture());
        assertFalse(session.getValue().isOpen());
        assertSame(failure, error.getValue());
    }
}
