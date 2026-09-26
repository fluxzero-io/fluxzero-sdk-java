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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebsocketTrackingCancellationTest {
    @Test
    void delayedReadIsReleasedAgainAfterInitialDisconnectWasAcknowledged() {
        var client = mock(TestClient.class, CALLS_REAL_METHODS);
        var delayedRead = new CompletableFuture<RequestResult>();
        var finalRelease = new CompletableFuture<Void>();
        doReturn(List.of(delayedRead)).when(client).pendingResponses(any());
        doReturn(CompletableFuture.completedFuture(null)).when(client)
                .disconnectTracker("consumer", "tracker", true, Guarantee.STORED);
        doReturn(finalRelease).when(client).disconnectTracker("consumer", "tracker", false, Guarantee.STORED);
        var cancellation = client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
        assertFalse(cancellation.isDone());
        verify(client, never()).disconnectTracker("consumer", "tracker", false, Guarantee.STORED);
        delayedRead.complete(mock(RequestResult.class));
        verify(client).disconnectTracker("consumer", "tracker", false, Guarantee.STORED);
        assertFalse(cancellation.isDone());
        finalRelease.complete(null);
        assertTrue(cancellation.isDone());
    }

    @Test
    void trackerWithoutPendingReadUsesOnlyOneDisconnect() {
        var client = mock(TestClient.class, CALLS_REAL_METHODS);
        doReturn(List.of()).when(client).pendingResponses(any());
        var release = CompletableFuture.<Void>completedFuture(null);
        doReturn(release).when(client).disconnectTracker("consumer", "tracker", false, Guarantee.STORED);
        assertSame(release, client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED));
        verify(client).disconnectTracker("consumer", "tracker", false, Guarantee.STORED);
        verify(client, never()).disconnectTracker("consumer", "tracker", true, Guarantee.STORED);
    }

    abstract static class TestClient extends WebsocketTrackingClient {
        TestClient(WebSocketClient client) { super("ws://localhost", client, MessageType.COMMAND, null); }
        @Override public abstract List<CompletableFuture<RequestResult>> pendingResponses(Predicate<Request> filter);
    }
}
