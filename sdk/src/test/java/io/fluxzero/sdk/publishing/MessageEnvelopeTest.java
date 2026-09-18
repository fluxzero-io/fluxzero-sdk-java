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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageEnvelopeTest {
    @Test
    void envelopeRoundTripAndLogicalRedispatchHaveDifferentSourceContracts() {
        var serializer = new JacksonSerializer();
        for (boolean async : new boolean[]{false, true}) {
            for (boolean blocking : new boolean[]{false, true}) {
                for (var type : new MessageType[]{MessageType.COMMAND, MessageType.QUERY}) {
                    var serialized = new Message(new Inspect()).serialize(serializer);
                    serialized.setMessageId("supplied-id");
                    serialized.setSource("supplied-source");
                    var decoded = serializer.deserializeMessage(serialized, type);
                    assertEquals("supplied-id", decoded.getMessageId());
                    assertEquals("supplied-source", decoded.getSerializedObject().getSource());
                    assertEquals("supplied-id", decoded.toMessage().getMessageId());
                    var fixture = async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
                    String clientId = fixture.getFluxzero().client().id();
                    fixture.whenApplying(fc -> {
                        return type == MessageType.COMMAND
                                ? blocking ? fc.commandGateway().sendAndWait(decoded) : fc.commandGateway().send(decoded).join()
                                : blocking ? fc.queryGateway().sendAndWait(decoded) : fc.queryGateway().send(decoded).join();
                    }).<Observed>expectResult(observed -> {
                        assertEquals("supplied-id", observed.messageId());
                        assertEquals(async ? clientId : null, observed.source());
                        return true;
                    }).expectNoErrors();
                }
            }
        }
    }

    @Test
    void mutatingSerializedEnvelopeAfterLogicalMaterializationDoesNotReplaceLogicalMessage() {
        var serializer = new JacksonSerializer();
        var serialized = new Message(new Inspect()).withMessageId("before").serialize(serializer);
        var decoded = serializer.deserializeMessage(serialized, MessageType.COMMAND);
        assertEquals("before", decoded.toMessage().getMessageId());
        serialized.setMessageId("after");
        assertEquals("after", decoded.getMessageId());
        assertEquals("before", decoded.toMessage().getMessageId());
        assertEquals("after", serializer.deserializeMessage(serialized, MessageType.COMMAND).toMessage().getMessageId());
        var replaced = decoded.withMessage(decoded.toMessage().withMessageId("replacement"));
        assertEquals("replacement", replaced.toMessage().getMessageId());
        assertEquals("replacement", replaced.getSerializedObject().getMessageId());
    }

    record Inspect() {}
    record Observed(String messageId, String source) {}
    static class Handler {
        @HandleCommand @HandleQuery
        Observed handle(Inspect ignored, DeserializingMessage message) {
            return new Observed(message.getMessageId(), message.getSerializedObject().getSource());
        }
    }
}
