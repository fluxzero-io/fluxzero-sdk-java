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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.serialization.JsonUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static io.fluxzero.common.FileUtils.loadProperties;

/**
 * A {@link PropertySource} that loads Fluxzero-specific settings from classpath resources.
 * <p>
 * The source reads {@code fluxzero.json} and {@code fluxzero.properties} from the root of the classpath, merging all
 * matching resources across modules. JSON objects are flattened to dot-separated property names, so
 * {@code {"validation":{"enabled":true}}} becomes {@code validation.enabled=true}. Inside this source,
 * {@code fluxzero.properties} wins over {@code fluxzero.json}; the default Fluxzero property chain still lets
 * {@code application.properties}, {@code application-<env>.properties}, system properties, and environment variables
 * override these Fluxzero-specific defaults.
 *
 * @see DefaultPropertySource
 * @see ApplicationPropertiesSource
 */
@Slf4j
public class FluxzeroPropertiesSource extends JavaPropertiesSource {

    /**
     * Creates a source backed by {@code fluxzero.json} and {@code fluxzero.properties} classpath resources.
     */
    public FluxzeroPropertiesSource() {
        super(loadFluxzeroProperties());
    }

    private static Properties loadFluxzeroProperties() {
        Properties result = loadJsonProperties("fluxzero.json");
        result.putAll(loadProperties("fluxzero.properties"));
        return result;
    }

    @SneakyThrows
    static Properties loadJsonProperties(String fileName) {
        Properties result = new Properties();
        String normalized = fileName.startsWith("/") ? fileName.substring(1) : fileName;
        var resources = Collections.list(FluxzeroPropertiesSource.class.getClassLoader()
                                             .getResources(normalized)).reversed();
        for (URL resource : resources) {
            try (InputStream inputStream = resource.openStream()) {
                result.putAll(loadJsonProperties(inputStream));
            }
        }
        return result;
    }

    @SneakyThrows
    static Properties loadJsonProperties(InputStream inputStream) {
        Properties result = new Properties();
        flattenJsonProperties(result, "", JsonUtils.writer.readTree(inputStream));
        return result;
    }

    @SneakyThrows
    private static void flattenJsonProperties(Properties result, String prefix, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String name = prefix.isBlank() ? field.getKey() : prefix + "." + field.getKey();
                flattenJsonProperties(result, name, field.getValue());
            }
            return;
        }
        String value = node.isValueNode() ? node.asText() : JsonUtils.writer.writeValueAsString(node);
        put(result, prefix, value);
    }

    private static void put(Properties result, Object key, Object value) {
        Object existing = result.put(key, value);
        if (existing != null && !Objects.equals(existing, value)) {
            log.warn("Fluxzero property {} has been registered more than once. The last value wins.", key);
        }
    }
}
