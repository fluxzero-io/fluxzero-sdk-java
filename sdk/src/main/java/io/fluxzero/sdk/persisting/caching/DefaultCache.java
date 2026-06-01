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

package io.fluxzero.sdk.persisting.caching;

import io.fluxzero.common.Registration;
import io.fluxzero.common.application.DefaultPropertySource;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.caching.CacheEviction;
import io.fluxzero.common.caching.MemoryPressureController;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.common.ObjectUtils.newWorkerThreadFactory;

/**
 * Default aggregate cache facade that selects the current SDK cache implementation from Fluxzero cache properties.
 * <p>
 * Existing applications without a defaults version or explicit {@link #MODE_PROPERTY} keep the legacy
 * {@link SoftReferenceCache}. Newer applications that use Fluxzero defaults {@code >= 2026.05.25}, or set
 * {@code fluxzero.cache.mode = adaptive}, use {@link AdaptiveObjectCache}.
 */
public final class DefaultCache implements Cache, AutoCloseable {
    /**
     * Fluxzero defaults version from which {@link DefaultCache} uses {@link AdaptiveObjectCache} by default.
     */
    public static final LocalDate ADAPTIVE_DEFAULTS_VERSION = LocalDate.of(2026, 5, 25);

    /**
     * Property that selects the cache implementation used by {@link DefaultCache}.
     */
    public static final String MODE_PROPERTY = "fluxzero.cache.mode";

    /**
     * {@link #MODE_PROPERTY} value that selects {@link SoftReferenceCache}.
     */
    public static final String MODE_SOFT_REFERENCE = "softRef";

    /**
     * {@link #MODE_PROPERTY} value that selects {@link AdaptiveObjectCache}.
     */
    public static final String MODE_ADAPTIVE = "adaptive";

    /**
     * Property that configures the maximum entry count for {@link AdaptiveObjectCache} when selected by
     * {@link DefaultCache}.
     */
    public static final String MAX_SIZE_PROPERTY = "fluxzero.cache.maxSize";

    private static final int DEFAULT_MAX_SIZE = 1_000_000;
    private static final DateTimeFormatter DEFAULTS_VERSION_FORMAT = DateTimeFormatter.ofPattern("uuuu.MM.dd");

    private final Cache delegate;

    /**
     * Constructs a cache using the active Fluxzero property source, or the default property source when no Fluxzero
     * instance is active.
     */
    public DefaultCache() {
        this(currentPropertySource());
    }

    /**
     * Constructs a cache using the given property source for cache mode and defaults-version selection.
     */
    public DefaultCache(PropertySource propertySource) {
        this(propertySource, () -> newSoftReferenceCache(DEFAULT_MAX_SIZE), AdaptiveObjectCache.DEFAULT_MAX_SIZE);
    }

    /**
     * Constructs a cache using the given property source for cache mode and defaults-version selection, with a custom
     * max size for the selected implementation.
     */
    public DefaultCache(PropertySource propertySource, int maxSize) {
        this(propertySource, () -> newSoftReferenceCache(maxSize), maxSize);
    }

    /**
     * Constructs a cache with a specified max size for the selected implementation.
     */
    public DefaultCache(int maxSize) {
        this(currentPropertySource(), () -> newSoftReferenceCache(maxSize), maxSize);
    }

    /**
     * Constructs a cache with specified size and expiration for the selected implementation.
     */
    public DefaultCache(int maxSize, Duration expiry) {
        this(currentPropertySource(), () -> new SoftReferenceCache(maxSize, defaultEvictionNotifier(), expiry),
             maxSize, expiry);
    }

    private DefaultCache(PropertySource propertySource, Supplier<? extends Cache> softReferenceCacheSupplier,
                         int adaptiveDefaultMaxSize) {
        this(propertySource, softReferenceCacheSupplier, adaptiveDefaultMaxSize, null);
    }

    private DefaultCache(PropertySource propertySource, Supplier<? extends Cache> softReferenceCacheSupplier,
                         int adaptiveDefaultMaxSize, Duration expiry) {
        this(selectDelegate(propertySource, softReferenceCacheSupplier, adaptiveDefaultMaxSize, expiry));
    }

