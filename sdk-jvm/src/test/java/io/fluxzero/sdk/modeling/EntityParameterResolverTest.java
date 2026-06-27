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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityParameterResolverTest {

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForHandlerKind() throws Exception {
        Executable command = HandlerKindHandler.class.getDeclaredMethod("handleCommand", Entity.class);
        EntityParameterResolver resolver =
                new EntityParameterResolver(true, (executable, annotationType) -> Optional.empty());

        GeneratedOnlyMetadataMode.run(() ->
                assertTrue(resolver.mayApply(command, HandlerKindHandler.class)));
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForHandlerKind() throws Exception {
        Executable command = HandlerKindHandler.class.getDeclaredMethod("handleCommand", Entity.class);
        Executable event = HandlerKindHandler.class.getDeclaredMethod("handleEvent", Entity.class);
        Executable notification = HandlerKindHandler.class.getDeclaredMethod("handleNotification", Entity.class);
        EntityParameterResolver resolver = new EntityParameterResolver();
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(HandlerKindHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                assertFalse(resolver.mayApply(command, HandlerKindHandler.class));
                assertTrue(resolver.mayApply(event, HandlerKindHandler.class));
                assertTrue(resolver.mayApply(notification, HandlerKindHandler.class));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void metadataExecutableViewKeepsMessageEntityInjectionLimitedToEventsAndNotifications() throws Exception {
        EntityParameterResolver resolver = new EntityParameterResolver();
        Annotation command = HandlerKindHandler.class.getDeclaredMethod("handleCommand", Entity.class)
                .getAnnotation(HandleCommand.class);
        Annotation event = HandlerKindHandler.class.getDeclaredMethod("handleEvent", Entity.class)
                .getAnnotation(HandleEvent.class);
        Annotation notification = HandlerKindHandler.class.getDeclaredMethod("handleNotification", Entity.class)
                .getAnnotation(HandleNotification.class);

        assertFalse(resolver.mayApply(new MetadataExecutableView(command), HandlerKindHandler.class));
        assertTrue(resolver.mayApply(new MetadataExecutableView(event), HandlerKindHandler.class));
        assertTrue(resolver.mayApply(new MetadataExecutableView(notification), HandlerKindHandler.class));
    }

    @Test
    void metadataParameterViewResolvesEntityValueWithoutReflectionParameter() {
        SampleAggregate aggregate = new SampleAggregate();
        Entity<?> entity = ImmutableEntity.<SampleAggregate>builder()
                .id("sample")
                .type(SampleAggregate.class)
                .value(aggregate)
                .build();
        HasEntity input = () -> entity;
        ParameterView parameter = new MetadataParameterView(
                0, "aggregate", SampleAggregate.class.getName(), Optional.empty(), Map.of());
        EntityParameterResolver resolver = new EntityParameterResolver();

        assertTrue(resolver.matches(parameter, null, input));
        assertSame(aggregate, resolver.resolve(parameter, (Annotation) null).apply(input));
        assertSame(aggregate, resolver.resolveIfPossible(parameter, null, input).apply(input));
    }

    static class HandlerKindHandler {
        @HandleCommand
        void handleCommand(Entity<SampleAggregate> entity) {
        }

        @HandleEvent
        void handleEvent(Entity<SampleAggregate> entity) {
        }

        @HandleNotification
        void handleNotification(Entity<SampleAggregate> entity) {
        }
    }

    record SampleAggregate() {
    }

    private record MetadataExecutableView(
            Annotation annotation) implements ExecutableView {

        @Override
        public Kind kind() {
            return Kind.METHOD;
        }

        @Override
        public String targetTypeName() {
            return HandlerKindHandler.class.getName();
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
        public List<? extends ParameterView> parameters() {
            return List.of();
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
            return annotationType.isInstance(annotation) ? Optional.of(annotationType.cast(annotation))
                    : Optional.empty();
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
