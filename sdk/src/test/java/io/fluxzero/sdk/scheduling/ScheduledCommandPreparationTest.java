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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.publishing.RecursivePublicationGuard.PUBLICATION_DEPTH_METADATA_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledCommandPreparationTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "3"})
    void unknownTypeRawDeliveryAcceptsNewAndLegacyDepthMetadata(String storedDepth) {
        var client = spy(LocalClient.newInstance());
        doReturn(client).when(client).forNamespace(null);
        GatewayClient transport = mock(GatewayClient.class);
        doReturn(transport).when(client).getGatewayClient(COMMAND);
        when(transport.append(any(Guarantee.class), any(SerializedMessage[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        try (var app = DefaultFluxzero.builder().disableAutomaticTracking().disableKeepalive().disableShutdownHook()
                .setMaxPublicationDepth(2).build(client)) {
            app.execute(fc -> {
                var parent = new DeserializingMessage(new Message("parent",
                        Metadata.of(PUBLICATION_DEPTH_METADATA_KEY, "2")), COMMAND, app.serializer());
                parent.apply(ignored -> {
                    app.messageScheduler().scheduleCommand(
                            new Schedule("future", "raw", Instant.now().plusSeconds(60)));
                    return null;
                });
                var envelope = app.messageScheduler().getSchedule("raw").orElseThrow();
                var prepared = assertInstanceOf(ScheduledCommand.class, envelope.getPayload()).getCommand();
                assertEquals("0", prepared.getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY));
                var command = prepared.withData(prepared.getData().withType("missing.FutureCommand"))
                        .withMetadata(prepared.getMetadata().with(PUBLICATION_DEPTH_METADATA_KEY, storedDepth));
                var source = new DeserializingMessage(envelope.withPayload(new ScheduledCommand(command)),
                                                      SCHEDULE, app.serializer());
                // Read the same wire envelope a separate scheduler application receives.
                var decoded = app.serializer().deserializeMessages(
                        Stream.of(source.getSerializedObject()), SCHEDULE).findFirst().orElseThrow();
                decoded.apply(message -> {
                    new ScheduledCommandHandler().handle(message.getPayload(), message);
                    return null;
                });
                ArgumentCaptor<SerializedMessage[]> sent = ArgumentCaptor.forClass(SerializedMessage[].class);
                verify(transport).append(eq(Guarantee.NONE), sent.capture());
                assertEquals(1, sent.getValue().length);
                var delivered = sent.getValue()[0];
                assertEquals(command.getData(), delivered.getData());
                assertEquals(command.getMessageId(), delivered.getMessageId());
                assertEquals(storedDepth, delivered.getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY));
            });
        }
    }

}
