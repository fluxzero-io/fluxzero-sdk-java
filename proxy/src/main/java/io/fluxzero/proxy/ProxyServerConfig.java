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

package io.fluxzero.proxy;

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;

import java.util.List;
import java.util.Objects;

import static io.fluxzero.proxy.ForwardProxyConsumer.METRICS_ENABLED_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getFirstAvailableProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getProperty;

/**
 * Configuration for embedding the Fluxzero proxy without relying on process-global properties.
 *
 * @param port                           port to bind, or {@code 0} to select a free port
 * @param runtimeBaseUrl                 Fluxzero Runtime websocket base URL
 * @param applicationName                proxy client name
 * @param namespace                      optional namespace/project id
 * @param metricsEnabled                 whether the proxy client publishes metrics
 * @param supportedCompressionAlgorithms optional compression algorithm preference
 * @param healthEndpoint                 HTTP health endpoint path
 * @param gracefulShutdown               whether Jetty uses the proxy's graceful shutdown timeout
 */
public record ProxyServerConfig(
        int port,
        String runtimeBaseUrl,
        String applicationName,
        String namespace,
        boolean metricsEnabled,
        List<CompressionAlgorithm> supportedCompressionAlgorithms,
        String healthEndpoint,
        boolean gracefulShutdown
) {
    public static final String DEFAULT_APPLICATION_NAME = "$proxy";
    public static final String DEFAULT_HEALTH_ENDPOINT = "/proxy/health";

    public ProxyServerConfig {
        if (port < 0) {
            throw new IllegalArgumentException("port must be >= 0");
        }
        Objects.requireNonNull(runtimeBaseUrl, "runtimeBaseUrl must not be null");
        Objects.requireNonNull(applicationName, "applicationName must not be null");
        Objects.requireNonNull(healthEndpoint, "healthEndpoint must not be null");
        supportedCompressionAlgorithms = supportedCompressionAlgorithms == null
                ? null : List.copyOf(supportedCompressionAlgorithms);
    }

    public static ProxyServerConfig fromProperties() {
        String runtimeBaseUrl = getFirstAvailableProperty("FLUXZERO_BASE_URL", "FLUX_BASE_URL", "FLUX_URL");
        if (runtimeBaseUrl == null) {
            throw new IllegalStateException("FLUXZERO_BASE_URL, FLUX_BASE_URL or FLUX_URL property is not set");
        }
        return new ProxyServerConfig(
                getIntegerProperty("FLUXZERO_PROXY_PORT", getIntegerProperty("PROXY_PORT", 8080)),
                runtimeBaseUrl,
                getProperty("FLUXZERO_APPLICATION_NAME", DEFAULT_APPLICATION_NAME),
                getFirstAvailableProperty("FLUXZERO_NAMESPACE", "FLUXZERO_PROJECT_ID", "FLUX_PROJECT_ID", "PROJECT_ID"),
                getBooleanProperty(METRICS_ENABLED_PROPERTY, true),
                ProxyServer.getConfiguredCompressionAlgorithms().orElse(null),
                getProperty("PROXY_HEALTH_ENDPOINT", DEFAULT_HEALTH_ENDPOINT),
                true);
    }

    public static ProxyServerConfig forRuntime(String runtimeBaseUrl) {
        return new ProxyServerConfig(
                0, runtimeBaseUrl, DEFAULT_APPLICATION_NAME, null, true, null, DEFAULT_HEALTH_ENDPOINT, true);
    }

    public ProxyServerConfig withPort(int port) {
        return new ProxyServerConfig(port, runtimeBaseUrl, applicationName, namespace, metricsEnabled,
                                     supportedCompressionAlgorithms, healthEndpoint, gracefulShutdown);
    }

    public ProxyServerConfig withNamespace(String namespace) {
        return new ProxyServerConfig(port, runtimeBaseUrl, applicationName, namespace, metricsEnabled,
                                     supportedCompressionAlgorithms, healthEndpoint, gracefulShutdown);
    }

    public ProxyServerConfig withMetricsEnabled(boolean metricsEnabled) {
        return new ProxyServerConfig(port, runtimeBaseUrl, applicationName, namespace, metricsEnabled,
                                     supportedCompressionAlgorithms, healthEndpoint, gracefulShutdown);
    }
}
