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
package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.caching.CacheEviction;
import io.fluxzero.sdk.Fluxzero;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Completion barriers for tests that assert cache hits rather than eventual cache admission. */
public final class ModelCacheTestSupport {
    private ModelCacheTestSupport() {}

    public static void awaitCacheTracking(Fluxzero app) {
        if (!await(((DefaultModelRepository) app.modelRepository()).cacheTrackingReadiness())) {
            throw new AssertionError("Model cache tracking is unavailable");
        }
    }

    /** Callers own this cache and issue clears sequentially, so no older clear notification is outstanding. */
    public static void clearCacheAndAwait(Fluxzero app) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Registration listener = app.cache().registerEvictionListener(event -> {
            // Repository listeners were installed during construction and run before this trailing listener.
            if (event.getId() == null && event.getReason() == CacheEviction.Reason.manual) { completed.complete(null); }
        });
        try {
            app.cache().clear();
            await(completed);
        } finally {
            listener.cancel();
        }
    }

    /** A subsequent poll proves the preceding page was fully processed, not merely received. */
    public static void awaitTrackedThrough(BlockingQueue<TrackModelUpdates> polls, long boundary) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                TrackModelUpdates request = polls.poll(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (request == null) { throw new AssertionError("Tracker did not process state " + boundary); }
                if (request.getMaxWaitMillis() > 0 && request.getLastStateIndex() >= boundary) { return; }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while observing tracker completion", failure);
        }
    }

    private static <T> T await(CompletableFuture<T> completed) {
        try {
            return completed.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting cache lifecycle", failure);
        } catch (Exception failure) {
            throw new AssertionError("Cache lifecycle did not complete", failure);
        }
    }
}
