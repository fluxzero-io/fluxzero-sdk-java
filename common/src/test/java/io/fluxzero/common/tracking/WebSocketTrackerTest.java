/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.Read;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTrackerTest {

    @Test
    void trackerWithoutTypeFilterDoesNotMaterializeMessageType() {
        SerializedMessage message = mock(SerializedMessage.class);
        WebSocketTracker tracker = tracker(null);

        assertTrue(tracker.canHandle(message, new int[]{0, 128}));

        verify(message, never()).getType();
    }

    @Test
    void configuredTypeFilterStillEvaluatesMessageType() {
        SerializedMessage message = mock(SerializedMessage.class);
        when(message.getType()).thenReturn("example.Command");
        WebSocketTracker tracker = tracker("example\\.Command");

        assertTrue(tracker.canHandle(message, new int[]{0, 128}));

        verify(message).getType();
    }

    private static WebSocketTracker tracker(String typeFilter) {
        return new WebSocketTracker(
                new Read(
                        MessageType.COMMAND, "consumer", "tracker",
                        128, 0L, 1_000L, typeFilter,
                        false, true, false, false,
                        null, null),
                MessageType.COMMAND, "client", "session");
    }
}
