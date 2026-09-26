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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CachingTrackingCancellationTest {
    @Test
    void terminalReleaseWaitsForDelayedClaimAndItsFallbackRead() {
        var delegate = mock(TrackingClient.class);
        when(delegate.getMessageType()).thenReturn(MessageType.EVENT);
        when(delegate.readAndWait(anyString(), any(), any())).thenAnswer(invocation -> new CompletableFuture<>().get());
        var claim = new CompletableFuture<ClaimSegmentResult>();
        var fallback = new CompletableFuture<MessageBatch>();
        var config = ConsumerConfiguration.builder().name("consumer").build();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(claim);
        when(delegate.read("tracker", 10L, config)).thenReturn(fallback);
        when(delegate.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED))
                .thenReturn(CompletableFuture.completedFuture(null));
        try (var client = new CachingTrackingClient(delegate, 10)) {
            var cached = mock(SerializedMessage.class);
            when(cached.getIndex()).thenReturn(10L);
            when(cached.getSegment()).thenReturn(0);
            client.cacheNewMessages(List.of(cached));
            var reading = client.read("tracker", 10L, config);
            var cancellation = client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            verify(delegate).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            assertFalse(cancellation.isDone());
            claim.complete(new ClaimSegmentResult(1, Position.newPosition(), new int[]{0, 128}));
            verify(delegate).read("tracker", 10L, config);
            assertFalse(cancellation.isDone());
            fallback.complete(new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true));
            assertTrue(reading.isDone());
            assertTrue(cancellation.isDone());
            verify(delegate, times(2)).disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
        }
    }
}
