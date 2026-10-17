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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class CachingTrackingTypeFilterTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"event.Created", "event\\..*", "event\\.(Created|Updated)"})
    void cachedManualReadsApplyTheConfiguredRegexToThePayloadType(String typeFilter) {
        var delegate = mock(TrackingClient.class);
        when(delegate.getMessageType()).thenReturn(MessageType.EVENT);
        var config = ConsumerConfiguration.builder().name("manual").typeFilter(typeFilter)
                .maxFetchSize(10).maxWaitDuration(Duration.ZERO).build();
        when(delegate.claimSegment("tracker", 10L, config)).thenReturn(CompletableFuture.completedFuture(
                new ClaimSegmentResult(1L, new Position(new int[]{0, 128}, 10L), new int[]{0, 128})));
        var matching = message(11L, "event.Created");
        var unknown = message(12L, null);
        var excluded = message(13L, "command.Create");
        try (var client = new CachingTrackingClient(delegate, 10);
             var trackers = mockStatic(DefaultTracker.class)) {
            trackers.when(() -> DefaultTracker.start(any(), any(ConsumerConfiguration.class), same(delegate)))
                    .thenReturn(mock(Registration.class));
            client.cacheNewMessages(List.of(message(10L, "cursor"), matching, unknown, excluded));
            var batch = client.read("tracker", 10L, config).join();
            assertEquals(typeFilter == null ? List.of(matching, unknown, excluded) : List.of(matching, unknown),
                         batch.getMessages());
            assertEquals(13L, batch.getLastIndex());
            verify(delegate, never()).read(anyString(), any(), any());
        }
    }

    private static SerializedMessage message(long index, String type) {
        var result = new SerializedMessage(new Data<>(new byte[0], type, 0, Data.JSON_FORMAT),
                                           Metadata.empty(), Long.toString(index), 0L);
        result.setIndex(index);
        result.setSegment(0);
        return result;
    }
}
