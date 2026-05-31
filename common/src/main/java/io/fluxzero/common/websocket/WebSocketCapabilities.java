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

package io.fluxzero.common.websocket;

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility methods for exchanging Fluxzero websocket client capabilities through handshake headers.
 */
public final class WebSocketCapabilities {
    public static final String SUPPORTED_COMPRESSION_ALGORITHMS_HEADER =
            "Fluxzero-Supported-Compression-Algorithms";
    public static final String SELECTED_COMPRESSION_ALGORITHM_HEADER =
            "Fluxzero-Selected-Compression-Algorithm";
    public static final String SUPPORTED_TRANSPORT_FORMATS_HEADER =
            "Fluxzero-Supported-Transport-Formats";
    public static final String SELECTED_TRANSPORT_FORMAT_HEADER =
            "Fluxzero-Selected-Transport-Format";
    public static final String CLIENT_SESSION_ID_HEADER = "Fluxzero-Client-Session-Id";
    public static final String CLIENT_SDK_VERSION_HEADER = "Fluxzero-Client-Sdk-Version";
    public static final String RUNTIME_SESSION_ID_HEADER = "Fluxzero-Runtime-Session-Id";
    public static final String RUNTIME_VERSION_HEADER = "Fluxzero-Runtime-Version";

    private WebSocketCapabilities() {
    }

    /**
     * Serializes the supplied compression capabilities into websocket request headers.
     *
     * @param supportedCompressionAlgorithms ordered list of supported compression algorithms, most preferred first
     * @return websocket request headers representing the supplied capabilities
     */
    public static Map<String, List<String>> asHeaders(Collection<CompressionAlgorithm> supportedCompressionAlgorithms) {
        List<CompressionAlgorithm> algorithms = Optional.ofNullable(supportedCompressionAlgorithms).orElse(List.of())
                .stream().filter(Objects::nonNull).distinct().toList();
        if (algorithms.isEmpty()) {
            return Map.of();
        }
        return Map.of(SUPPORTED_COMPRESSION_ALGORITHMS_HEADER,
                      List.of(algorithms.stream().map(CompressionAlgorithm::name).collect(Collectors.joining(","))));
    }

    /**
     * Serializes the supplied websocket transport formats into websocket request headers.
     *
     * @param supportedTransportFormats ordered list of supported transport formats, most preferred first
     * @return websocket request headers representing the supplied transport capabilities
     */
    public static Map<String, List<String>> asTransportHeaders(
            Collection<WebSocketTransportFormat> supportedTransportFormats) {
        List<WebSocketTransportFormat> formats = Optional.ofNullable(supportedTransportFormats).orElse(List.of())
                .stream().filter(Objects::nonNull).distinct().toList();
        if (formats.isEmpty()) {
            return Map.of();
        }
        return Map.of(SUPPORTED_TRANSPORT_FORMATS_HEADER,
                      List.of(formats.stream().map(WebSocketTransportFormat::name).collect(Collectors.joining(","))));
    }

    /**
     * Extracts the ordered list of compression algorithms from websocket request headers.
     */
    public static List<CompressionAlgorithm> getSupportedCompressionAlgorithms(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        return headers.entrySet().stream()
                .filter(entry -> SUPPORTED_COMPRESSION_ALGORITHMS_HEADER.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(WebSocketCapabilities::parseCompressionAlgorithm)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    /**
     * Returns the most preferred compression algorithm advertised via request headers, if available.
     */
    public static Optional<CompressionAlgorithm> getPreferredCompressionAlgorithm(Map<String, List<String>> headers) {
        return getSupportedCompressionAlgorithms(headers).stream().findFirst();
    }

    public static Optional<CompressionAlgorithm> getSelectedCompressionAlgorithm(Map<String, List<String>> headers) {
        return getHeaderValue(headers, SELECTED_COMPRESSION_ALGORITHM_HEADER)
                .flatMap(WebSocketCapabilities::parseCompressionAlgorithm);
    }

    /**
     * Extracts the ordered list of websocket transport formats from websocket request headers.
     */
    public static List<WebSocketTransportFormat> getSupportedTransportFormats(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        return headers.entrySet().stream()
                .filter(entry -> SUPPORTED_TRANSPORT_FORMATS_HEADER.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(WebSocketCapabilities::parseTransportFormat)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    /**
     * Returns the most preferred websocket transport format advertised via request headers, if available.
     */
    public static Optional<WebSocketTransportFormat> getPreferredTransportFormat(Map<String, List<String>> headers) {
        return getSupportedTransportFormats(headers).stream().findFirst();
    }

    public static Optional<WebSocketTransportFormat> getSelectedTransportFormat(Map<String, List<String>> headers) {
        return getHeaderValue(headers, SELECTED_TRANSPORT_FORMAT_HEADER)
                .flatMap(WebSocketCapabilities::parseTransportFormat);
    }

    public static Optional<String> getClientSessionId(Map<String, List<String>> headers) {
        return getHeaderValue(headers, CLIENT_SESSION_ID_HEADER);
    }

    public static Optional<String> getClientSdkVersion(Map<String, List<String>> headers) {
        return getHeaderValue(headers, CLIENT_SDK_VERSION_HEADER);
    }

    public static Optional<String> getRuntimeSessionId(Map<String, List<String>> headers) {
        return getHeaderValue(headers, RUNTIME_SESSION_ID_HEADER);
    }

    public static Optional<String> getRuntimeVersion(Map<String, List<String>> headers) {
        return getHeaderValue(headers, RUNTIME_VERSION_HEADER);
    }

    public static String newShortSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Optional<CompressionAlgorithm> parseCompressionAlgorithm(String value) {
        try {
            return Optional.of(CompressionAlgorithm.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<WebSocketTransportFormat> parseTransportFormat(String value) {
        try {
            return Optional.of(WebSocketTransportFormat.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> getHeaderValue(Map<String, List<String>> headers, String headerName) {
        if (headers == null || headers.isEmpty()) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
                .filter(entry -> headerName.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .findFirst();
    }
}
