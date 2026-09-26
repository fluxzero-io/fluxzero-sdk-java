/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.TestUtils;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Read;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackingClusterWakeupTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void observesRebalanceBeforeWaiterPublication(boolean claimOnly, boolean beforeWaitingRegistration) {
        var source = mock(MessageStore.class);
        when(source.registerMonitor(any())).thenReturn(Registration.noOp());
        List<SerializedMessage> messages = List.of(message(1, 0), message(2, 127));
        when(source.scanBatch(any(), anyInt(), anyBoolean(), anyLong(), any())).thenAnswer(invocation -> {
            Predicate<SerializedMessage> filter = invocation.getArgument(4);
            return new MessageStoreBatch(messages.stream().filter(filter).toList(), 2L, 2, false);
        });
        var scheduler = mock(TaskScheduler.class);
        var duringSchedule = new AtomicReference<Runnable>();
        when(scheduler.schedule(anyLong(), any())).thenAnswer(invocation -> {
            Runnable action = duringSchedule.getAndSet(null);
            if (action != null) {
                action.run();
            }
            return Registration.noOp();
        });
        var beforeWaiting = new AtomicReference<Runnable>();
        try (var strategy = new DefaultTrackingStrategy(source, new InMemoryPositionStore(), scheduler) {
            @Override
            protected void waitForUpdate(Tracker tracker, MessageBatch emptyBatch, Runnable followUp,
                                         TrackerRequest<?> request) {
                Runnable action = beforeWaiting.getAndSet(null);
                if (action != null) {
                    action.run();
                }
                super.waitForUpdate(tracker, emptyBatch, followUp, request);
            }
        }) {
            Tracker owner = tracker("a");
            Tracker waiting = tracker("b");
            assertArrayEquals(new int[]{0, 128}, strategy.claimSegment(owner).join().getSegment());
            Runnable rebalance = () -> assertArrayEquals(new int[]{0, 64}, strategy.getBatch(owner).join().getSegment());
            // Run the owner's next read after the waiter claimed an empty range, either before it marks itself
            // waiting or after that transition but before its scheduled waiter is published. No message arrives.
            (beforeWaitingRegistration ? beforeWaiting : duringSchedule).set(rebalance);
            CompletableFuture<?> result = claimOnly ? strategy.claimSegment(waiting) : strategy.getBatch(waiting);

            assertTrue(result.isDone(), "A missed cluster notification must not defer available work to the long-poll deadline");
            if (claimOnly) {
                assertArrayEquals(new int[]{64, 128}, ((ClaimResult) result.join()).getSegment());
            } else {
                MessageBatch batch = (MessageBatch) result.join();
                assertArrayEquals(new int[]{64, 128}, batch.getSegment());
                assertEquals(List.of(2L), batch.getMessages().stream().map(SerializedMessage::getIndex).toList());
            }
        }
    }

    private static Tracker tracker(String id) {
        return new WebSocketTracker(new Read(MessageType.EVENT, "consumer", id, 1024, 60_000,
                                             null, false, false, false, false, 0L, null),
                                    MessageType.EVENT, "client", "session");
    }

    private static SerializedMessage message(long index, int segment) {
        SerializedMessage result = TestUtils.createMessage();
        result.setIndex(index);
        result.setSegment(segment);
        return result;
    }
}
