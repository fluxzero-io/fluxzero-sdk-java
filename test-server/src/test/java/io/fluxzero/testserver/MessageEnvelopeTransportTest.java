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

package io.fluxzero.testserver;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageEnvelopeTransportTest {
    @Test
    void redispatchAcrossClientsPreservesLogicalIdAndAssignsTransportSource() throws Exception {
        var server = TestServer.startServer(0);
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        String namespace = "envelope-" + UUID.randomUUID();
        try (var receiver = DefaultFluxzero.builder().disableTrackingMetrics().build(client(port, namespace, "receiver"));
             var sender = DefaultFluxzero.builder().disableTrackingMetrics().build(client(port, namespace, "sender"))) {
            receiver.registerHandlers(new Observer());
            var serializer = new JacksonSerializer();
            for (boolean blocking : new boolean[]{false, true}) {
                for (var type : new MessageType[]{MessageType.COMMAND, MessageType.QUERY}) {
                    String id = type + "-" + blocking;
                    var raw = new Message(new Inspect()).serialize(serializer);
                    raw.setMessageId(id);
                    raw.setSource("previous-hop");
                    var incoming = serializer.deserializeMessage(raw, type);
                    Observed observed = sender.apply(fc -> type == MessageType.COMMAND
                            ? blocking ? fc.commandGateway().sendAndWait(incoming)
                                       : fc.commandGateway().<Observed>send(incoming).join()
                            : blocking ? fc.queryGateway().sendAndWait(incoming)
                                       : fc.queryGateway().<Observed>send(incoming).join());
                    assertEquals(id, observed.messageId());
                    assertEquals(sender.client().id(), observed.source());
                    assertEquals(receiver.client().id(), observed.handlerClient());
                }
            }
        } finally {
            server.stop();
        }
    }

    private WebSocketClient client(int port, String namespace, String name) {
        return WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port).namespace(namespace)
                .name(name).id(name + "-" + UUID.randomUUID()).disableMetrics(true).build());
    }

    record Inspect() {}
    record Observed(String messageId, String source, String handlerClient) {}

    static class Observer {
        @HandleCommand
        @HandleQuery
        Observed handle(Inspect ignored, DeserializingMessage message) {
            return new Observed(message.getMessageId(), message.getSerializedObject().getSource(),
                                Fluxzero.get().client().id());
        }
    }
}
