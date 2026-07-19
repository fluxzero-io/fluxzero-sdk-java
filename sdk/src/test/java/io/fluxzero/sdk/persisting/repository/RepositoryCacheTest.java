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

import io.fluxzero.common.caching.AdaptiveObjectCache;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryCacheTest {

    @Test
    void invokesListenerOnlyForFirstNonNullCacheWrite() {
        AdaptiveObjectCache delegate = new AdaptiveObjectCache();
        try {
            RepositoryCache cache = new RepositoryCache(delegate, "aggregate", "tenant");
            AtomicInteger invocations = new AtomicInteger();
            cache.onFirstWrite(() -> 42L, ignored -> invocations.incrementAndGet());

            cache.compute("one", (key, value) -> null);
            assertEquals(0, invocations.get());
            cache.compute("one", (key, value) -> "value");
            cache.put("two", "value");

            assertEquals(1, invocations.get());
        } finally {
            delegate.close();
        }
    }

    @Test
    void retainsReplayIndexCapturedBeforeFirstCacheLoad() {
        AdaptiveObjectCache delegate = new AdaptiveObjectCache();
        try {
            RepositoryCache cache = new RepositoryCache(delegate, "aggregate", "tenant");
            AtomicLong observedIndex = new AtomicLong();
            AtomicLong currentIndex = new AtomicLong(42L);
            cache.onFirstWrite(currentIndex::get, observedIndex::set);

            cache.computeIfAbsent("one", ignored -> {
                currentIndex.set(84L);
                return "value";
            });

            assertEquals(42L, observedIndex.get());
        } finally {
            delegate.close();
        }
    }

    @Test
    void invokesLateListenerWithoutScanningCache() {
        AdaptiveObjectCache delegate = new AdaptiveObjectCache();
        try {
            RepositoryCache cache = new RepositoryCache(delegate, "aggregate", "tenant");
            AtomicInteger invocations = new AtomicInteger();
            cache.put("one", "value");

            cache.onFirstWrite(() -> 42L, ignored -> invocations.incrementAndGet());

            assertEquals(1, invocations.get());
        } finally {
            delegate.close();
        }
    }
}
