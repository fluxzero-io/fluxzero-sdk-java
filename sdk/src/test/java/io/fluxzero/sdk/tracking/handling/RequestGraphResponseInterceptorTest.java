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

package io.fluxzero.sdk.tracking.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.Graph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestGraphResponseInterceptorTest {
    private final JacksonSerializer serializer = new JacksonSerializer();
    private final RequestGraphResponseInterceptor subject = new RequestGraphResponseInterceptor(serializer);

    @Test
    void convertsGraphHandlerResultToJsonRequestContract() {
        Graph<TestModel> graph = graph("one");

        Object result = invoke(new GraphHandler(graph), new GraphRequest());

        ObjectNode document = assertInstanceOf(ObjectNode.class, result);
        assertEquals("one", document.path("value").asText());
    }

    @Test
    void convertsGraphCollectionToJsonCollectionContract() {
        Object result = invoke(
                new GraphListHandler(List.of(graph("one"), graph("two"))),
                new GraphListRequest());

        List<?> documents = assertInstanceOf(List.class, result);
        assertEquals(List.of("one", "two"), documents.stream()
                .map(JsonNode.class::cast)
                .map(node -> node.path("value").asText())
                .toList());
    }

    @Test
    void convertsAsynchronousGraphHandlerResult() {
        Object result = invoke(new AsyncGraphHandler(graph("async")), new GraphRequest());

        ObjectNode document = assertInstanceOf(
                ObjectNode.class,
                assertInstanceOf(CompletableFuture.class, result).join());
        assertEquals("async", document.path("value").asText());
    }

    @Test
    void ordinaryHandlerResultUsesNoOpPreparedPath() {
        String value = new String("result");

        Object result = invoke(new StringHandler(value), new StringRequest());

        assertSame(value, result);
    }

    private Object invoke(Object target, Object request) {
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                target, HandleCommand.class, List.of(new PayloadParameterResolver()));
        Handler<DeserializingMessage> wrapped = subject.wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message(request), MessageType.COMMAND, null, serializer);
        return wrapped.getInvokerOrNull(message).invoke();
    }

    @SuppressWarnings("unchecked")
    private static Graph<TestModel> graph(String value) {
        Graph<TestModel> graph = mock(Graph.class);
        when(graph.get()).thenReturn(new TestModel(value));
        when(graph.type()).thenReturn(TestModel.class);
        when(graph.children()).thenReturn(List.of());
        return graph;
    }

    record TestModel(String value) {
    }

    record GraphRequest() implements Request<ObjectNode> {
    }

    record GraphListRequest() implements Request<List<JsonNode>> {
    }

    record StringRequest() implements Request<String> {
    }

    record GraphHandler(Graph<TestModel> graph) {
        @HandleCommand
        Graph<TestModel> handle(GraphRequest request) {
            return graph;
        }
    }

    record GraphListHandler(List<Graph<TestModel>> graphs) {
        @HandleCommand
        List<Graph<TestModel>> handle(GraphListRequest request) {
            return graphs;
        }
    }

    record AsyncGraphHandler(Graph<TestModel> graph) {
        @HandleCommand
        CompletableFuture<Graph<TestModel>> handle(GraphRequest request) {
            return CompletableFuture.completedFuture(graph);
        }
    }

    record StringHandler(String result) {
        @HandleCommand
        String handle(StringRequest request) {
            return result;
        }
    }
}
