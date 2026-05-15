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

package io.fluxzero.common.application;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * A {@link PropertySource} that loads extra configuration from locations declared through
 * {@value #CONFIG_LOCATIONS_PROPERTY}.
 * <p>
 * The property can be set as an environment variable or JVM system property. Multiple locations can be separated with
 * commas. Supported locations are {@code file:/path/to/config.properties}, plain file paths, and
 * {@code classpath:config.properties}. Prefix a location with {@code optional:} to ignore it when it does not exist.
 * Later locations override earlier locations.
 */
@Slf4j
public class FluxzeroAdditionalPropertiesSource extends JavaPropertiesSource {
    /**
     * Environment variable or system property that points to extra Fluxzero configuration locations.
     */
    public static final String CONFIG_LOCATIONS_PROPERTY = "FLUXZERO_CONFIG_LOCATIONS";

    /**
     * Lower-case system property alias for {@link #CONFIG_LOCATIONS_PROPERTY}.
     */
    public static final String CONFIG_LOCATIONS_PROPERTY_ALIAS = "fluxzero.config.locations";

    /**
     * Creates a source backed by the configured additional locations.
     */
    public FluxzeroAdditionalPropertiesSource() {
        super(loadAdditionalProperties());
    }

    private static Properties loadAdditionalProperties() {
        return Optional.ofNullable(System.getenv(CONFIG_LOCATIONS_PROPERTY))
                .or(() -> Optional.ofNullable(System.getProperty(CONFIG_LOCATIONS_PROPERTY)))
                .or(() -> Optional.ofNullable(System.getProperty(CONFIG_LOCATIONS_PROPERTY_ALIAS)))
                .map(FluxzeroAdditionalPropertiesSource::loadLocations)
                .orElseGet(Properties::new);
    }

    private static Properties loadLocations(String locations) {
        Properties result = new Properties();
        for (String value : locations.split(",")) {
            if (value.isBlank()) {
                continue;
            }
            ConfigLocation location = ConfigLocation.parse(value);
            putAll(result, loadLocation(location));
        }
        return result;
    }

    private static Properties loadLocation(ConfigLocation location) {
        try {
            return loadRequiredLocation(location.value());
        } catch (FileNotFoundException e) {
            if (location.optional()) {
                log.debug("Optional Fluxzero configuration location {} was not found", location.value());
                return new Properties();
            }
            throw new IllegalStateException("Fluxzero configuration location %s was not found".formatted(
                    location.value()), e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load Fluxzero configuration location %s".formatted(
                    location.value()), e);
        }
    }

    private static Properties loadRequiredLocation(String location) throws Exception {
        if (location.startsWith("classpath:")) {
            return loadClasspathLocation(location.substring("classpath:".length()));
        }
        Path path = location.startsWith("file:") ? filePath(location) : Path.of(location);
        if (!Files.exists(path)) {
            throw new FileNotFoundException(path.toString());
        }
        try (InputStream inputStream = Files.newInputStream(path)) {
            return load(inputStream, path.toString());
        }
    }

    @SneakyThrows
    private static Properties loadClasspathLocation(String location) {
        Properties result = new Properties();
        String normalized = location.startsWith("/") ? location.substring(1) : location;
        var resources = Collections.list(FluxzeroAdditionalPropertiesSource.class.getClassLoader()
                                             .getResources(normalized)).reversed();
        if (resources.isEmpty()) {
            throw new FileNotFoundException("classpath:" + normalized);
        }
        for (URL resource : resources) {
            try (InputStream inputStream = resource.openStream()) {
                putAll(result, load(inputStream, normalized));
            }
        }
        return result;
    }

    private static Properties load(InputStream inputStream, String location) throws Exception {
        if (location.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return FluxzeroPropertiesSource.loadJsonProperties(inputStream);
        }
        Properties properties = new Properties();
        properties.load(inputStream);
        return properties;
    }

    private static Path filePath(String location) {
        URI uri = URI.create(location);
        return uri.isOpaque() ? Path.of(location.substring("file:".length())) : Path.of(uri);
    }

    private static void putAll(Properties result, Properties properties) {
        result.putAll(properties);
    }

    private record ConfigLocation(boolean optional, String value) {
        private static ConfigLocation parse(String rawValue) {
            String value = rawValue.trim();
            boolean optional = value.regionMatches(true, 0, "optional:", 0, "optional:".length());
            if (optional) {
                value = value.substring("optional:".length()).trim();
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("Empty Fluxzero configuration location");
            }
            return new ConfigLocation(optional, value);
        }
    }
}
