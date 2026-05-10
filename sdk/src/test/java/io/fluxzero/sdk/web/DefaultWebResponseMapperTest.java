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

package io.fluxzero.sdk.web;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.MessageType.WEBRESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultWebResponseMapperTest {
    private final DefaultWebResponseMapper mapper = new DefaultWebResponseMapper();

    @Test
    void negotiatesJsonForObjectPayload() {
        WebResponse response = map(Map.of("answer", 42), "text/plain;q=0.4, application/json;q=0.8");

        assertEquals("application/json", response.getContentType());
        assertEquals(List.of("Accept"), response.getHeaders("Vary"));
    }

    @Test
    void negotiatesJsonForStringPayloadWhenPreferred() {
        WebResponse response = map("answer", "application/json, text/plain;q=0.5");

        assertEquals("application/json", response.getContentType());
    }

    @Test
    void keepsDefaultTextForStringPayloadWithWildcardAccept() {
        WebResponse response = map("answer", "*/*");

        assertEquals("text/plain", response.getContentType());
    }

    @Test
    void negotiatesBinaryForByteArrayPayload() {
        WebResponse response = map("abc".getBytes(), "application/octet-stream");

        assertEquals("application/octet-stream", response.getContentType());
    }

    @Test
    void ignoresUnsupportedAcceptForObjectPayload() {
        WebResponse response = map(Map.of("answer", 42), "text/plain");

        assertNull(response.getContentType());
    }

    @Test
    void keepsExplicitResponseContentType() {
        WebResponse response = map(
                new Message("a,b", WebResponse.asMetadata(200, Map.of("Content-Type", List.of("text/csv")))),
                "application/json");

        assertEquals("text/csv", response.getContentType());
    }

    @Test
    void gatewayNegotiatesMarkedResponsesBeforeSerialization() {
        AtomicReference<SerializedMessage> appended = new AtomicReference<>();
        GatewayClient gatewayClient = mock(GatewayClient.class);
        when(gatewayClient.append(any(), any(SerializedMessage[].class))).thenAnswer(invocation -> {
            Object messageArgument = invocation.getArgument(1);
            appended.set(messageArgument instanceof SerializedMessage[] messages
                    ? messages[0] : (SerializedMessage) messageArgument);
            return CompletableFuture.completedFuture(null);
        });
        Client client = mock(Client.class);
        when(client.getGatewayClient(WEBRESPONSE)).thenReturn(gatewayClient);
        when(client.namespace()).thenReturn("");

        JacksonSerializer serializer = new JacksonSerializer();
        WebResponseGateway gateway = new WebResponseGateway(
                client, serializer, DispatchInterceptor.noOp, new DefaultWebResponseMapper());
        WebRequest request = WebRequest.get("/test").header("Accept", "application/json").build();

        new DeserializingMessage(request, WEBREQUEST, serializer).apply(__ -> {
            gateway.respond(Map.of("answer", 42), Metadata.empty(), "target", 1, Guarantee.NONE).join();
            return null;
        });

        WebResponse response = new WebResponse(new Message(
                appended.get().getData().getValue(), appended.get().getMetadata()));
        assertEquals("application/json", response.getContentType());
        assertEquals(List.of("Accept"), response.getHeaders("Vary"));
    }

    private WebResponse map(Object payload, String accept) {
        WebRequest request = WebRequest.get("/test").header("Accept", accept).build();
        DeserializingMessage message = new DeserializingMessage(request, WEBREQUEST, mock(Serializer.class));
        return message.apply(__ -> WebResponseContentNegotiator.negotiate(mapper.map(payload)));
    }
}
