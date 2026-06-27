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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultHandlerFactoryGeneratedOnlyMetadataTest {

    @Test
    void generatedOnlyModeDoesNotDiscoverHandlerAnnotationsWithoutRegistryMetadata() {
        GeneratedOnlyMetadataMode.run(() ->
                assertTrue(factory().createHandler(
                        new UnregisteredGeneratedOnlyHandler(), (c, e) -> true, List.of()).isEmpty()));
    }

    @Test
    void generatedOnlyModeDiscoversHandlerAnnotationsFromRegistryMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = factory().createHandler(
                        new RegisteredGeneratedOnlyHandler(), (c, e) -> true, List.of()).orElseThrow();

                assertEquals("handled", handler.getInvokerOrNull(command()).invoke());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeHonorsDisabledHandlerMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(DisabledGeneratedOnlyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() ->
                    assertTrue(factory().createHandler(
                            new DisabledGeneratedOnlyHandler(), (c, e) -> true, List.of()).isEmpty()));
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeHonorsPassiveHandlerMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(PassiveGeneratedOnlyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = factory().createHandler(
                        new PassiveGeneratedOnlyHandler(), (c, e) -> true, List.of()).orElseThrow();

                HandlerInvoker invoker = handler.getInvokerOrNull(command());
                assertEquals("passive", invoker.invoke());
                assertTrue(invoker.isPassive());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeSynthesizesClassLiteralAnnotationAttributes() throws Exception {
        Method method = AllowedClassesGeneratedOnlyHandler.class.getDeclaredMethod("handle");
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(AllowedClassesGeneratedOnlyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                HandleCommand annotation = (HandleCommand) MetadataExecutableAnnotationResolver.create()
                        .getAnnotation(method, HandleCommand.class).orElseThrow();

                assertArrayEquals(new Class<?>[]{AllowedGeneratedOnlyCommand.class}, annotation.allowedClasses());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static DefaultHandlerFactory factory() {
        return new DefaultHandlerFactory(
                MessageType.COMMAND, HandlerDecorator.noOp, List.of(), MethodInvocationValidator.noOp(),
                c -> null, null, false, null);
    }

    private static DeserializingMessage command() {
        return new DeserializingMessage(new Message("command"), MessageType.COMMAND, null);
    }

    private static class UnregisteredGeneratedOnlyHandler {
        @HandleCommand
        String handle() {
            return "unregistered";
        }
    }

    private static class RegisteredGeneratedOnlyHandler {
        @HandleCommand
        String handle() {
            return "handled";
        }
    }

    private static class DisabledGeneratedOnlyHandler {
        @HandleCommand(disabled = true)
        String handle() {
            return "disabled";
        }
    }

    private static class PassiveGeneratedOnlyHandler {
        @HandleCommand(passive = true)
        String handle() {
            return "passive";
        }
    }

    private static class AllowedClassesGeneratedOnlyHandler {
        @HandleCommand(allowedClasses = AllowedGeneratedOnlyCommand.class)
        String handle() {
            return "allowed";
        }
    }

    private record AllowedGeneratedOnlyCommand() {
    }
}
