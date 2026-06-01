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

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.QUERY;
import static io.fluxzero.common.TestUtils.callWithSystemProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FluxzeroConfigTest {

    @Test
    void testAddConsumerWithExistingNameNotAllowed() {
        ConsumerConfiguration config1 =
                ConsumerConfiguration.builder().name("test").build();
        ConsumerConfiguration config2 =
                ConsumerConfiguration.builder().name("test").build();
        assertThrows(IllegalArgumentException.class, () -> DefaultFluxzero.builder()
                .addConsumerConfiguration(config1, QUERY)
                .addConsumerConfiguration(config2, QUERY));
    }

    @Test
    void testAddConsumerWithExistingNameAllowedIfDifferentMessageType() {
        ConsumerConfiguration config1 =
                ConsumerConfiguration.builder().name("test").build();
        ConsumerConfiguration config2 =
                ConsumerConfiguration.builder().name("test").build();
        DefaultFluxzero.builder()
                .addConsumerConfiguration(config1, QUERY)
                .addConsumerConfiguration(config2, COMMAND);
    }

    @Test
    void publicationDepthIsExposedByConfiguration() {
        FluxzeroConfiguration configuration = DefaultFluxzero.builder().setMaxPublicationDepth(42);

        assertEquals(42, configuration.maxPublicationDepth());
    }

    @Test
    void publicationDepthDefaultCanBeConfiguredByProperty() {
        FluxzeroConfiguration configuration = callWithSystemProperties(
                DefaultFluxzero::builder, "fluxzero.maxPublicationDepth", "37");

        assertEquals(37, configuration.maxPublicationDepth());
    }

    @Test
    void compatibilityDefaultsUseSoftReferenceCache() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of()))
                .build(LocalClient.newInstance())) {
            assertDefaultCacheDelegate(fluxzero.cache(), SoftReferenceCache.class);
        }
    }

    @Test
    void compatibilityDefaultsUseSoftReferenceRelationshipsCache() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of()))
                .build(LocalClient.newInstance())) {
            assertDefaultCacheDelegate(fluxzero.configuration().relationshipsCache(), SoftReferenceCache.class);
        }
    }

    @Test
    void adaptiveCacheCanBeEnabledExplicitly() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        DefaultCache.MODE_PROPERTY, DefaultCache.MODE_ADAPTIVE)).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertDefaultCacheDelegate(fluxzero.cache(), AdaptiveObjectCache.class);
        }
    }

    @Test
    void defaultsVersionEnablesAdaptiveCache() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.05.25")).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertDefaultCacheDelegate(fluxzero.cache(), AdaptiveObjectCache.class);
        }
    }

    @Test
    void defaultsVersionEnablesAdaptiveRelationshipsCache() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.05.25")).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertDefaultCacheDelegate(fluxzero.configuration().relationshipsCache(), AdaptiveObjectCache.class);
        }
    }

    @Test
    void softRefCacheModeOverridesDefaultsVersion() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.05.25",
                        DefaultCache.MODE_PROPERTY, DefaultCache.MODE_SOFT_REFERENCE)).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertDefaultCacheDelegate(fluxzero.cache(), SoftReferenceCache.class);
        }
    }

    @Test
    void cacheModePropertyRequiresSupportedValue() {
        assertThrows(IllegalArgumentException.class, () -> DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        DefaultCache.MODE_PROPERTY, "sometimes")).andThen(existing))
                .build(LocalClient.newInstance()));
    }

    @Test
    void defaultsVersionEnablesAdaptiveCacheUnlessCacheWasConfiguredExplicitly() {
        Cache customCache = new DefaultCache(1);
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.05.25")).andThen(existing))
                .replaceCache(customCache)
                .build(LocalClient.newInstance())) {
            assertEquals(customCache, fluxzero.cache());
        }
    }

    private static <T extends Cache> T assertDefaultCacheDelegate(Cache cache, Class<T> delegateType) {
        DefaultCache defaultCache = assertInstanceOf(DefaultCache.class, cache);
        return assertInstanceOf(delegateType, defaultCache.delegate());
    }

}
