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
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.SocketEndpoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    @Test
    void generatedOnlyModeSynthesizesConcreteAnnotationWhenResolvingByMetaAnnotation() throws Exception {
        Method method = RegisteredGeneratedOnlyHandler.class.getDeclaredMethod("handle");
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                var annotation = MetadataExecutableAnnotationResolver.create()
                        .getAnnotation(method, HandleMessage.class).orElseThrow();

                assertEquals(HandleCommand.class, annotation.annotationType());
                assertTrue(annotation instanceof HandleCommand);
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeDoesNotDiscoverSocketEndpointWithoutRegistryMetadata() {
        GeneratedOnlyMetadataMode.run(() ->
                assertTrue(webFactory().createHandler(
                        UnregisteredSocketEndpoint.class, (c, e) -> true, List.of()).isEmpty()));
    }

    @Test
    void generatedOnlyModeCreatesSocketEndpointFromRegistryMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredSocketEndpoint.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = webFactory().createHandler(
                        RegisteredSocketEndpoint.class, (c, e) -> true, List.of()).orElseThrow();

                assertEquals("SocketEndpointHandler[%s]".formatted(RegisteredSocketEndpoint.class), handler.toString());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeCreatesTrackSelfHandlerFromRegistryMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredTrackSelfCommand.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = factory().createHandler(
                        RegisteredTrackSelfCommand.class, (c, e) -> true, List.of()).orElseThrow();

                assertEquals("self", handler.getInvokerOrNull(
                        new DeserializingMessage(new Message(new RegisteredTrackSelfCommand()),
                                                 MessageType.COMMAND, null))
                        .invoke());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeCreatesStatefulHandlerFromRegistryMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredStatefulHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = statefulFactory().createHandler(
                        RegisteredStatefulHandler.class, (c, e) -> true, List.of()).orElseThrow();

                assertEquals("StatefulHandler[%s]".formatted(RegisteredStatefulHandler.class), handler.toString());
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

    private static DefaultHandlerFactory webFactory() {
        return new DefaultHandlerFactory(
                MessageType.WEBREQUEST, HandlerDecorator.noOp, List.of(), MethodInvocationValidator.noOp(),
                c -> null, repositoryProvider(), false, null);
    }

    private static DefaultHandlerFactory statefulFactory() {
        return new DefaultHandlerFactory(
                MessageType.COMMAND, HandlerDecorator.noOp, List.of(), MethodInvocationValidator.noOp(),
                c -> emptyRepository(), null, false, null);
    }

    private static RepositoryProvider repositoryProvider() {
        Map<Class<?>, Map<Object, Object>> repositories = new ConcurrentHashMap<>();
        return new RepositoryProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Map<Object, T> getRepository(Class<T> repositoryClass) {
                return (Map<Object, T>) repositories.computeIfAbsent(
                        repositoryClass, key -> new ConcurrentHashMap<>());
            }
        };
    }

    private static HandlerRepository emptyRepository() {
        return new HandlerRepository() {
            @Override
            public Collection<? extends Entry<?>> findByAssociation(Map<Object, String> associations) {
                return List.of();
            }

            @Override
            public Collection<? extends Entry<?>> getAll() {
                return List.of();
            }

            @Override
            public CompletableFuture<?> put(Object id, Object value) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<?> delete(Object id) {
                return CompletableFuture.completedFuture(null);
            }
        };
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

    @TrackSelf
    private static class RegisteredTrackSelfCommand {
        @HandleCommand
        String handle() {
            return "self";
        }
    }

    @Stateful
    private static class RegisteredStatefulHandler {
        @HandleCommand
        void handle(String command) {
        }
    }

    @SocketEndpoint
    private static class UnregisteredSocketEndpoint {
        @HandleSocketOpen
        void open() {
        }
    }

    @SocketEndpoint(aliveCheck = @SocketEndpoint.AliveCheck(
            timeUnit = TimeUnit.MILLISECONDS, pingDelay = 7, pingTimeout = 3))
    private static class RegisteredSocketEndpoint {
        @HandleSocketOpen
        void open() {
        }
    }

    private record AllowedGeneratedOnlyCommand() {
    }
}
