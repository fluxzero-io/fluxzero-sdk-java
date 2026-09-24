/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.websocket;

import io.fluxzero.common.api.JsonType;

import java.io.IOException;

/**
 * Optional compact representation for a protocol-specific websocket payload.
 *
 * <p>Implementations return {@code null} for values or transport formats they do not own. The generic transport then
 * falls back to its regular JSON or CBOR representation.</p>
 */
public interface WebSocketPayloadCodec {

    /** Returns a compact representation, or {@code null} when this codec does not own the value. */
    byte[] tryEncode(JsonType value, WebSocketTransportFormat format) throws IOException;

    /** Returns the decoded value, or {@code null} when the bytes do not belong to this codec. */
    JsonType tryDecode(byte[] bytes, WebSocketTransportFormat format) throws IOException;
}
