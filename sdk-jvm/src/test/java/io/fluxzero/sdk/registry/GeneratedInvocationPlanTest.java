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

package io.fluxzero.sdk.registry;

import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedInvocationPlanTest {

    @Test
    void metadataLookupBuildsInvocationPlansFromRegistryDescriptors() throws Exception {
        ComponentMetadataLookup lookup = JvmComponentMetadataLookup.scan(
                GeneratedPlanHandler.class, GeneratedCommand.class);
        Method method = GeneratedPlanHandler.class.getDeclaredMethod("handle", GeneratedCommand.class);

        InvocationPlanDescriptor plan = lookup.invocationPlan(
                GeneratedPlanHandler.class.getName(),
                ExecutableKind.METHOD,
                "handle",
                List.of(typeName(GeneratedCommand.class))).orElseThrow();

        assertEquals(GeneratedPlanHandler.class.getName(), plan.targetComponentName());
        assertEquals(GeneratedExecutableInvocations.executableId(method), plan.executableId());
        assertEquals(typeName(GeneratedCommand.class), plan.parameters().getFirst().typeName());
        assertTrue(plan.propertyAccesses().stream().anyMatch(property -> property.name().equals("id")
                                                                     && property.annotationNames()
                                                                             .contains(EntityId.class.getName())));
    }

    @Test
    void jvmHandlerInvocationPrefersRegisteredGeneratedInvocation() throws Exception {
        GeneratedPlanHandler target = new GeneratedPlanHandler();
        Method method = GeneratedPlanHandler.class.getDeclaredMethod("handle", GeneratedCommand.class);

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(GeneratedPlanHandler.class, GeneratedCommand.class).registry());
            try (var ignored = GeneratedExecutableInvocations.register(
                    GeneratedPlanHandler.class,
                    GeneratedExecutableInvocations.executableId(method),
                    (handler, parameterCount, parameterProvider) ->
                            "generated:" + ((GeneratedCommand) parameterProvider.apply(0)).id())) {
                GeneratedOnlyMetadataMode.run(() -> {
                    var handler = HandlerInspector.createHandler(
                            target,
                            List.of(payloadResolver()),
                            HandlerConfiguration.builder()
                                    .methodAnnotation(HandleCommand.class)
                                    .executableAnnotationResolver(MetadataExecutableAnnotationResolver.create())
                                    .executableInvocationBackend(
                                            JvmComponentIntrospector.getInstance().executableInvocationBackend())
                                    .build());

                    assertEquals("generated:42", handler.getInvoker(new GeneratedCommand("42")).orElseThrow().invoke());
                    assertFalse(target.called.get(), "The JVM reflection method should not have been invoked");
                });
            }
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    private static ParameterResolver<Object> payloadResolver() {
        return new ParameterResolver<>() {
            @Override
            public Function<Object, Object> resolve(Parameter parameter, Annotation methodAnnotation) {
                return value -> value;
            }
        };
    }

    static class GeneratedPlanHandler {
        private final AtomicBoolean called = new AtomicBoolean();

        @EntityId
        private String id = "handler";

        @HandleCommand
        String handle(GeneratedCommand command) {
            called.set(true);
            throw new AssertionError("Generated invocation should have handled this call");
        }
    }

    record GeneratedCommand(@EntityId String id) {
    }
}
