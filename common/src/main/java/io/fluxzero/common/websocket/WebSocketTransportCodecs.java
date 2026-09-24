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
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static io.fluxzero.common.websocket.WebSocketTransportFormat.BINARY;
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
        return forFormat(format, objectMapper, List.of());
    }

    /** Creates a codec with optional protocol-specific compact payload representations. */
    public static WebSocketTransportCodec forFormat(
            WebSocketTransportFormat format, ObjectMapper objectMapper,
            List<? extends WebSocketPayloadCodec> payloadCodecs) {
        return switch (Objects.requireNonNullElse(format, JSON)) {
            case JSON -> json(objectMapper);
            case CBOR -> cbor(objectMapper);
            case BINARY -> binary(objectMapper, payloadCodecs);
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
        return new CborWebSocketTransportCodec(new CborObjectMapper(objectMapper), CBOR, List.of());
    }

    /**
     * Negotiated binary codec. Protocol values without a compact representation retain the CBOR representation.
     */
    public static WebSocketTransportCodec binary(ObjectMapper objectMapper) {
        return binary(objectMapper, List.of());
    }

    /** Negotiated binary codec extended with protocol-specific compact payload representations. */
    public static WebSocketTransportCodec binary(
            ObjectMapper objectMapper, List<? extends WebSocketPayloadCodec> payloadCodecs) {
        return new CborWebSocketTransportCodec(
                new CborObjectMapper(objectMapper), BINARY, List.copyOf(payloadCodecs));
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

    private record CborWebSocketTransportCodec(
            ObjectMapper objectMapper, WebSocketTransportFormat format,
            List<? extends WebSocketPayloadCodec> payloadCodecs)
            implements WebSocketTransportCodec {
        @Override
        public WebSocketTransportFormat format() {
            return format;
        }

        @Override
        public byte[] encode(JsonType value) throws IOException {
            for (WebSocketPayloadCodec payloadCodec : payloadCodecs) {
                byte[] compact = payloadCodec.tryEncode(value, format);
                if (compact != null) {
                    return compact;
                }
            }
            return objectMapper.writeValueAsBytes(value);
        }

        public JsonType decode(byte[] bytes) throws IOException {
            for (WebSocketPayloadCodec payloadCodec : payloadCodecs) {
                JsonType compact = payloadCodec.tryDecode(bytes, format);
                if (compact != null) {
                    return compact;
                }
            }
            return objectMapper.readValue(bytes, JsonType.class);
        }
    }

    private static final class CborObjectMapper extends ObjectMapper {
        private CborObjectMapper(ObjectMapper objectMapper) {
            super(objectMapper, new CBORFactory());
        }
    }
}
