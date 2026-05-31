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

import io.fluxzero.common.api.JsonType;

import java.io.IOException;

/**
 * Encodes and decodes Fluxzero websocket transport payloads after format negotiation and before compression.
 */
public interface WebSocketTransportCodec {

    /**
     * The negotiated format handled by this codec.
     */
    WebSocketTransportFormat format();

    /**
     * Encodes a protocol object into transport bytes.
     */
    byte[] encode(JsonType value) throws IOException;

    /**
     * Decodes transport bytes into a protocol object.
     */
    JsonType decode(byte[] bytes) throws IOException;
}
