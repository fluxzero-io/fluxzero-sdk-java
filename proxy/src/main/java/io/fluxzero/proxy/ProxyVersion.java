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

package io.fluxzero.proxy;

import io.fluxzero.common.FileUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Resolves the Maven version of the Fluxzero proxy from build metadata packaged with the artifact.
 */
@Slf4j
final class ProxyVersion {
    private static final String VERSION_KEY = "version";
    private static final String VERSION_RESOURCE = "/META-INF/fluxzero/proxy.version";
    private static final String POM_PROPERTIES_RESOURCE = "/META-INF/maven/io.fluxzero/proxy/pom.properties";

    private static volatile String version;
    private static volatile boolean versionResolved;

    private ProxyVersion() {
    }

    static Optional<String> version() {
        if (!versionResolved) {
            synchronized (ProxyVersion.class) {
                if (!versionResolved) {
                    version = load(VERSION_RESOURCE).or(() -> load(POM_PROPERTIES_RESOURCE)).orElse(null);
                    if (version == null) {
                        log.warn("Could not determine Fluxzero proxy version from packaged build metadata. "
                                 + "This can happen when META-INF resources are stripped or overwritten during "
                                 + "repackaging/shading.");
                    }
                    versionResolved = true;
                }
            }
        }
        return Optional.ofNullable(version);
    }

    private static Optional<String> load(String resourcePath) {
        return normalize(FileUtils.loadProperties(resourcePath).getProperty(VERSION_KEY));
    }

    private static Optional<String> normalize(String value) {
        return Optional.ofNullable(value)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .filter(v -> !v.contains("${"));
    }
}
