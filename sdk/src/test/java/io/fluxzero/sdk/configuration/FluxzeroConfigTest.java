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

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;
import io.fluxzero.sdk.publishing.dataprotection.MissingProtectedDataPolicy;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.ConsumerHandlingMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.QUERY;
import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.sdk.tracking.ConsumerHandlingMode.ASYNC;
import static io.fluxzero.sdk.tracking.ConsumerHandlingMode.SYNC;
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
        FluxzeroConfiguration configuration = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        "fluxzero.maxPublicationDepth", "37")).andThen(existing));

        assertEquals(37, configuration.maxPublicationDepth());
    }

    @Test
    void missingProtectedDataPolicyCanBeConfiguredByProperty() {
        FluxzeroConfiguration configuration = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        MissingProtectedDataPolicy.PROPERTY, "warn")).andThen(existing));

        assertEquals(MissingProtectedDataPolicy.WARN, configuration.onMissingProtectedData());
    }

    @Test
    void explicitMissingProtectedDataPolicyOverridesProperty() {
        FluxzeroConfiguration configuration = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        MissingProtectedDataPolicy.PROPERTY, "skip")).andThen(existing))
                .onMissingProtectedData(MissingProtectedDataPolicy.HANDLE);

        assertEquals(MissingProtectedDataPolicy.HANDLE, configuration.onMissingProtectedData());
    }

    @Test
    void defaultMaxFetchBytesCanBeConfiguredByPropertySource() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .addConsumerConfiguration(ConsumerConfiguration.builder().name("custom").build(), COMMAND)
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ConsumerConfiguration.MAX_FETCH_BYTES_PROPERTY, "4096")).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertEquals(ConsumerConfiguration.USE_DEFAULT_MAX_FETCH_BYTES,
                         fluxzero.configuration().defaultConsumerConfigurations()
                                 .get(COMMAND).getMaxFetchBytes());
            assertEquals(ConsumerConfiguration.USE_DEFAULT_MAX_FETCH_BYTES,
                         fluxzero.configuration().customConsumerConfigurations()
                                 .get(COMMAND).getFirst().getMaxFetchBytes());
            long defaultMaxFetchBytes = fluxzero.apply(fc -> fc.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).effectiveMaxFetchBytes());
            long customMaxFetchBytes = fluxzero.apply(fc -> fc.configuration().customConsumerConfigurations()
                    .get(COMMAND).getFirst().effectiveMaxFetchBytes());
            assertEquals(4096L, defaultMaxFetchBytes);
            assertEquals(4096L, customMaxFetchBytes);
        }
    }

    @Test
    void explicitMaxFetchBytesOverridesPropertySourceDefault() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .configureDefaultConsumer(COMMAND, c -> c.toBuilder().maxFetchBytes(0L).build())
                .addConsumerConfiguration(
                        ConsumerConfiguration.builder().name("custom").maxFetchBytes(8192L).build(), COMMAND)
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ConsumerConfiguration.MAX_FETCH_BYTES_PROPERTY, "4096")).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertEquals(0L, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).getMaxFetchBytes());
            assertEquals(8192L, fluxzero.configuration().customConsumerConfigurations()
                    .get(COMMAND).getFirst().getMaxFetchBytes());
            long defaultMaxFetchBytes = fluxzero.apply(fc -> fc.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).effectiveMaxFetchBytes());
            long customMaxFetchBytes = fluxzero.apply(fc -> fc.configuration().customConsumerConfigurations()
                    .get(COMMAND).getFirst().effectiveMaxFetchBytes());
            assertEquals(0L, defaultMaxFetchBytes);
            assertEquals(8192L, customMaxFetchBytes);
        }
    }

    @Test
    void compatibilityDefaultsResolveConsumerHandlingModeToSync() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of()))
                .build(LocalClient.newInstance())) {
            assertEquals(SYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(WEBREQUEST).getHandlingMode());
            assertEquals(SYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).getHandlingMode());
        }
    }

    @Test
    void defaultsVersionEnablesAsyncWebRequestHandling() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.20")).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertEquals(ASYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(WEBREQUEST).getHandlingMode());
            assertEquals(SYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).getHandlingMode());
        }
    }

    @Test
    void appDefaultConsumerHandlingModeCanBeConfiguredByProperty() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ConsumerConfiguration.DEFAULT_HANDLING_MODE_PROPERTY, "async")).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertEquals(ASYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(EVENT).getHandlingMode());
            assertEquals(ASYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(WEBREQUEST).getHandlingMode());
        }
    }

    @Test
    void messageTypeDefaultConsumerHandlingModeCanBeConfiguredByProperty() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ConsumerConfiguration.DEFAULT_HANDLING_MODE_PROPERTY, "async",
                        ConsumerConfiguration.defaultHandlingModeProperty(COMMAND), "sync",
                        ConsumerConfiguration.DEFAULT_HANDLING_MODE_BY_MESSAGE_TYPE_PROPERTY_PREFIX
                        + "WEBREQUEST", "sync")).andThen(existing))
                .build(LocalClient.newInstance())) {
            assertEquals(SYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).getHandlingMode());
            assertEquals(SYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(WEBREQUEST).getHandlingMode());
            assertEquals(ASYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(QUERY).getHandlingMode());
        }
    }

    @Test
    void messageTypeDefaultConsumerHandlingModeOverridesAppDefault() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .configureDefaultConsumerHandlingMode(ASYNC)
                .configureDefaultConsumer(COMMAND, c -> c.toBuilder().handlingMode(SYNC).build())
                .build(LocalClient.newInstance())) {
            assertEquals(SYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).getHandlingMode());
            assertEquals(ASYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(QUERY).getHandlingMode());
        }
    }

    @Test
    void defaultConsumerHandlingModeCanBeConfiguredForSpecificMessageTypes() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .configureDefaultConsumerHandlingMode(ASYNC, WEBREQUEST, QUERY)
                .build(LocalClient.newInstance())) {
            assertEquals(ASYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(WEBREQUEST).getHandlingMode());
            assertEquals(ASYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(QUERY).getHandlingMode());
            assertEquals(SYNC, fluxzero.configuration().defaultConsumerConfigurations()
                    .get(COMMAND).getHandlingMode());
        }
    }

    @Test
    void customConsumerDefaultHandlingModeInheritsMessageTypeDefault() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.20")).andThen(existing))
                .addConsumerConfiguration(ConsumerConfiguration.builder().name("web-custom").build(), WEBREQUEST)
                .addConsumerConfiguration(ConsumerConfiguration.builder().name("command-custom").build(), COMMAND)
                .build(LocalClient.newInstance())) {
            assertEquals(ConsumerHandlingMode.DEFAULT, ConsumerConfiguration.builder().name("raw").build()
                    .getHandlingMode());
            assertEquals(ASYNC, fluxzero.configuration().customConsumerConfigurations()
                    .get(WEBREQUEST).getFirst().getHandlingMode());
            assertEquals(SYNC, fluxzero.configuration().customConsumerConfigurations()
                    .get(COMMAND).getFirst().getHandlingMode());
        }
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
