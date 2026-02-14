/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

import io.fluxzero.common.application.ApplicationEnvironmentPropertiesSource;
import io.fluxzero.common.application.ApplicationPropertiesSource;
import io.fluxzero.common.application.DecryptingPropertySource;
import io.fluxzero.common.application.DefaultPropertySource;
import io.fluxzero.common.application.EnvironmentVariablesSource;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.application.SystemPropertiesSource;
import io.fluxzero.common.encryption.Encryption;
import io.fluxzero.sdk.Fluxzero;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Central utility for resolving configuration properties within a Fluxzero application.
 *
 * <p>This class delegates to a layered {@link PropertySource}, typically obtained from the active
 * {@link io.fluxzero.sdk.Fluxzero} instance. If no context-bound property source is present, it falls
 * back to a {@link DecryptingPropertySource} that wraps the default layered {@link DefaultPropertySource}.
 *
 * <p>Property sources are accessed in a prioritized order:
 * <ol>
 *   <li>{@link EnvironmentVariablesSource} – highest precedence</li>
 *   <li>{@link SystemPropertiesSource}</li>
 *   <li>{@link ApplicationEnvironmentPropertiesSource} – e.g. application-dev.properties</li>
 *   <li>{@link ApplicationPropertiesSource} – fallback base configuration from application.properties</li>
 * </ol>
 *
 * <p>Property resolution supports typed access, default values, encryption, and template substitution.
 *
 * <p>Common usage:
 * <pre>{@code
 * String token = ApplicationProperties.getProperty("FLUXZERO_API_TOKEN");
 * boolean featureEnabled = ApplicationProperties.getBooleanProperty("my.feature.enabled", true);
 * }</pre>
 *
 * @see PropertySource
 * @see DefaultPropertySource
 * @see DecryptingPropertySource
 */
public class ApplicationProperties {

    /**
     * Returns the raw string property for the given key, or {@code null} if not found.
     */
    public static String getProperty(String name) {
        return getPropertySource().get(name);
    }

    /**
     * Maps a property value identified by its name to a desired type using the provided mapping function.
     * If the property is not available, the method returns {@code null}.
     */
    public static <T> T mapProperty(String name, Function<String, T> mapper) {
        return Optional.ofNullable(getProperty(name)).map(mapper).orElse(null);
    }

    /**
     * Maps a property value identified by its name to a desired type using the provided mapping function.
     * If the property is not found, the method returns a default value supplied by the given supplier.
     */
    public static <T> T mapProperty(String name, Function<String, T> mapper, Supplier<T> defaultValueSupplier) {
        var stringValue = getProperty(name);
        return stringValue == null ? defaultValueSupplier.get() : mapper.apply(stringValue);
    }

    /**
     * Returns an {@link Optional} containing the first non-null property value
     * among the provided property names, if any exist. If no properties are resolved,
     * returns an empty {@link Optional}.
     */
    public static String getFirstAvailableProperty(String... propertyNames) {
        return Arrays.stream(propertyNames)
                .map(ApplicationProperties::getProperty).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Resolves a boolean property by key, returning {@code false} if not present.
     * <p>Accepts case-insensitive "true" as {@code true}, otherwise returns {@code false}.
     */
    public static boolean getBooleanProperty(String name) {
        return getBooleanProperty(name, false);
    }

    /**
     * Resolves a boolean property by key, returning a default if the property is not present.
     */
    public static boolean getBooleanProperty(String name, boolean defaultValue) {
        return Optional.ofNullable(getProperty(name)).map("true"::equalsIgnoreCase).orElse(defaultValue);
    }

    /**
     * Resolves an integer property by key, or {@code null} if not found.
     *
     * @throws NumberFormatException if the property value is not a valid integer
     */
    public static Integer getIntegerProperty(String name) {
        return getIntegerProperty(name, null);
    }

    /**
     * Resolves an integer property by key, or returns the given default value if not found.
     *
     * @throws NumberFormatException if the property value is not a valid integer
     */
    public static Integer getIntegerProperty(String name, Integer defaultValue) {
        return Optional.ofNullable(getProperty(name)).map(Integer::valueOf).orElse(defaultValue);
    }

    /**
     * Resolves a long property by key, or {@code null} if not found.
     *
     * @throws NumberFormatException if the property value is not a valid long
     */
    public static Long getLongProperty(String name) {
        return getLongProperty(name, null);
    }

    /**
     * Resolves a long property by key, or returns the given default value if not found.
     *
     * @throws NumberFormatException if the property value is not a valid long
     */
    public static Long getLongProperty(String name, Long defaultValue) {
        return Optional.ofNullable(getProperty(name)).map(Long::valueOf).orElse(defaultValue);
    }

    /**
     * Returns the string property value for the given key, or the specified default if not found.
     */
    public static String getProperty(String name, String defaultValue) {
        return Optional.ofNullable(getProperty(name)).orElse(defaultValue);
    }

    /**
     * Returns the string property for the given key, throwing an {@link IllegalStateException} if not found.
     */
    public static String requireProperty(String name) {
        return Optional.ofNullable(getProperty(name)).orElseThrow(
                () -> new IllegalStateException(String.format("Property for %s is missing", name)));
    }

    /**
     * Returns {@code true} if a property with the given name exists.
     */
    public static boolean containsProperty(String name) {
        return getProperty(name) != null;
    }

    /**
     * Substitutes placeholders in the given template using current property values.
     * <p>Placeholders use the syntax {@code ${propertyName}}.
     */
    public static String substituteProperties(String template) {
        return getPropertySource().substituteProperties(template);
    }

    /**
     * Substitutes placeholders in the properties using current property values.
     * <p>Placeholders use the syntax {@code ${propertyName}}.
     */
    public static Properties substituteProperties(Properties properties) {
        return getPropertySource().substituteProperties(properties);
    }

    /**
     * Returns the currently active {@link Encryption} instance.
     * <p>By default, wraps the encryption from the current {@link PropertySource}.
     */
    public static Encryption getEncryption() {
        return getPropertySource().getEncryption();
    }

    /**
     * Encrypts the given value using the configured {@link Encryption} strategy.
     */
    public static String encryptValue(String value) {
        return getEncryption().encrypt(value);
    }

    /**
     * Decrypts the given encrypted value using the configured {@link Encryption} strategy.
     * <p>Returns the original value if decryption is not applicable.
     */
    public static String decryptValue(String encryptedValue) {
        return getEncryption().decrypt(encryptedValue);
    }

    static DecryptingPropertySource getPropertySource() {
        return Fluxzero.getOptionally().map(Fluxzero::propertySource)
                .map(p -> p instanceof DecryptingPropertySource dps ? dps : new DecryptingPropertySource(p))
                .orElseGet(() -> new DecryptingPropertySource(DefaultPropertySource.getInstance()));
    }
}
