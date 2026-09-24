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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.api.Data;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProperty;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphWebResponseTest {
    @ParameterizedTest
    @ValueSource(strings = {"application/json", ""})
    void serializationRetainsBodyBytesAndEnvelope(String contentType) {
        var serializer = new JacksonSerializer();
        Graph<Root> graph = graph(new Root(new RootId("root"), "document"));
        byte[] expectedBody = serializer.serialize(graph).getValue();
        var response = WebResponse.builder().status(201).payload(graph)
                .header("X-Custom", "retained").contentType(contentType.isEmpty() ? null : contentType).build()
                .addMetadata("custom", "metadata").withMessageId("response-id").withTimestamp(Instant.EPOCH);
        clearInvocations(graph);

        var wire = response.serialize(serializer);

        assertArrayEquals(expectedBody, wire.getData().getValue());
        assertEquals(response.getMetadata(), wire.getMetadata());
        assertEquals("response-id", wire.getMessageId());
        assertEquals(0L, wire.getTimestamp());
        JsonNode document = assertInstanceOf(JsonNode.class, serializer.deserialize(wire));
        assertEquals("root", document.path("id").asText());
        assertSame(graph, response.getPayload());
        verify(graph).get();
    }

    @Test
    void missingGraphSerializesAsJsonNull() {
        var serializer = new JacksonSerializer();
        var response = WebResponse.builder().status(200).payload(graph(null)).build();
        assertTrue(((JsonNode) serializer.deserialize(response.serialize(serializer))).isNull());
    }

    @Test
    void ordinaryTypedResponsesDoNotRequireConversion() {
        var serializer = spy(new JacksonSerializer());
        Root value = new Root(new RootId("root"), "plain model");
        var response = WebResponse.builder().status(200).payload(value).build();
        assertEquals(value, serializer.deserialize(response.serialize(serializer)));
        verify(serializer, never()).convert(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesCustomSerializerTypeAndFormat(boolean customFormat) {
        Graph<Root> graph = graph(new Root(new RootId("root"), "custom"));
        var serializer = mock(Serializer.class);
        var data = new Data<>(new byte[]{1, 2, 3}, customFormat ? graph.getClass().getName() : "custom.graph",
                              7, customFormat ? "application/custom" : Data.JSON_FORMAT);
        when(serializer.serialize(graph, Data.JSON_FORMAT)).thenReturn(data);
        var response = WebResponse.builder().payload(graph).contentType(Data.JSON_FORMAT).build();
        assertSame(data, response.serialize(serializer).getData());
        verify(serializer).serialize(graph, Data.JSON_FORMAT);
        verifyNoMoreInteractions(serializer);
    }

    @SuppressWarnings("unchecked")
    private static Graph<Root> graph(Root value) {
        Graph<Root> graph = mock(Graph.class);
        when(graph.get()).thenReturn(value);
        when(graph.type()).thenReturn(Root.class);
        when(graph.children()).thenReturn(List.of());
        return graph;
    }

    @ParameterizedTest
    @CsvSource({"false,/graph,identity", "true,/graph,identity", "false,/graph,gzip", "true,/graph,gzip",
                "false,/future,identity", "true,/future,identity", "false,/wrapped,identity", "true,/wrapped,identity"})
    void returnsComposedGraphWithoutReconstructingItsImplementation(boolean async, String path, String encoding) {
        TestFixture fixture = async ? TestFixture.createAsync(new Endpoint()) : TestFixture.create(new Endpoint());
        try {
            fixture.givenCommands(new CreateRoot(new RootId("root")),
                                  new CreateChild("visible", new RootId("root"), true),
                                  new CreateChild("hidden", new RootId("root"), false))
                    .whenWebRequest(WebRequest.get(path).header("Accept-Encoding", encoding).build())
                    .expectWebResult(response -> {
                        assertEquals(200, response.getStatus());
                        JsonNode body = response.getPayloadAs(JsonNode.class);
                        assertEquals("root", body.path("id").asText());
                        assertEquals(1, body.path("children").size());
                        JsonNode child = body.path("children").get(0);
                        assertEquals("visible", child.path("id").asText());
                        assertEquals("root", child.path("rootName").asText());
                        assertFalse(body.has("stateIndex"));
                        assertFalse(body.has("present"));
                        return true;
                    }).expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }

    @Model
    record Root(@EntityId RootId id, String description) {}

    static class RootId extends Id<Root> {
        RootId(String id) { super(id); }
    }

    @Model
    record Child(@EntityId String id, @Parent(pathInParent = "children") RootId rootId, boolean visible) {
        @GraphProperty
        String rootName(Graph<Root> root) { return root.functionalId(); }

        @FilterContent
        Child filter() { return visible ? this : null; }
    }

    record CreateRoot(RootId id) {
        @Apply Root apply() { return new Root(id, "x".repeat(3000)); }
    }

    record CreateChild(String id, RootId rootId, boolean visible) {
        @Apply Child apply() { return new Child(id, rootId, visible); }
    }

    @NoUserRequired
    static class Endpoint {
        @HandleGet("/graph") @FilterContent
        Graph<Root> get() { return Fluxzero.loadGraph(new RootId("root")); }

        @HandleGet("/future") @FilterContent
        CompletableFuture<Graph<Root>> future() { return CompletableFuture.completedFuture(get()); }

        @HandleGet("/wrapped")
        WebResponse wrapped() {
            return WebResponse.builder().status(200).payload(filtered()).build();
        }

        private Graph<Root> filtered() {
            return get().filterNodes(graph -> !(graph.get() instanceof Child child) || child.visible());
        }
    }
}
