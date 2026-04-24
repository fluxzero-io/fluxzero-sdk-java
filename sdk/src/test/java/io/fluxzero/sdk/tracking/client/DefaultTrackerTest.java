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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.tracking.Tracker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTrackerTest {

    @Test
    void doesNotFetchStoredPositionWhenNoMaxIndexIsConfigured() throws Exception {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("consumer").build();
        Tracker tracker = new Tracker("trackerId", MessageType.EVENT, null, config, null);
        DefaultTracker defaultTracker = createTracker(trackingClient, config, tracker);
        CountDownLatch fetching = new CountDownLatch(1);

        when(trackingClient.readAndWait(anyString(), any(), same(config))).thenAnswer(invocation -> {
            fetching.countDown();
            Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            return null;
        });

        Thread trackerThread = new Thread(defaultTracker, "test-tracker");
        trackerThread.start();

        try {
            assertTrue(fetching.await(1, TimeUnit.SECONDS));
            verify(trackingClient, after(100).never()).getPosition("consumer");
        } finally {
            defaultTracker.cancel();
            trackerThread.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(trackerThread.isAlive());
        }
    }

    @Test
    void doesNotFetchStoredPositionWhenMaxIndexIsStillInTheFuture() throws Exception {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder()
                .name("consumer")
                .maxIndexExclusive(IndexUtils.indexFromTimestamp(Instant.parse("2999-01-01T00:00:00Z")))
                .build();
        Tracker tracker = new Tracker("trackerId", MessageType.EVENT, null, config, null);
        DefaultTracker defaultTracker = createTracker(trackingClient, config, tracker);
        CountDownLatch fetching = new CountDownLatch(1);

        when(trackingClient.readAndWait(anyString(), any(), same(config))).thenAnswer(invocation -> {
            fetching.countDown();
            Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            return null;
        });

        Thread trackerThread = new Thread(defaultTracker, "test-tracker");
        trackerThread.start();

        try {
            assertTrue(fetching.await(1, TimeUnit.SECONDS));
            verify(trackingClient, after(100).never()).getPosition("consumer");
        } finally {
            defaultTracker.cancel();
            trackerThread.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(trackerThread.isAlive());
        }
    }

    @Test
    void stopsBeforeFirstReadWhenStoredPositionAlreadyReachedMaxIndex() {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder()
                .name("consumer")
                .threads(1)
                .minIndex(IndexUtils.indexFromTimestamp(Instant.parse("2020-01-01T00:00:00Z")))
                .maxIndexExclusive(IndexUtils.indexFromTimestamp(Instant.parse("2021-01-01T00:00:00Z")))
                .build();
        long storedIndex = config.getMaxIndexExclusive();

        when(trackingClient.getMessageType()).thenReturn(MessageType.EVENT);
        when(trackingClient.getPosition("consumer")).thenReturn(new Position(storedIndex));
        when(trackingClient.disconnectTracker(eq("consumer"), anyString(), eq(false))).thenReturn(
                CompletableFuture.completedFuture(null));

        Registration registration = DefaultTracker.start(messages -> {
        }, config, trackingClient);
        try {
            verify(trackingClient, timeout(1000).atLeastOnce()).getPosition("consumer");
            verify(trackingClient, timeout(1000)).disconnectTracker(eq("consumer"), anyString(), eq(false));
            verify(trackingClient, never()).readAndWait(anyString(), any(), same(config));
        } finally {
            registration.cancel();
        }
    }

    @Test
    void startsFromStoredPositionWhenMaxIndexIsInThePastButNotReachedYet() {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder()
                .name("consumer")
                .threads(1)
                .minIndex(IndexUtils.indexFromTimestamp(Instant.parse("2020-01-01T00:00:00Z")))
                .maxIndexExclusive(IndexUtils.indexFromTimestamp(Instant.parse("2021-01-01T00:00:00Z")))
                .build();
        long storedIndex = config.getMaxIndexExclusive() - 1;
        long firstReadIndex = config.getMinIndex() - 1;

        when(trackingClient.getMessageType()).thenReturn(MessageType.EVENT);
        when(trackingClient.getPosition("consumer")).thenReturn(new Position(storedIndex));
        when(trackingClient.readAndWait(anyString(), eq(firstReadIndex), same(config))).thenReturn(
                new MessageBatch(new int[]{0, 128}, List.of(), config.getMaxIndexExclusive(),
                                 Position.newPosition(), true));
        when(trackingClient.storePosition(eq("consumer"), any(), eq(config.getMaxIndexExclusive()))).thenReturn(
                CompletableFuture.completedFuture(null));
        when(trackingClient.disconnectTracker(eq("consumer"), anyString(), eq(false))).thenReturn(
                CompletableFuture.completedFuture(null));

        Registration registration = DefaultTracker.start(messages -> {
        }, config, trackingClient);
        try {
            verify(trackingClient, timeout(1000).atLeastOnce()).getPosition("consumer");
            verify(trackingClient, timeout(1000).atLeastOnce()).readAndWait(anyString(), eq(firstReadIndex),
                                                                            same(config));
        } finally {
            registration.cancel();
        }
    }

    @Test
    void resetPositionRevivesTrackerAfterMaxIndexWasReached() {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder()
                .name("consumer")
                .threads(1)
                .minIndex(IndexUtils.indexFromTimestamp(Instant.parse("2020-01-01T00:00:00Z")))
                .maxIndexExclusive(IndexUtils.indexFromTimestamp(Instant.parse("2021-01-01T00:00:00Z")))
                .build();
        long firstReadIndex = config.getMinIndex() - 1;
        long resetIndex = config.getMaxIndexExclusive() - 2;

        when(trackingClient.getMessageType()).thenReturn(MessageType.EVENT);
        when(trackingClient.getPosition("consumer")).thenReturn(
                new Position(config.getMaxIndexExclusive() - 1),
                new Position(resetIndex));
        when(trackingClient.readAndWait(anyString(), eq(firstReadIndex), same(config))).thenReturn(
                new MessageBatch(new int[]{0, 128}, List.of(), config.getMaxIndexExclusive(),
                                 Position.newPosition(), true));
        when(trackingClient.readAndWait(anyString(), eq(resetIndex), same(config))).thenReturn(
                new MessageBatch(new int[]{0, 128}, List.of(), resetIndex, Position.newPosition(), true));
        when(trackingClient.storePosition(eq("consumer"), any(), eq(config.getMaxIndexExclusive()))).thenReturn(
                CompletableFuture.completedFuture(null));
        when(trackingClient.storePosition(eq("consumer"), any(), eq(resetIndex))).thenReturn(
                CompletableFuture.completedFuture(null));
        when(trackingClient.disconnectTracker(eq("consumer"), anyString(), eq(false))).thenReturn(
                CompletableFuture.completedFuture(null));

        Registration registration = DefaultTracker.start(messages -> {
        }, config, trackingClient);
        try {
            verify(trackingClient, timeout(1000).atLeastOnce()).readAndWait(anyString(), eq(firstReadIndex),
                                                                            same(config));
            verify(trackingClient, timeout(1000)).disconnectTracker(eq("consumer"), anyString(), eq(false));
            verify(trackingClient, timeout(1000).atLeastOnce()).readAndWait(anyString(), eq(resetIndex), same(config));
        } finally {
            registration.cancel();
        }
    }

    @Test
    void storePositionPreservesInterruptAndDoesNotRetryInterruptedWait() throws Exception {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("consumer").build();
        Tracker tracker = new Tracker("trackerId", MessageType.EVENT, null, config, null);
        DefaultTracker defaultTracker = createTracker(trackingClient, config, tracker);
        setRunning(defaultTracker, true);

        when(trackingClient.storePosition(eq("consumer"), any(), eq(42L))).thenReturn(new InterruptingFuture());

        Method method = DefaultTracker.class.getDeclaredMethod("storePosition", Long.class, int[].class);
        method.setAccessible(true);

        try {
            InvocationTargetException error = org.junit.jupiter.api.Assertions.assertThrows(
                    InvocationTargetException.class, () -> method.invoke(defaultTracker, 42L, new int[]{0, 128}));

            assertInstanceOf(io.fluxzero.sdk.tracking.TrackingException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(trackingClient, times(1)).storePosition(eq("consumer"), any(), eq(42L));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void storePositionStopsQuietlyWhenInterruptedAfterShutdown() throws Exception {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("consumer").build();
        Tracker tracker = new Tracker("trackerId", MessageType.EVENT, null, config, null);
        DefaultTracker defaultTracker = createTracker(trackingClient, config, tracker);
        setRunning(defaultTracker, false);

        when(trackingClient.storePosition(eq("consumer"), any(), eq(42L))).thenReturn(new InterruptingFuture());

        Method method = DefaultTracker.class.getDeclaredMethod("storePosition", Long.class, int[].class);
        method.setAccessible(true);

        try {
            method.invoke(defaultTracker, 42L, new int[]{0, 128});

            assertTrue(Thread.currentThread().isInterrupted());
            verify(trackingClient, times(1)).storePosition(eq("consumer"), any(), eq(42L));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shutdownInterruptWhileFetchingStopsTrackerWithoutUncaughtException() throws Exception {
        TrackingClient trackingClient = mock(TrackingClient.class);
        ConsumerConfiguration config = ConsumerConfiguration.builder().name("consumer").build();
        Tracker tracker = new Tracker("trackerId", MessageType.EVENT, null, config, null);
        DefaultTracker defaultTracker = createTracker(trackingClient, config, tracker);
        CountDownLatch fetching = new CountDownLatch(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();

        when(trackingClient.getPosition("consumer")).thenReturn(new Position(-1L));
        when(trackingClient.readAndWait(anyString(), any(), same(config))).thenAnswer(invocation -> {
            fetching.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                return null;
            } catch (InterruptedException e) {
                throw e;
            }
        });

        Thread trackerThread = new Thread(defaultTracker, "test-tracker");
        trackerThread.setUncaughtExceptionHandler((thread, error) -> uncaught.set(error));
        trackerThread.start();

        assertTrue(fetching.await(1, TimeUnit.SECONDS));
        defaultTracker.cancel();
        trackerThread.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(trackerThread.isAlive());
        assertNull(uncaught.get());
    }

    @SuppressWarnings("unchecked")
    private static DefaultTracker createTracker(TrackingClient trackingClient, ConsumerConfiguration config,
                                                Tracker tracker) throws Exception {
        Constructor<DefaultTracker> constructor = DefaultTracker.class.getDeclaredConstructor(
                java.util.function.Consumer.class, ConsumerConfiguration.class, Tracker.class, TrackingClient.class);
        constructor.setAccessible(true);
        return constructor.newInstance((java.util.function.Consumer<List<io.fluxzero.common.api.SerializedMessage>>) m -> {
        }, config, tracker, trackingClient);
    }

    private static void setRunning(DefaultTracker tracker, boolean value) throws Exception {
        Field field = DefaultTracker.class.getDeclaredField("running");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(tracker)).set(value);
    }

    private static class InterruptingFuture extends CompletableFuture<Void> {
        @Override
        public Void get() throws InterruptedException {
            throw new InterruptedException("stop");
        }
    }
}
