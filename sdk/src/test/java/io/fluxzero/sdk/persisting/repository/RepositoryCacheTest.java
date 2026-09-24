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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void bulkMergePreservesNamespaceAndFirstWriteTracking() {
        AdaptiveObjectCache delegate = new AdaptiveObjectCache();
        try {
            RepositoryCache first =
                    new RepositoryCache(
                            delegate, "model", "first");
            RepositoryCache second =
                    new RepositoryCache(
                            delegate, "model", "second");
            AtomicInteger invocations =
                    new AtomicInteger();
            first.onFirstWrite(
                    () -> 42L,
                    ignored -> invocations.incrementAndGet());

            first.mergeAll(
                    Map.of("same", "first-value"),
                    (current, candidate) -> candidate);
            second.mergeAll(
                    Map.of("same", "second-value"),
                    (current, candidate) -> candidate);

            assertEquals(
                    "first-value",
                    first.get("same"));
            assertEquals(
                    "second-value",
                    second.get("same"));
            assertEquals(1, invocations.get());
        } finally {
            delegate.close();
        }
    }

    @Test
    void equivalentScopeStringsShareTheSameDelegateEntry() {
        AdaptiveObjectCache delegate =
                new AdaptiveObjectCache();
        try {
            RepositoryCache writer =
                    new RepositoryCache(
                            delegate,
                            new String("model"),
                            new String("tenant"));
            RepositoryCache reader =
                    new RepositoryCache(
                            delegate,
                            new String("model"),
                            new String("tenant"));

            writer.put("same", "value");

            assertEquals(
                    "value",
                    reader.get("same"));
        } finally {
            delegate.close();
        }
    }

    @Test
    void orderedBulkUpdateDoesNotRetainItsReusableLookupKey() {
        AdaptiveObjectCache delegate =
                new AdaptiveObjectCache();
        try {
            RepositoryCache cache =
                    new RepositoryCache(
                            delegate, "model", "tenant");
            cache.put("replace", "old");
            cache.put("remove", "old");

            cache.<Map.Entry<String, String>, String>updateAll(
                    List.of(
                            Map.entry("replace", "old-new"),
                            Map.entry("add", "added"),
                            Map.entry("remove", "removed")),
                    Map.Entry::getKey,
                    (update, current) ->
                            "remove".equals(update.getKey())
                                    ? null : update.getValue());

            assertEquals("old-new", cache.get("replace"));
            assertEquals("added", cache.get("add"));
            assertNull(cache.get("remove"));
        } finally {
            delegate.close();
        }
    }

    @Test
    void reusesReadKeysWithoutBreakingReentrantCacheAccess() {
        ReentrantAdaptiveCache delegate =
                new ReentrantAdaptiveCache();
        try {
            RepositoryCache cache =
                    new RepositoryCache(
                            delegate, "model", "tenant");
            delegate.repositoryCache = cache;
            cache.put("outer", "outer-value");
            cache.put("nested", "nested-value");
            delegate.reenter = true;

            assertEquals("outer-value", cache.get("outer"));
            Object firstLookupKey = delegate.outerLookupKey;

            assertEquals("nested-value", delegate.nestedValue);
            assertNotSame(
                    delegate.outerLookupKey,
                    delegate.nestedLookupKey);
            assertEquals(0, firstLookupKey.hashCode());

            assertEquals("outer-value", cache.get("outer"));
            assertSame(firstLookupKey, delegate.outerLookupKey);
        } finally {
            delegate.close();
        }
    }

    private static final class ReentrantAdaptiveCache extends AdaptiveObjectCache {
        private RepositoryCache repositoryCache;
        private boolean reenter;
        private boolean nested;
        private Object outerLookupKey;
        private Object nestedLookupKey;
        private Object nestedValue;

        @Override
        public <T> T get(Object id) {
            if (reenter && !nested) {
                outerLookupKey = id;
                nested = true;
                try {
                    nestedValue = repositoryCache.get("nested");
                } finally {
                    nested = false;
                }
            } else if (nested) {
                nestedLookupKey = id;
            }
            return super.get(id);
        }
    }
}