    private DefaultCache(Cache delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the selected cache implementation.
     */
    public Cache delegate() {
        return delegate;
    }

    @Override
    public Object put(Object id, Object value) {
        return delegate.put(id, value);
    }

    @Override
    public Object putIfAbsent(Object id, Object value) {
        return delegate.putIfAbsent(id, value);
    }

    @Override
    public <T> T computeIfAbsent(Object id, Function<? super Object, T> mappingFunction) {
        return delegate.computeIfAbsent(id, mappingFunction);
    }

    @Override
    public <T> T computeIfPresent(Object id, BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        return delegate.computeIfPresent(id, mappingFunction);
    }

    @Override
    public <T> T compute(Object id, BiFunction<? super Object, ? super T, ? extends T> mappingFunction) {
        return delegate.compute(id, mappingFunction);
    }

    @Override
    public <T> void modifyEach(BiFunction<? super Object, ? super T, ? extends T> modifierFunction) {
        delegate.modifyEach(modifierFunction);
    }

    @Override
    public <T> T get(Object id) {
        return delegate.get(id);
    }

    @Override
    public boolean containsKey(Object id) {
        return delegate.containsKey(id);
    }

    @Override
    public <T> T remove(Object id) {
        return delegate.remove(id);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Registration registerEvictionListener(Consumer<CacheEviction> listener) {
        return delegate.registerEvictionListener(listener);
    }

    @Override
    public Cache rebuild() {
        return new DefaultCache(delegate.rebuild());
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static Cache selectDelegate(PropertySource propertySource,
                                        Supplier<? extends Cache> softReferenceCacheSupplier,
                                        int adaptiveDefaultMaxSize, Duration expiry) {
        Objects.requireNonNull(propertySource, "propertySource");
        Objects.requireNonNull(softReferenceCacheSupplier, "softReferenceCacheSupplier");
        String mode = propertySource.get(MODE_PROPERTY);
        if (mode != null) {
            if (mode.trim().equalsIgnoreCase(MODE_SOFT_REFERENCE)) {
                return softReferenceCacheSupplier.get();
            }
            if (mode.trim().equalsIgnoreCase(MODE_ADAPTIVE)) {
                return newAdaptiveCache(propertySource, adaptiveDefaultMaxSize, expiry);
            }
            throw new IllegalArgumentException("Property `%s` must be `%s` or `%s`, but found `%s`"
                                                       .formatted(MODE_PROPERTY, MODE_SOFT_REFERENCE,
                                                                  MODE_ADAPTIVE, mode));
        }
        return defaultsVersionUsesAdaptiveCache(propertySource)
                ? newAdaptiveCache(propertySource, adaptiveDefaultMaxSize, expiry)
                : softReferenceCacheSupplier.get();
    }

    private static Cache newAdaptiveCache(PropertySource propertySource, int defaultMaxSize, Duration expiry) {
        return new AdaptiveObjectCache(propertySource.getInteger(MAX_SIZE_PROPERTY, defaultMaxSize),
                                       MemoryPressureController.jvm(propertySource), expiry, Fluxzero.currentClock());
    }

    private static boolean defaultsVersionUsesAdaptiveCache(PropertySource propertySource) {
        return Optional.ofNullable(defaultsVersion(propertySource))
                .map(version -> !version.isBefore(ADAPTIVE_DEFAULTS_VERSION)).orElse(false);
    }

    private static LocalDate defaultsVersion(PropertySource propertySource) {
        String value = propertySource.get(ApplicationProperties.DEFAULTS_VERSION_PROPERTY);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DEFAULTS_VERSION_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Property `%s` must use format `yyyy.MM.dd`, but found `%s`"
                                                       .formatted(ApplicationProperties.DEFAULTS_VERSION_PROPERTY,
                                                                  value), e);
        }
    }

    private static SoftReferenceCache newSoftReferenceCache(int maxSize) {
        return new SoftReferenceCache(maxSize, defaultEvictionNotifier(), null);
    }

    private static Executor defaultEvictionNotifier() {
        return runnable -> newWorkerThreadFactory("DefaultCache-evictionNotifier-").newThread(runnable).start();
    }

    private static PropertySource currentPropertySource() {
        return Fluxzero.getOptionally().map(Fluxzero::propertySource).orElseGet(DefaultPropertySource::getInstance);
    }
}
