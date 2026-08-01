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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.modeling.ModelCommitWireCodec;
import io.fluxzero.common.api.modeling.ModelEventWireCodec;
import io.fluxzero.common.api.tracking.TrackingWireCodec;

import java.io.IOException;
import java.util.Objects;

import static io.fluxzero.common.websocket.WebSocketTransportFormat.BINARY;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.BINARY_V2;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.CBOR;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.JSON;

/**
 * Factory and built-in implementations for Fluxzero websocket transport codecs.
 */
public final class WebSocketTransportCodecs {
    private WebSocketTransportCodecs() {
    }

    /**
     * Creates a codec for the supplied format.
     */
    public static WebSocketTransportCodec forFormat(WebSocketTransportFormat format, ObjectMapper objectMapper) {
        return switch (Objects.requireNonNullElse(format, JSON)) {
            case JSON -> json(objectMapper);
            case CBOR -> cbor(objectMapper);
            case BINARY -> binary(objectMapper);
            case BINARY_V2 -> binaryV2(objectMapper);
        };
    }

    /**
     * Existing JSON websocket codec. Jackson represents {@code byte[]} fields as base64 strings here.
     */
    public static WebSocketTransportCodec json(ObjectMapper objectMapper) {
        return new JsonWebSocketTransportCodec(objectMapper);
    }

    /**
     * Jackson CBOR codec. This keeps the JSON object model but writes {@code byte[]} fields as native binary.
     */
    public static WebSocketTransportCodec cbor(ObjectMapper objectMapper) {
        return new CborWebSocketTransportCodec(new CborObjectMapper(objectMapper), CBOR);
    }

    /**
     * Negotiated binary codec. Protocol values without a compact representation retain the CBOR representation.
     */
    public static WebSocketTransportCodec binary(ObjectMapper objectMapper) {
        return new CborWebSocketTransportCodec(new CborObjectMapper(objectMapper), BINARY);
    }

    /**
     * Native binary codec. Messages use a patchable envelope while other compact protocols retain their binary form.
     */
    public static WebSocketTransportCodec binaryV2(ObjectMapper objectMapper) {
        return new CborWebSocketTransportCodec(new CborObjectMapper(objectMapper), BINARY_V2);
    }

    private record JsonWebSocketTransportCodec(ObjectMapper objectMapper) implements WebSocketTransportCodec {
        @Override
        public WebSocketTransportFormat format() {
            return JSON;
        }

        @Override
        public byte[] encode(JsonType value) throws IOException {
            return objectMapper.writeValueAsBytes(value);
        }

        @Override
        public JsonType decode(byte[] bytes) throws IOException {
            return objectMapper.readValue(bytes, JsonType.class);
        }
    }

    private record CborWebSocketTransportCodec(ObjectMapper objectMapper, WebSocketTransportFormat format)
            implements WebSocketTransportCodec {
        @Override
        public WebSocketTransportFormat format() {
            return format;
        }

        @Override
        public byte[] encode(JsonType value) throws IOException {
            byte[] compact = format == BINARY_V2
                    ? TrackingWireCodec.tryEncodeNative(value)
                    : format == BINARY ? TrackingWireCodec.tryEncode(value) : null;
            if (compact == null) {
                compact = format == BINARY_V2
                        ? ModelCommitWireCodec.tryEncodeNative(value)
                        : ModelCommitWireCodec.tryEncode(value);
            }
            if (compact == null) {
                compact = ModelEventWireCodec.tryEncode(value);
            }
            return compact == null ? objectMapper.writeValueAsBytes(value) : compact;
        }

        public JsonType decode(byte[] bytes) throws IOException {
            JsonType compact = format == BINARY_V2
                    ? TrackingWireCodec.tryDecodeNative(bytes)
                    : format == BINARY ? TrackingWireCodec.tryDecode(bytes) : null;
            if (compact == null) {
                compact = format == BINARY_V2
                        ? ModelCommitWireCodec.tryDecodeNative(bytes)
                        : ModelCommitWireCodec.tryDecode(bytes);
            }
            if (compact == null) {
                compact = ModelEventWireCodec.tryDecode(bytes);
            }
            return compact == null ? objectMapper.readValue(bytes, JsonType.class) : compact;
        }
    }

    private static final class CborObjectMapper extends ObjectMapper {
        private CborObjectMapper(ObjectMapper objectMapper) {
            super(objectMapper, new CBORFactory());
        }
    }
}
