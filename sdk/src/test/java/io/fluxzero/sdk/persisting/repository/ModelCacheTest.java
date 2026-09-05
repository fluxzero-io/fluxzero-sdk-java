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
package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ModelCacheTest {
    @Test
    void serializingCacheDoesNotRequireEntityIdentity() {
        JacksonSerializer serializer = new JacksonSerializer();
        SoftReferenceCache physical = new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public <T> T get(Object id) {
                T value = super.get(id);
                return value == null ? null : serializer.deserialize(serializer.serialize(value));
            }
        };
        ModelCache cache = new ModelCache(physical);
        try {
            Entity<?> value = ImmutableModelRoot.<String>builder()
                    .id("id").type(String.class).value("headless").stateIndex(-1).build();
            ModelCache.Stamp stamp;
            try (var token = cache.beginRead("id")) {
                stamp = cache.publish(token, value, 12);
            }
            Entity<?> read = cache.get("id", stamp);
            assertNotSame(value, read);
            assertEquals("headless", read.get());
            assertEquals(12, stamp.boundary);
            assertTrue(cache.isCurrent(stamp));
        } finally {
            cache.close();
        }
    }

    @Test
    void readRechecksPublicationAfterDelegateGet() throws Exception {
        CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean hold = new AtomicBoolean();
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public <T> T get(Object id) {
                T value = super.get(id);
                if (hold.compareAndSet(true, false)) {
                    captured.countDown();
                    await(release);
                }
                return value;
            }
        });
        try {
            cache.put("id", "before");
            hold.set(true);
            CompletableFuture<Object> read = async(() -> cache.get("id"));
            await(captured);
            cache.put("id", "after");
            release.countDown();
            assertNull(read.get(5, TimeUnit.SECONDS));
            assertEquals("after", cache.get("id"));
        } finally {
            release.countDown();
            cache.close();
        }
    }

    @Test
    void overlappingWritersRemainInvisibleUntilBothHaveFinished() throws Exception {
        CountDownLatch installed = new CountDownLatch(1), release = new CountDownLatch(1);
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public Object put(Object id, Object value) {
                Object previous = super.put(id, value);
                if ("first".equals(value)) {
                    installed.countDown();
                    await(release);
                }
                return previous;
            }
        });
        try {
            CompletableFuture<Object> first = async(() -> cache.put("id", "first"));
            await(installed);
            cache.put("id", "second");
            assertNull(cache.get("id"));
            cache.invalidate("id");
            try (var replacement = cache.beginRead("id")) {
                assertNull(cache.publish(replacement, "third", 12));
            }
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertNull(cache.get("id"));
            ModelCache.Stamp accepted = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (accepted == null && System.nanoTime() < deadline) {
                try (var replacement = cache.beginRead("id")) {
                    accepted = cache.publish(replacement, "fourth", 13);
                }
                if (accepted == null) {
                    Thread.sleep(1);
                }
            }
            assertNotNull(accepted, "The entry must become cacheable again after overlapping cleanup finishes");
            assertEquals("fourth", cache.get("id"));
        } finally {
            release.countDown();
            cache.close();
        }
    }

    @Test
    void crossWorkerInvalidationDoesNotWaitForTheOwningCacheOperation() throws Exception {
        Object cacheLock = new Object();
        AtomicBoolean invalidate = new AtomicBoolean(true);
        SoftReferenceCache physical = new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public <U, T> void updateAll(Iterable<? extends U> updates, Function<? super U, ?> keyFunction,
                                       BiFunction<? super U, ? super T, ? extends T> updateFunction) {
                synchronized (cacheLock) {
                    async(() -> {
                        super.updateAll(updates, keyFunction, updateFunction);
                        return null;
                    }).join();
                }
            }

            @Override
            public <T> void modifyEach(BiFunction<? super Object, ? super T, ? extends T> modifier) {
                synchronized (cacheLock) {
                    super.modifyEach(modifier);
                }
            }
        };
        ModelCache cache = new ModelCache(physical);
        try {
            CompletableFuture<Object> update = async(() -> {
                cache.<String, String>updateAll(List.of("id"), Function.identity(), (id, current) -> {
                    if (invalidate.compareAndSet(true, false)) {
                        cache.invalidateAll();
                        cache.clear();
                    }
                    return "obsolete";
                });
                return null;
            });
            update.get(5, TimeUnit.SECONDS);
            assertNull(cache.get("id"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (physical.containsKey("id") && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertFalse(physical.containsKey("id"));
        } finally {
            cache.close();
        }
    }

    @Test
    void orderedBulkUpdatesWithRepeatedKeysKeepTheirIntermediateValue() {
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null));
        try {
            cache.<Integer, Integer>updateAll(List.of(1, 2, 3), ignored -> "id",
                                             (increment, current) -> (current == null ? 0 : current) + increment);
            assertEquals(6, (Integer) cache.get("id"));
        } finally {
            cache.close();
        }
    }

    @Test
    void sharedPhysicalCacheAndNamespaceHaveOnePublicationOwner() {
        SoftReferenceCache physical = new SoftReferenceCache(100, Runnable::run, null);
        try {
            ModelCache first = ModelCache.shared(physical, "a");
            ModelCache second = ModelCache.shared(physical, "a");
            ModelCache other = ModelCache.shared(physical, "b");
            assertSame(first, second);
            assertNotSame(first, other);
            try (var oldRead = first.beginRead("id")) {
                second.put("id", "current");
                assertNull(first.publish(oldRead, "old", 10));
            }
            assertEquals("current", second.get("id"));
            assertNull(other.get("id"));
        } finally {
            physical.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void physicalCleanupAlsoCoversWritesThatStartAfterTheClear(boolean cleanupFinishesLast) throws Exception {
        CountDownLatch beforePut = new CountDownLatch(1), proceed = new CountDownLatch(1);
        CountDownLatch physicallyCleaned = new CountDownLatch(1), completeCleanup = new CountDownLatch(1);
        SoftReferenceCache physical = new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public Object put(Object id, Object value) {
                if ("obsolete".equals(value)) {
                    beforePut.countDown();
                    await(proceed);
                }
                return super.put(id, value);
            }

            @Override
            public <T> void modifyEach(BiFunction<? super Object, ? super T, ? extends T> modifier) {
                super.modifyEach(modifier);
                physicallyCleaned.countDown();
                if (cleanupFinishesLast) {
                    await(completeCleanup);
                }
            }
        };
        ModelCache cache = new ModelCache(physical);
        try {
            cache.put("id", "initial");
            CompletableFuture<Object> writer = async(() -> cache.put("id", "obsolete"));
            await(beforePut);
            cache.clear();
            await(physicallyCleaned);
            proceed.countDown();
            writer.get(5, TimeUnit.SECONDS);
            completeCleanup.countDown();
            awaitMissing(physical, "id");
            assertNull(cache.get("id"));
        } finally {
            proceed.countDown();
            completeCleanup.countDown();
            cache.close();
        }
    }

    @Test
    void failedDelegateWriteCannotLeaveAnUntrackedPhysicalValue() throws Exception {
        SoftReferenceCache physical = new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public Object put(Object id, Object value) {
                super.put(id, value);
                throw new IllegalStateException("failed after publication");
            }
        };
        ModelCache cache = new ModelCache(physical);
        try {
            assertThrows(IllegalStateException.class, () -> cache.put("id", "uncertain"));
            assertNull(cache.get("id"));
            awaitMissing(physical, "id");
        } finally {
            cache.close();
        }
    }

    @Test
    void declinedCacheAdmissionsDoNotRetainReadOrPublicationMetadata() throws Exception {
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public Object put(Object id, Object value) {
                return null;
            }
        });
        try {
            for (int index = 0; index < 1_000; index++) {
                try (var token = cache.beginRead("model-" + index)) {
                    assertNull(cache.publish(token, "declined", 10));
                }
            }
            assertEquals(0, trackedKeys(cache));
        } finally {
            cache.close();
        }
    }

    @Test
    void lastRepositoryReleaseInvalidatesReadsAndDoesNotRetainIdleMetadata() throws Exception {
        SoftReferenceCache physical = new SoftReferenceCache(100, Runnable::run, null);
        try {
            ModelCache first = ModelCache.shared(physical, "namespace");
            first.put("id", "old");
            try (var oldRead = first.beginRead("id")) {
                first.releaseShared();
                ModelCache replacement = ModelCache.shared(physical, "namespace");
                assertNull(first.publish(oldRead, "obsolete", 10));
                replacement.put("id", "replacement");
                assertEquals("replacement", replacement.get("id"));
                replacement.releaseShared();
            }
            assertEquals(0, trackedKeys(first));
            ModelCache next = ModelCache.shared(physical, "namespace");
            assertNotSame(first, next);
            assertNull(next.get("id"));
            next.releaseShared();
        } finally {
            physical.close();
        }
    }

    @Test
    void readBegunDuringAWriterCannotPublishAfterThatWriterFinishes() throws Exception {
        CountDownLatch installed = new CountDownLatch(1), finish = new CountDownLatch(1);
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null) {
            @Override
            public Object put(Object id, Object value) {
                Object previous = super.put(id, value);
                if ("new".equals(value)) {
                    installed.countDown();
                    await(finish);
                }
                return previous;
            }
        });
        try {
            CompletableFuture<Object> writer = async(() -> cache.put("id", "new"));
            await(installed);
            try (var read = cache.beginRead("id")) {
                finish.countDown();
                writer.get(5, TimeUnit.SECONDS);
                assertNull(cache.publish(read, "old", 10));
                assertEquals("new", cache.get("id"));
            }
        } finally {
            finish.countDown();
            cache.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1L, 12L})
    void olderReadBoundaryCannotReplaceAnAlreadyAcceptedPublication(long currentState) {
        ModelCache cache = new ModelCache(new SoftReferenceCache(100, Runnable::run, null));
        try {
            Entity<?> current = ImmutableModelRoot.<String>builder().id("id").type(String.class)
                    .value("current").stateIndex(currentState).build();
            Entity<?> old = ImmutableModelRoot.<String>builder().id("id").type(String.class)
                    .value("old").stateIndex(10).build();
            try (var initialRead = cache.beginRead("id")) {
                assertNotNull(cache.publish(initialRead, current, 12));
            }
            try (var olderBoundary = cache.beginRead("id")) {
                assertNull(cache.publish(olderBoundary, old, 10));
            }
            assertSame(current, cache.get("id"));
            if (currentState < 0) {
                cache.compute("id", (id, previous) -> old);
                assertSame(current, cache.get("id"), "A late local commit must also respect headless read proof");
            }
        } finally {
            cache.close();
        }
    }

    @Test
    void registeringAReadPinsTheSharedViewUntilItsStateIsVisible() throws Exception {
        CountDownLatch beforeRegistration = new CountDownLatch(1), register = new CountDownLatch(1);
        AtomicBoolean hold = new AtomicBoolean(true);
        Object id = new Object() {
            @Override
            public int hashCode() {
                if (hold.compareAndSet(true, false)) {
                    beforeRegistration.countDown();
                    await(register);
                }
                return 1;
            }
        };
        SoftReferenceCache physical = new SoftReferenceCache(100, Runnable::run, null);
        try {
            ModelCache first = ModelCache.shared(physical, "namespace");
            CompletableFuture<ModelCache.ReadToken> registration = async(() -> first.beginRead(id));
            await(beforeRegistration);
            first.releaseShared();
            ModelCache replacement = ModelCache.shared(physical, "namespace");
            assertSame(first, replacement, "An admitted operation must retain the shared generation owner");
            register.countDown();
            registration.get(5, TimeUnit.SECONDS).close();
            replacement.releaseShared();
            assertEquals(0, trackedKeys(first));
        } finally {
            register.countDown();
            physical.close();
        }
    }

    private static int trackedKeys(ModelCache cache) throws Exception {
        var field = ModelCache.class.getDeclaredField("states");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(cache)).size();
    }

    private static void awaitMissing(SoftReferenceCache cache, Object id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.containsKey(id) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertFalse(cache.containsKey(id), "obsolete physical value was retained");
    }

    private static <T> CompletableFuture<T> async(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, task -> Thread.ofVirtual().start(task));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
