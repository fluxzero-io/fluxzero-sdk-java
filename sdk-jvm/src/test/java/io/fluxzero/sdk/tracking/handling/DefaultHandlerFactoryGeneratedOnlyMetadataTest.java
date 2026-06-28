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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.ComponentRegistryException;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.ExecutableKind;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.InvocationPlanDescriptor;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.registry.ParameterDescriptor;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthenticatedException;
import io.fluxzero.sdk.tracking.handling.validation.ValidationUtils;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.HandleWebResponse;
import io.fluxzero.sdk.web.SocketEndpoint;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_OPEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultHandlerFactoryGeneratedOnlyMetadataTest {

    @Test
    void generatedOnlyModeUsesGeneratedClasspathRegistryForCompiledLocalHandlerMetadata() {
        GeneratedOnlyMetadataMode.run(() -> {
            Handler<DeserializingMessage> handler = factory().createHandler(
                    new GeneratedClasspathHandler(), (c, e) -> true, List.of()).orElseThrow();

            assertEquals("classpath", handler.getInvokerOrNull(command()).invoke());
        });
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
    void generatedOnlyModeExecutesAllNonWebHandlerAnnotationsFromRegistryMetadata() {
        for (HandlerCase handlerCase : nonWebHandlerCases()) {
            try {
                TestFixture.create().getFluxzero().registerComponentRegistry(
                        JvmComponentMetadataLookup.scan(handlerCase.handlerType()).registry());

                GeneratedOnlyMetadataMode.run(() -> {
                    Handler<DeserializingMessage> handler = factory(handlerCase.messageType()).createHandler(
                            handlerCase.target(), (c, e) -> true, List.of()).orElseThrow();

                    assertEquals(handlerCase.expectedResult(), handler.getInvokerOrNull(
                            message(handlerCase.messageType(), handlerCase.topic())).invoke());
                });
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }
    }

    @Test
    void generatedOnlyModeRejectsRegisteredHandlerWithoutGeneratedInvocation() {
        class LocalRegisteredGeneratedOnlyHandler {
            @HandleCommand
            String handle() {
                return "handled";
            }
        }

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    registryWithUnlowerableHandlerPlan(LocalRegisteredGeneratedOnlyHandler.class));

            GeneratedOnlyMetadataMode.run(() -> assertThrows(ComponentRegistryException.class,
                    () -> factory().createHandler(
                            new LocalRegisteredGeneratedOnlyHandler(), (c, e) -> true, List.of())));
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeRejectsHandlerInspectorFallbackWithoutRegistryMatcher() {
        class LocalUnregisteredGeneratedOnlyHandler {
            @HandleCommand
            String handle() {
                return "handled";
            }
        }

        HandlerConfiguration<DeserializingMessage> config =
                HandlerConfiguration.<DeserializingMessage>builder()
                        .methodAnnotation(HandleCommand.class)
                        .build();

        GeneratedOnlyMetadataMode.run(() -> assertThrows(ComponentRegistryException.class,
                () -> new ExposedDefaultHandlerFactory().createMatcher(
                        new LocalUnregisteredGeneratedOnlyHandler(), config)));
    }

    @Test
    void generatedOnlyModeRejectsPartiallyLoweredRegisteredHandlerInvocations() {
        class LocalPartiallyLoweredGeneratedOnlyHandler {
            @HandleCommand
            String handle() {
                return "handled";
            }

            @HandleCommand
            String handle(String command) {
                return command;
            }
        }

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    registryWithPartiallyUnlowerableHandlerPlan(LocalPartiallyLoweredGeneratedOnlyHandler.class));

            GeneratedOnlyMetadataMode.run(() -> {
                assertThrows(ComponentRegistryException.class,
                             () -> factory().createHandler(
                                     new LocalPartiallyLoweredGeneratedOnlyHandler(), (c, e) -> true, List.of()));
                assertThrows(ComponentRegistryException.class,
                             () -> new ExposedDefaultHandlerFactory().createMatcher(
                                     new LocalPartiallyLoweredGeneratedOnlyHandler(),
                                     HandlerConfiguration.<DeserializingMessage>builder()
                                             .methodAnnotation(HandleCommand.class)
                                             .build()));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeUsesRegistryMatcherWhenGeneratedInvocationIsRegistered() {
        try (var ignored = GeneratedExecutableInvocations.register(
                RegisteredGeneratedOnlyHandler.class,
                InvocationPlanDescriptor.executableId(ExecutableKind.METHOD, "handle", List.of()),
                (target, parameterCount, parameterProvider) -> "generated")) {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = factory().createHandler(
                        new RegisteredGeneratedOnlyHandler(), (c, e) -> true, List.of()).orElseThrow();

                HandlerInvoker invoker = handler.getInvokerOrNull(command());
                assertNull(invoker.getMethod());
                assertEquals("handle", invoker.getExecutableView().name());
                assertEquals("generated", invoker.invoke());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeRespectsLocalHandlerFilterWithRegistryMetadata() {
        try (var ignored = registerGeneratedHandle(RegisteredGeneratedOnlyHandler.class, "generated")) {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> assertTrue(factory().createHandler(
                    new RegisteredGeneratedOnlyHandler(),
                    ClientUtils.localHandlerFilter(),
                    List.of()).isEmpty()));
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyRegistryMatcherExposesMethodMetadataWithoutJvmExecutable() {
        try (var ignored = GeneratedExecutableInvocations.register(
                RegisteredGeneratedOnlyPolicyHandler.class,
                InvocationPlanDescriptor.executableId(ExecutableKind.METHOD, "handle", List.of()),
                (target, parameterCount, parameterProvider) -> "generated")) {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyPolicyHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Handler<DeserializingMessage> handler = factory().createHandler(
                        new RegisteredGeneratedOnlyPolicyHandler(), (c, e) -> true, List.of()).orElseThrow();

                HandlerInvoker invoker = handler.getInvokerOrNull(command());
                assertNull(invoker.getMethod());
                assertTrue(ClientUtils.getLocalHandlerAnnotation(invoker).orElseThrow().logMetrics());
                assertThrows(UnauthenticatedException.class, () -> ValidationUtils.assertAuthorized(
                        invoker.getTargetClass(), invoker.getExecutableView(), null));
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
        try (var ignored = registerGeneratedHandle(PassiveGeneratedOnlyHandler.class, "passive")) {
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
    void generatedOnlyModeUsesGeneratedClasspathRegistryForCompiledLocalSocketEndpointMetadata() {
        GeneratedOnlyMetadataMode.run(() -> assertTrue(webFactory().createHandler(
                GeneratedClasspathSocketEndpoint.class, (c, e) -> true, List.of()).isPresent()));
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
    void generatedOnlyModeExecutesWebHandlerFromRegistryMetadata() {
        try (var ignored = GeneratedExecutableInvocations.register(
                RegisteredWebHandler.class,
                InvocationPlanDescriptor.executableId(ExecutableKind.METHOD, "handle", List.of()),
                (target, parameterCount, parameterProvider) -> "generated-web")) {
            TestFixture fixture = TestFixture.createAsync()
                    .resultTimeout(Duration.ofSeconds(2))
                    .consumerTimeout(Duration.ofSeconds(2));
            fixture.getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredWebHandler.class).registry());
            GeneratedOnlyMetadataMode.run(() -> fixture.getFluxzero().registerHandlers(new RegisteredWebHandler()));

            fixture.whenWebRequest(WebRequest.builder().method(GET).url("/generated").build())
                    .expectResult("generated-web");
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeExecutesSocketRouteFromRegistryMetadata() {
        try (var ignored = GeneratedExecutableInvocations.register(
                RegisteredSocketRouteHandler.class,
                InvocationPlanDescriptor.executableId(ExecutableKind.METHOD, "open", List.of()),
                (target, parameterCount, parameterProvider) -> "generated-socket")) {
            TestFixture fixture = TestFixture.createAsync()
                    .resultTimeout(Duration.ofSeconds(2))
                    .consumerTimeout(Duration.ofSeconds(2));
            fixture.getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredSocketRouteHandler.class).registry());
            GeneratedOnlyMetadataMode.run(() ->
                    fixture.getFluxzero().registerHandlers(new RegisteredSocketRouteHandler()));

            fixture.whenWebRequest(WebRequest.builder().method(WS_OPEN).url("/generated-socket")
                                           .metadata(Metadata.of("sessionId", "generated-session")).build())
                    .expectResult("generated-socket");
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeCreatesTrackSelfHandlerFromRegistryMetadata() {
        try (var ignored = registerGeneratedHandle(RegisteredTrackSelfCommand.class, "self")) {
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
        try (var ignored = GeneratedExecutableInvocations.register(
                RegisteredStatefulHandler.class,
                InvocationPlanDescriptor.executableId(ExecutableKind.METHOD, "handle", List.of(String.class.getName())),
                (target, parameterCount, parameterProvider) -> null)) {
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

    private static GeneratedExecutableInvocations.Registration registerGeneratedHandle(
            Class<?> handlerType, Object result) {
        return GeneratedExecutableInvocations.register(
                handlerType,
                InvocationPlanDescriptor.executableId(ExecutableKind.METHOD, "handle", List.of()),
                (target, parameterCount, parameterProvider) -> result);
    }

    private static ComponentRegistry registryWithUnlowerableHandlerPlan(Class<?> handlerType) {
        ComponentRegistry registry = JvmComponentMetadataLookup.scan(handlerType).registry();
        ComponentDescriptor component = registry.components().getFirst();
        ExecutableDescriptor executable = component.executables().stream()
                .filter(candidate -> candidate.name().equals("handle"))
                .findFirst().orElseThrow();
        ExecutableDescriptor unlowerableExecutable = new ExecutableDescriptor(
                executable.kind(),
                executable.name(),
                executable.returnTypeName(),
                executable.returnTypeUse(),
                List.of(new ParameterDescriptor(
                        "missing", MissingGeneratedOnlyInvocationParameter.class.getName(), List.of())),
                executable.annotations(),
                executable.isStatic());
        ComponentDescriptor unlowerableComponent = new ComponentDescriptor(
                component.sourceFile(),
                component.packageInfoSource(),
                component.componentKind(),
                component.packageName(),
                component.className(),
                component.superTypeNames(),
                component.annotations(),
                component.properties(),
                List.of(unlowerableExecutable),
                component.handlerRoutes(),
                component.registeredTypes(),
                component.consumer(),
                component.capabilities());
        return new ComponentRegistry(registry.sourceRoot(), registry.packages(), List.of(unlowerableComponent));
    }

    private static ComponentRegistry registryWithPartiallyUnlowerableHandlerPlan(Class<?> handlerType) {
        ComponentRegistry registry = JvmComponentMetadataLookup.scan(handlerType).registry();
        ComponentDescriptor component = registry.components().getFirst();
        List<ExecutableDescriptor> executables = component.executables().stream()
                .map(executable -> executable.parameters().isEmpty()
                        ? executable : unlowerableExecutable(executable))
                .toList();
        ComponentDescriptor partialComponent = new ComponentDescriptor(
                component.sourceFile(),
                component.packageInfoSource(),
                component.componentKind(),
                component.packageName(),
                component.className(),
                component.superTypeNames(),
                component.annotations(),
                component.properties(),
                executables,
                component.handlerRoutes(),
                component.registeredTypes(),
                component.consumer(),
                component.capabilities());
        return new ComponentRegistry(registry.sourceRoot(), registry.packages(), List.of(partialComponent));
    }

    private static ExecutableDescriptor unlowerableExecutable(ExecutableDescriptor executable) {
        return new ExecutableDescriptor(
                executable.kind(),
                executable.name(),
                executable.returnTypeName(),
                executable.returnTypeUse(),
                List.of(new ParameterDescriptor(
                        "missing", MissingGeneratedOnlyInvocationParameter.class.getName(), List.of())),
                executable.annotations(),
                executable.isStatic());
    }

    private static DefaultHandlerFactory factory() {
        return factory(MessageType.COMMAND);
    }

    private static DefaultHandlerFactory factory(MessageType messageType) {
        return new DefaultHandlerFactory(
                messageType, HandlerDecorator.noOp, List.of(), MethodInvocationValidator.noOp(),
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

    private static class ExposedDefaultHandlerFactory extends DefaultHandlerFactory {
        private ExposedDefaultHandlerFactory() {
            super(MessageType.COMMAND, HandlerDecorator.noOp, List.of(), MethodInvocationValidator.noOp(),
                  c -> null, null, false, null);
        }

        HandlerMatcher<Object, DeserializingMessage> createMatcher(
                Object target, HandlerConfiguration<DeserializingMessage> config) {
            return createHandlerMatcher(target, config);
        }
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

    private static DeserializingMessage message(MessageType messageType, String topic) {
        return new DeserializingMessage(new Message("payload"), messageType, topic, null);
    }

    private static List<HandlerCase> nonWebHandlerCases() {
        return List.of(
                new HandlerCase(MessageType.COMMAND, new GeneratedCommandHandler(), null, "command"),
                new HandlerCase(MessageType.QUERY, new GeneratedQueryHandler(), null, "query"),
                new HandlerCase(MessageType.EVENT, new GeneratedEventHandler(), null, "event"),
                new HandlerCase(MessageType.NOTIFICATION, new GeneratedNotificationHandler(), null, "notification"),
                new HandlerCase(MessageType.ERROR, new GeneratedErrorHandler(), null, "error"),
                new HandlerCase(MessageType.METRICS, new GeneratedMetricsHandler(), null, "metrics"),
                new HandlerCase(MessageType.RESULT, new GeneratedResultHandler(), null, "result"),
                new HandlerCase(MessageType.CUSTOM, new GeneratedCustomHandler(), "custom-topic", "custom"),
                new HandlerCase(MessageType.DOCUMENT, new GeneratedDocumentHandler(), "documents", "document"),
                new HandlerCase(MessageType.SCHEDULE, new GeneratedScheduleHandler(), null, "schedule"),
                new HandlerCase(MessageType.WEBRESPONSE, new GeneratedWebResponseHandler(), null, "webresponse"));
    }

    private record HandlerCase(MessageType messageType, Object target, String topic, String expectedResult) {
        Class<?> handlerType() {
            return target.getClass();
        }
    }

    private static class RegisteredGeneratedOnlyHandler {
        @HandleCommand
        String handle() {
            return "handled";
        }
    }

    private static class GeneratedClasspathHandler {
        @HandleCommand
        String handle() {
            return "classpath";
        }
    }

    private static class GeneratedCommandHandler {
        @HandleCommand
        String handle() {
            return "command";
        }
    }

    private static class GeneratedQueryHandler {
        @HandleQuery
        String handle() {
            return "query";
        }
    }

    private static class GeneratedEventHandler {
        @HandleEvent
        String handle() {
            return "event";
        }
    }

    private static class GeneratedNotificationHandler {
        @HandleNotification
        String handle() {
            return "notification";
        }
    }

    private static class GeneratedErrorHandler {
        @HandleError
        String handle() {
            return "error";
        }
    }

    private static class GeneratedMetricsHandler {
        @HandleMetrics
        String handle() {
            return "metrics";
        }
    }

    private static class GeneratedResultHandler {
        @HandleResult
        String handle() {
            return "result";
        }
    }

    private static class GeneratedCustomHandler {
        @HandleCustom("custom-topic")
        String handle() {
            return "custom";
        }
    }

    private static class GeneratedDocumentHandler {
        @HandleDocument("documents")
        String handle() {
            return "document";
        }
    }

    private static class GeneratedScheduleHandler {
        @HandleSchedule
        String handle() {
            return "schedule";
        }
    }

    private static class GeneratedWebResponseHandler {
        @HandleWebResponse
        String handle() {
            return "webresponse";
        }
    }

    private static class RegisteredGeneratedOnlyPolicyHandler {
        @HandleCommand
        @LocalHandler(logMetrics = true)
        @RequiresUser
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

    @SocketEndpoint
    private static class GeneratedClasspathSocketEndpoint {
        @HandleSocketOpen
        String open() {
            return "socket";
        }
    }

    @SocketEndpoint(aliveCheck = @SocketEndpoint.AliveCheck(
            timeUnit = TimeUnit.MILLISECONDS, pingDelay = 7, pingTimeout = 3))
    private static class RegisteredSocketEndpoint {
        @HandleSocketOpen
        void open() {
        }
    }

    private static class RegisteredWebHandler {
        @HandleGet("/generated")
        String handle() {
            return "web";
        }
    }

    private static class RegisteredSocketRouteHandler {
        @HandleSocketOpen("/generated-socket")
        String open() {
            return "socket";
        }
    }

    private record AllowedGeneratedOnlyCommand() {
    }

    private record MissingGeneratedOnlyInvocationParameter() {
    }
}
