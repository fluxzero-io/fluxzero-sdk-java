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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static io.fluxzero.common.api.modeling.ModelEventMetadata.COMMIT_ID;
import static io.fluxzero.common.api.modeling.ModelEventMetadata.SUBSTEP;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GraphChangeHandlerDecoratorTest {

    @Test
    void selectsGraphOnlyHandlerWithoutInspectingTheEventPayload() throws Exception {
        Method method = GraphOnlyHandler.class.getDeclaredMethod(
                "handle", Graph.class);
        Parameter graphParameter = method.getParameters()[0];
        Handler<DeserializingMessage> source = new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return GraphOnlyHandler.class;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(
                    DeserializingMessage message) {
                return GraphChangeHandlerDecorator.suppliesGraph(graphParameter)
                        ? HandlerInvoker.noOp(GraphOnlyHandler.class, method)
                        : null;
            }
        };
        DeserializingMessage event = new DeserializingMessage(
                new Message(
                        new PayloadWithoutModelIdentity(),
                        Metadata.of(COMMIT_ID, "commit", SUBSTEP, "0")),
                MessageType.EVENT, null);

        assertNotNull(GraphChangeHandlerDecorator
                              .wrapGraphChanges(source, MessageType.EVENT)
                              .getInvokerOrNull(event));
    }

    @Test
    void doesNotSelectGraphOnlyHandlerForOrdinaryEvents() throws Exception {
        Method method = GraphOnlyHandler.class.getDeclaredMethod(
                "handle", Graph.class);
        Parameter graphParameter = method.getParameters()[0];
        Handler<DeserializingMessage> source = new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return GraphOnlyHandler.class;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(
                    DeserializingMessage message) {
                return GraphChangeHandlerDecorator.suppliesGraph(graphParameter)
                        ? HandlerInvoker.noOp(GraphOnlyHandler.class, method)
                        : null;
            }
        };
        DeserializingMessage event = new DeserializingMessage(
                new Message(new PayloadWithoutModelIdentity()),
                MessageType.EVENT, null);

        assertNull(GraphChangeHandlerDecorator
                           .wrapGraphChanges(source, MessageType.EVENT)
                           .getInvokerOrNull(event));
    }

    @Test
    void leavesOrdinaryAndExplicitPayloadGraphHandlersUntouched() {
        Handler<DeserializingMessage> ordinary = handler(OrdinaryHandler.class);
        Handler<DeserializingMessage> explicit = handler(ExplicitGraphHandler.class);

        assertSame(
                ordinary,
                GraphChangeHandlerDecorator.wrapGraphChanges(
                        ordinary, MessageType.EVENT));
        assertSame(
                explicit,
                GraphChangeHandlerDecorator.wrapGraphChanges(
                        explicit, MessageType.EVENT));
    }

    @ParameterizedTest
    @MethodSource("contextMethods")
    void contextSignaturesSelectOnlyModelEventsAndNotifications(Method method) {
        Parameter graphParameter = Arrays.stream(method.getParameters())
                .filter(p -> p.getType() == Graph.class).findFirst().orElseThrow();
        Handler<DeserializingMessage> source = new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return ContextHandler.class;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return GraphChangeHandlerDecorator.suppliesGraph(graphParameter)
                        ? Optional.of(HandlerInvoker.noOp(ContextHandler.class, method)) : Optional.empty();
            }
        };
        for (MessageType type : new MessageType[]{MessageType.EVENT, MessageType.NOTIFICATION}) {
            Handler<DeserializingMessage> decorated = GraphChangeHandlerDecorator.wrapGraphChanges(source, type);
            assertNotNull(decorated.getInvokerOrNull(new DeserializingMessage(new Message(
                    new PayloadWithoutModelIdentity(), Metadata.of(COMMIT_ID, "commit", SUBSTEP, "0")), type, null)));
            assertNull(decorated.getInvokerOrNull(new DeserializingMessage(
                    new Message(new PayloadWithoutModelIdentity()), type, null)));
        }
        assertSame(source, GraphChangeHandlerDecorator.wrapGraphChanges(source, MessageType.COMMAND));
    }

    static Stream<Method> contextMethods() {
        return Arrays.stream(ContextHandler.class.getDeclaredMethods());
    }

    @Test
    void leavesQualifiedMultipleAndPayloadSelectionsUntouched() {
        for (Class<?> type : new Class<?>[]{QualifiedHandler.class, MultipleGraphsHandler.class,
                PayloadContextHandler.class, MetadataOnlyHandler.class}) {
            Handler<DeserializingMessage> source = handler(type);
            assertSame(source, GraphChangeHandlerDecorator.wrapGraphChanges(source, MessageType.EVENT));
        }
    }

    private static class ContextHandler {
        @HandleEvent @HandleNotification
        void messageFirst(Message message, Graph<Root> graph) {}

        @HandleEvent @HandleNotification
        void messageLast(Graph<Root> graph, Message message) {}

        @HandleEvent @HandleNotification
        void metadataFirst(Metadata metadata, Graph<Root> graph) {}

        @HandleEvent @HandleNotification
        void metadataLast(Graph<Root> graph, Metadata metadata) {}
    }

    private static class QualifiedHandler {
        @HandleEvent
        void handle(Metadata metadata, @Association("rootId") Graph<Root> graph) {}
    }

    private static class MultipleGraphsHandler {
        @HandleEvent
        void handle(Graph<Root> first, Metadata metadata, Graph<Root> second) {}
    }

    private static class PayloadContextHandler {
        @HandleEvent
        void handle(Metadata metadata, PayloadWithoutModelIdentity payload, Graph<Root> graph) {}
    }

    private static class MetadataOnlyHandler {
        @HandleEvent
        void handle(Metadata metadata, Message message) {}
    }

    private static Handler<DeserializingMessage> handler(Class<?> targetClass) {
        return new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(
                    DeserializingMessage message) {
                return Optional.empty();
            }
        };
    }

    private record PayloadWithoutModelIdentity() {
    }

    @Model(searchable = false)
    private record Root(@EntityId String id) {
    }

    private static class GraphOnlyHandler {
        @HandleEvent
        void handle(Graph<Root> graph) {
        }
    }

    private static class OrdinaryHandler {
        @HandleEvent
        void handle(PayloadWithoutModelIdentity event) {
        }
    }

    private static class ExplicitGraphHandler {
        @HandleEvent
        void handle(
                PayloadWithoutModelIdentity event,
                Graph<Root> graph) {
        }
    }
}
