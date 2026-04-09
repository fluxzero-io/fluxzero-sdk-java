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

package io.fluxzero.sdk.common.serialization;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeserializingMessageTest {

    @Test
    void returnsVoidPayloadClassWhenDelegatePayloadClassIsUnknown() {
        Serializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                new Data<>("Boom!".getBytes(), null, 0, "unknown"),
                null,
                "message-id",
                0L);

        DeserializingMessage message = new DeserializingMessage(
                new DeserializingObject<>(serializedMessage, type -> "Boom!"),
                MessageType.WEBREQUEST,
                null,
                serializer);

        message.getPayload();

        assertEquals(Void.class, message.getPayloadClass());
        assertDoesNotThrow(message::toString);
    }
}
