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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.fluxzero.common.websocket.WebSocketTransportFormat.CBOR;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketCapabilitiesTest {

    @Test
    void transportFormatsAreSerializedInPreferenceOrder() {
        Map<String, List<String>> headers =
                WebSocketCapabilities.asTransportHeaders(List.of(CBOR, JSON));

        assertEquals(List.of(CBOR, JSON), WebSocketCapabilities.getSupportedTransportFormats(headers));
        assertEquals(CBOR, WebSocketCapabilities.getPreferredTransportFormat(headers).orElseThrow());
    }

    @Test
    void transportFormatParsingIgnoresUnknownValues() {
        Map<String, List<String>> headers = Map.of(
                WebSocketCapabilities.SUPPORTED_TRANSPORT_FORMATS_HEADER, List.of("BINARY_V2, CBOR, JSON"));

        assertEquals(List.of(CBOR, JSON), WebSocketCapabilities.getSupportedTransportFormats(headers));
    }

    @Test
    void compressionAlgorithmParsingIgnoresUnknownValues() {
        Map<String, List<String>> headers = Map.of(
                WebSocketCapabilities.SUPPORTED_COMPRESSION_ALGORITHMS_HEADER, List.of("BROTLITE, LZ4, GZIP"));

        assertEquals(List.of(CompressionAlgorithm.LZ4, CompressionAlgorithm.GZIP),
                     WebSocketCapabilities.getSupportedCompressionAlgorithms(headers));
    }

    @Test
    void selectedTransportFormatIsParsedCaseInsensitivelyByHeaderName() {
        Map<String, List<String>> headers = Map.of(
                WebSocketCapabilities.SELECTED_TRANSPORT_FORMAT_HEADER.toLowerCase(), List.of("JSON"));

        assertEquals(JSON, WebSocketCapabilities.getSelectedTransportFormat(headers).orElseThrow());
    }
}
