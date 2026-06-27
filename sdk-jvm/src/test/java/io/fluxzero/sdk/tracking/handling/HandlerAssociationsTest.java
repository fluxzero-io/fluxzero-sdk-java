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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerAssociationsTest {

    @Test
    void reusesAssociationMetadataPerType() {
        var first = new HandlerAssociations(SampleHandler.class, List.of(), e -> null);
        var second = new HandlerAssociations(SampleHandler.class, List.of(), e -> null);

        assertSame(first.getAssociationProperties(), second.getAssociationProperties());
    }

    @Test
    void cachesAssociationAndExecutableMetadata() throws NoSuchMethodException {
        HandlerAssociations associations = new HandlerAssociations(SampleHandler.class, List.of(), e -> null);

        var associationProperties = associations.getAssociationProperties();
        assertEquals(List.of("aliasId", "someId"), associationProperties.keySet().stream().sorted().toList());
        assertEquals("someId", associationProperties.get("aliasId").getPath());

        Method alwaysMethod = SampleHandler.class.getDeclaredMethod("always", SamplePayload.class);
        assertTrue(associations.alwaysAssociate(alwaysMethod));

        Method routingKeyMethod = SampleHandler.class.getDeclaredMethod("routingKeyAssociation", SamplePayload.class);
        var routingKeyAssociations = associations.getMethodAssociationProperties(routingKeyMethod);
        assertSame(routingKeyAssociations, associations.getMethodAssociationProperties(routingKeyMethod));
        assertEquals(1, routingKeyAssociations.size());
        assertEquals("customId", routingKeyAssociations.getFirst().getPropertyName());
        assertFalse(routingKeyAssociations.getFirst().isComputedRoutingKey());

        Method computedMethod = SampleHandler.class.getDeclaredMethod("computedAssociation", SamplePayload.class);
        var computedAssociations = associations.getMethodAssociationProperties(computedMethod);
        assertEquals(1, computedAssociations.size());
        assertTrue(computedAssociations.getFirst().isComputedRoutingKey());

        Method parameterMethod = SampleHandler.class.getDeclaredMethod("parameterAssociation", SampleCommand.class);
        var parameterAssociations = associations.getMethodAssociationProperties(parameterMethod);
        assertEquals(1, parameterAssociations.size());
        assertEquals("orderId", parameterAssociations.getFirst().getPropertyName());
        assertEquals("targetId", parameterAssociations.getFirst().getAssociationValue().getPath());
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForAssociationAnnotations() throws NoSuchMethodException {
        GeneratedOnlyMetadataMode.run(() -> {
            HandlerAssociations associations =
                    new HandlerAssociations(UnregisteredGeneratedOnlyHandler.class, List.of(), e -> null);

            assertTrue(associations.getAssociationProperties().isEmpty());
            assertFalse(associations.alwaysAssociate(
                    UnregisteredGeneratedOnlyHandler.class.getDeclaredMethod("always", SamplePayload.class)));
            assertTrue(associations.getMethodAssociationProperties(
                    UnregisteredGeneratedOnlyHandler.class.getDeclaredMethod("handle", SamplePayload.class)).isEmpty());
        });
    }

    @Test
    void associationsFromMetadataViewsResolveParameterAssociations() throws NoSuchMethodException {
        Method method = SampleHandler.class.getDeclaredMethod("parameterAssociation", SampleCommand.class);
        Annotation association = method.getParameters()[0].getAnnotation(Association.class);
        ParameterView parameter = new MetadataParameterView(
                0, "command", SampleCommand.class.getName(), Optional.of(SampleCommand.class),
                Map.of(Association.class, association));
        ExecutableView executable = new MetadataExecutableView(List.of(parameter), Map.of());
        HandlerAssociations associations = new HandlerAssociations(
                SampleHandler.class, List.of(new PayloadParameterResolver()), e -> null);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new SampleCommand("order-1")), MessageType.COMMAND, null);

        assertEquals(Map.of("order-1", "targetId"),
                     associations.associationsFromViews(message, Stream.of(executable)));
    }

    static class SampleHandler {
        @Association({"someId", "aliasId"})
        private String someId;

        @HandleCommand
        @Association(always = true)
        static void always(SamplePayload payload) {
        }

        @HandleCommand
        @Association
        @RoutingKey("customId")
        void routingKeyAssociation(SamplePayload payload) {
        }

        @HandleCommand
        @Association
        void computedAssociation(SamplePayload payload) {
        }

        @HandleCommand
        void parameterAssociation(@Association(value = "orderId", path = "targetId") SampleCommand command) {
        }
    }

    static class UnregisteredGeneratedOnlyHandler {
        @Association
        private String someId;

        @HandleCommand
        @Association(always = true)
        void always(SamplePayload payload) {
        }

        @HandleCommand
        @Association
        void handle(SamplePayload payload) {
        }
    }

    record SamplePayload(String someId) {
    }

    record SampleCommand(String orderId) {
    }

    private record MetadataExecutableView(
            List<? extends ParameterView> parameters,
            Map<Class<? extends Annotation>, Annotation> annotations) implements ExecutableView {

        @Override
        public Kind kind() {
            return Kind.METHOD;
        }

        @Override
        public String targetTypeName() {
            return SampleHandler.class.getName();
        }

        @Override
        public String name() {
            return "handle";
        }

        @Override
        public String returnTypeName() {
            return "void";
        }

        @Override
        public Optional<Class<?>> targetClass() {
            return Optional.empty();
        }

        @Override
        public Optional<Executable> executable() {
            return Optional.empty();
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return Optional.ofNullable(annotations.get(annotationType)).map(annotationType::cast);
        }
    }

    private record MetadataParameterView(
            int index,
            String name,
            String typeName,
            Optional<Class<?>> type,
            Map<Class<? extends Annotation>, Annotation> annotations) implements ParameterView {

        @Override
        public Optional<Parameter> parameter() {
            return Optional.empty();
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return Optional.ofNullable(annotations.get(annotationType)).map(annotationType::cast);
        }
    }
}
