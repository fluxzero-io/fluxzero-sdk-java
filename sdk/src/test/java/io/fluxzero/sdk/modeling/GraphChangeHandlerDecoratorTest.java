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
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;

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
                return ModelEntityParameterResolver.suppliesGraph(graphParameter)
                        ? HandlerInvoker.noOp(GraphOnlyHandler.class, method)
                        : null;
            }
        };
        DeserializingMessage event = new DeserializingMessage(
                new Message(
                        new PayloadWithoutModelIdentity(),
                        Metadata.of(COMMIT_ID, "commit", SUBSTEP, "0")),
                MessageType.EVENT, null);

        assertNotNull(ModelEntityParameterResolver
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
                return ModelEntityParameterResolver.suppliesGraph(graphParameter)
                        ? HandlerInvoker.noOp(GraphOnlyHandler.class, method)
                        : null;
            }
        };
        DeserializingMessage event = new DeserializingMessage(
                new Message(new PayloadWithoutModelIdentity()),
                MessageType.EVENT, null);

        assertNull(ModelEntityParameterResolver
                           .wrapGraphChanges(source, MessageType.EVENT)
                           .getInvokerOrNull(event));
    }

    @Test
    void leavesOrdinaryAndExplicitPayloadGraphHandlersUntouched() {
        Handler<DeserializingMessage> ordinary = handler(OrdinaryHandler.class);
        Handler<DeserializingMessage> explicit = handler(ExplicitGraphHandler.class);

        assertSame(
                ordinary,
                ModelEntityParameterResolver.wrapGraphChanges(
                        ordinary, MessageType.EVENT));
        assertSame(
                explicit,
                ModelEntityParameterResolver.wrapGraphChanges(
                        explicit, MessageType.EVENT));
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

    @Model
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
