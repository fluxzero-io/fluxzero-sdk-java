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

package io.fluxzero.sdk.persisting.caching;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.fluxzero.common.TestUtils.callWithSystemProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DefaultCacheTest {
    private final List<Cache> caches = new ArrayList<>();

    @AfterEach
    void tearDown() {
        caches.forEach(Cache::close);
    }

    @Test
    void usesSoftReferenceCacheWithoutDefaultsVersion() {
        DefaultCache cache = cache(new DefaultCache(new SimplePropertySource(Map.of())));

        assertInstanceOf(SoftReferenceCache.class, cache.delegate());
    }

    @Test
    void usesAdaptiveCacheForNewDefaultsVersion() {
        DefaultCache cache = cache(new DefaultCache(new SimplePropertySource(Map.of(
                ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.05.25"))));

        assertInstanceOf(AdaptiveObjectCache.class, cache.delegate());
    }

    @Test
    void softReferenceModeOverridesNewDefaultsVersion() {
        DefaultCache cache = cache(new DefaultCache(new SimplePropertySource(Map.of(
                ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.05.25",
                DefaultCache.MODE_PROPERTY, DefaultCache.MODE_SOFT_REFERENCE))));

        assertInstanceOf(SoftReferenceCache.class, cache.delegate());
    }

    @Test
    void constructorsRemainAvailable() {
        assertNotNull(cache(new DefaultCache()));
        assertNotNull(cache(new DefaultCache(new SimplePropertySource(Map.of()))));
        assertNotNull(cache(new DefaultCache(1)));
        assertNotNull(cache(new DefaultCache(1, Duration.ofSeconds(1))));
    }

    @Test
    void rebuildKeepsDefaultCacheCompatibility() {
        DefaultCache cache = cache(new DefaultCache(new SimplePropertySource(Map.of(
                DefaultCache.MODE_PROPERTY, DefaultCache.MODE_SOFT_REFERENCE))));

        Cache rebuilt = cache.rebuild();
        caches.add(rebuilt);

        DefaultCache defaultCache = assertInstanceOf(DefaultCache.class, rebuilt);
        assertEquals(cache.delegate().getClass(), defaultCache.delegate().getClass());
    }

    @Test
    void operationsDelegateToSelectedCache() {
        DefaultCache cache = cache(new DefaultCache(new SimplePropertySource(Map.of(
                DefaultCache.MODE_PROPERTY, DefaultCache.MODE_ADAPTIVE))));

        assertEquals("bar", cache.computeIfAbsent("foo", key -> "bar"));
        assertEquals("bar", cache.get("foo"));
    }

    @Test
    void durationConstructorAppliesExpiryToAdaptiveCache() {
        DefaultCache cache = cache(callWithSystemProperties(() -> new DefaultCache(1, Duration.ofNanos(-1)),
                                                           DefaultCache.MODE_PROPERTY, DefaultCache.MODE_ADAPTIVE));

        assertInstanceOf(AdaptiveObjectCache.class, cache.delegate());
        cache.put("foo", "bar");

        assertNull(cache.get("foo"));
    }

    private DefaultCache cache(DefaultCache cache) {
        caches.add(cache);
        return cache;
    }
}
