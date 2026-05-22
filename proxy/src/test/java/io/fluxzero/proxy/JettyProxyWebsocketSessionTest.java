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

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JettyProxyWebsocketSessionTest {

    @Test
    void outgoingSendsAreSerializedWithoutBlockingCaller() throws Exception {
        Session jettySession = mock(Session.class);
        when(jettySession.isOpen()).thenReturn(true);
        ArrayList<Callback> callbacks = new ArrayList<>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1, Callback.class));
            return null;
        }).when(jettySession).sendText(any(), any(Callback.class));

        JettyProxyWebsocketSession session = new JettyProxyWebsocketSession(jettySession, Map.of(), 8);

        var first = session.sendText("one");
        var second = session.sendText("two");

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        verify(jettySession).sendText(eq("one"), any(Callback.class));
        verify(jettySession, never()).sendText(eq("two"), any(Callback.class));

        callbacks.get(0).succeed();
        first.get(1, TimeUnit.SECONDS);

        verify(jettySession).sendText(eq("two"), any(Callback.class));
        callbacks.get(1).succeed();
        second.get(1, TimeUnit.SECONDS);
    }

    @Test
    void closeIsSerializedAfterPendingSendsWithoutBlockingCaller() throws Exception {
        Session jettySession = mock(Session.class);
        when(jettySession.isOpen()).thenReturn(true);
        ArrayList<Callback> sendCallbacks = new ArrayList<>();
        AtomicReference<Callback> closeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            sendCallbacks.add(invocation.getArgument(1, Callback.class));
            return null;
        }).when(jettySession).sendText(any(), any(Callback.class));
        doAnswer(invocation -> {
            closeCallback.set(invocation.getArgument(2, Callback.class));
            return null;
        }).when(jettySession).close(anyInt(), any(), any(Callback.class));

        JettyProxyWebsocketSession session = new JettyProxyWebsocketSession(jettySession, Map.of(), 8);

        var send = session.sendText("one");
        var close = session.close(new WebsocketCloseReason(WebsocketCloseReason.NORMAL_CLOSURE, "done"));

        assertFalse(send.isDone());
        assertFalse(close.isDone());
        verify(jettySession, never()).close(anyInt(), any(), any(Callback.class));

        sendCallbacks.getFirst().succeed();
        send.get(1, TimeUnit.SECONDS);

        verify(jettySession).close(eq(WebsocketCloseReason.NORMAL_CLOSURE), eq("done"), any(Callback.class));
        closeCallback.get().succeed();
        close.get(1, TimeUnit.SECONDS);
    }

    @Test
    void sendBacklogLimitDisconnectsSlowSession() {
        Session jettySession = mock(Session.class);
        when(jettySession.isOpen()).thenReturn(true);
        doAnswer(invocation -> null).when(jettySession).sendText(any(), any(Callback.class));
        JettyProxyWebsocketSession session = new JettyProxyWebsocketSession(jettySession, Map.of(), 1);

        var first = session.sendText("one");
        var second = session.sendText("two");

        assertFalse(first.isDone());
        assertTrue(second.isCompletedExceptionally());
        assertFalse(session.isOpen());
        verify(jettySession).disconnect();
    }
}
