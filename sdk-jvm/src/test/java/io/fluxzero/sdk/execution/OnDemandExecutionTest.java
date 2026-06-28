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

package io.fluxzero.sdk.execution;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.registry.ComponentCapability;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.ComponentRegistryJson;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.SourceComponentScanner;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OnDemandExecutionTest {

    @Test
    void indexesHandlerSourceWithoutCompiling(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "IndexedLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionEvent;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleEvent;

                public class IndexedLogic {
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return command.value();
                    }

                    @HandleEvent
                    public void on(ExecutionEvent event) {
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        List<ComponentDescriptor> units = registry.components();

        assertEquals(tempDir, registry.sourceRoot());
        assertEquals(java.util.Set.of(MessageType.COMMAND, MessageType.EVENT), registry.messageTypes());
        assertEquals(1, units.size());
        ComponentDescriptor unit = units.getFirst();
        assertEquals("io.fluxzero.sdk.execution.generated.IndexedLogic", unit.fullClassName());
        assertEquals(java.util.Set.of(MessageType.COMMAND, MessageType.EVENT), unit.messageTypes());
        assertTrue(unit.handlerRoutes().stream().noneMatch(HandlerRoute::local));
        assertTrue(unit.capabilities().contains(ComponentCapability.SOURCE_COMPONENT));
        assertTrue(unit.capabilities().contains(ComponentCapability.HANDLER));
        assertTrue(unit.capabilities().contains(ComponentCapability.TRACKING_HANDLER));
        assertTrue(unit.canHandle(MessageType.COMMAND, ExecutionCommand.class));
        assertTrue(unit.canHandle(MessageType.EVENT, ExecutionEvent.class));
    }

    @Test
    void indexesLocalHandlerSemantics(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "LocalSemanticsLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionEvent;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionQuery;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleEvent;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @LocalHandler
                public class LocalSemanticsLogic {
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return command.value();
                    }

                    @LocalHandler(false)
                    @HandleEvent
                    public void on(ExecutionEvent event) {
                    }

                    @LocalHandler(allowExternalMessages = true)
                    @HandleQuery
                    public String query(ExecutionQuery query) {
                        return query.value();
                    }
                }
                """);

        ComponentDescriptor unit = new SourceComponentScanner().scan(tempDir).components().getFirst();
        HandlerRoute command = route(unit, MessageType.COMMAND);
        HandlerRoute event = route(unit, MessageType.EVENT);
        HandlerRoute query = route(unit, MessageType.QUERY);

        assertTrue(command.local());
        assertTrue(!command.tracked());
        assertTrue(!event.local());
        assertTrue(event.tracked());
        assertTrue(query.local());
        assertTrue(query.tracked());
    }

    @Test
    void indexesPackageLocalHandlerSemantics(@TempDir Path tempDir) throws Exception {
        writePackageInfo(
                tempDir, "io.fluxzero.sdk.tracking.handling.LocalHandler(allowExternalMessages = true)");
        writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("package", ""));

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        HandlerRoute command = route(registry.components().getFirst(), MessageType.COMMAND);

        assertEquals(1, registry.packages().size());
        assertEquals("io.fluxzero.sdk.execution.generated", registry.packages().getFirst().packageName());
        assertTrue(registry.packages().getFirst().capabilities().contains(ComponentCapability.PACKAGE_LOCAL_HANDLER));
        assertTrue(command.local());
        assertTrue(command.tracked());
    }

    @Test
    void handlesCommandFromLocalSource(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("v1", "@LocalHandler"));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenCommand(new ExecutionCommand("one")).expectResult("v1:one");
        }
    }

    @Test
    void handlesPackageLocalSource(@TempDir Path tempDir) throws Exception {
        writePackageInfo(tempDir, "io.fluxzero.sdk.tracking.handling.LocalHandler");
        writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("package", ""));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenCommand(new ExecutionCommand("one")).expectResult("package:one");
        }
    }

    @Test
    void handlesPackageLocalSourceFromChildPackage(@TempDir Path tempDir) throws Exception {
        writePackageInfo(tempDir, "io.fluxzero.sdk.execution.generated",
                         "io.fluxzero.sdk.tracking.handling.LocalHandler");
        writeHandlerSource(tempDir, "io.fluxzero.sdk.execution.generated.child", "ChildCommandLogic",
                           commandHandlerSource("child", ""));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenCommand(new ExecutionCommand("one")).expectResult("child:one");
        }
    }

    @Test
    void validatesRequestHandlerReturnTypeDuringCompilation(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "InvalidRequestLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionRequest;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class InvalidRequestLogic {
                    @LocalHandler
                    @HandleQuery
                    public Integer handle(ExecutionRequest query) {
                        return 1;
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenQuery(new ExecutionRequest("one")).expectExceptionalResult(OnDemandCompilationException.class);
        }
    }

    @Test
    void handlesWebSourceWithGeneratedParameterRegistry(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "WebLogic", """
                import io.fluxzero.common.reflection.ParameterRegistry;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.PathParam;

                public class WebLogic {
                    @LocalHandler
                    @HandleGet("/web/{id}")
                    public String get(@PathParam String id) throws Exception {
                        var method = getClass().getDeclaredMethod("get", String.class);
                        return ParameterRegistry.of(getClass()).getParameterNames(method).getFirst() + ":" + id;
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenGet("/web/42").expectResult("id:42");
        }
    }

    @Test
    void doesNotLoadNonMatchingWebSourceRoutes(@TempDir Path tempDir) throws Exception {
        LoadCounter.reset();
        writeHandlerSource(tempDir, "MatchingWebLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.PathParam;

                @LocalHandler
                public class MatchingWebLogic {
                    static {
                        LoadCounter.loaded();
                    }

                    @HandleGet("/web/match/{id}")
                    public String get(@PathParam String id) {
                        return "match:" + id;
                    }
                }
                """);
        writeHandlerSource(tempDir, "OtherWebLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;

                @LocalHandler
                public class OtherWebLogic {
                    static {
                        LoadCounter.loaded();
                    }

                    @HandleGet("/web/other/{id}")
                    public String get() {
                        return "other";
                    }
                }
                """);

        HandlerRoute webRoute = route(new SourceComponentScanner().scan(tempDir).components().getFirst(),
                                      MessageType.WEBREQUEST);
        assertTrue(webRoute.hasWildcardPayload());
        assertTrue(webRoute.hasWebRouteMetadata());

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenGet("/web/match/42").expectResult("match:42");
        }

        assertEquals(1, LoadCounter.loadedCount());
    }

    @Test
    void builderRegistersOnDemandExecution(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("builder", "@LocalHandler"));

        TestFixture.create(DefaultFluxzero.builder().executionMode(ExecutionMode.onDemand(tempDir)))
                .whenCommand(new ExecutionCommand("one")).expectResult("builder:one");
    }

    @Test
    void builderAutoRegistersGeneratedFluxzeroSourceRoot(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeHandlerSource(sourceRoot, "CommandLogic", commandHandlerSource("auto", "@LocalHandler"));
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder())
                    .whenCommand(new ExecutionCommand("one")).expectResult("auto:one");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void builderCanDisableConventionalSourceChangeChecks(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        Path source = writeHandlerSource(sourceRoot, "CommandLogic", commandHandlerSource("auto-v1", "@LocalHandler"));
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture fixture = TestFixture.create(DefaultFluxzero.builder()
                    .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                            OnDemandExecution.CHECK_SOURCE_CHANGES_PROPERTY, "false")).andThen(existing)));

            fixture.whenCommand(new ExecutionCommand("one")).expectResult("auto-v1:one");
            Files.writeString(source, source("CommandLogic", commandHandlerSource("auto-v2", "@LocalHandler")));
            fixture.whenCommand(new ExecutionCommand("two")).expectResult("auto-v1:two");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void builderAutoRegistersGeneratedFluxzeroTestSourceRoot(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/test/fluxzero");
        writeHandlerSource(sourceRoot, "CommandLogic", commandHandlerSource("test-auto", "@LocalHandler"));
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path testClasses = tempDir.resolve("test-classes");
        ComponentRegistryJson.write(registry, testClasses.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{testClasses.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder())
                    .whenCommand(new ExecutionCommand("one")).expectResult("test-auto:one");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void generatedFluxzeroTestSourceRootCanUseMainFluxzeroSourceRoot(@TempDir Path tempDir) throws Exception {
        Path mainSourceRoot = tempDir.resolve("src/main/fluxzero");
        Path testSourceRoot = tempDir.resolve("src/test/fluxzero");
        writePackagedSource(mainSourceRoot, "io.fluxzero.sdk.execution.generated", "SharedFormatter", """
                public class SharedFormatter {
                    public static String format(String value) {
                        return "shared:" + value;
                    }
                }
                """);
        writePackagedSource(testSourceRoot, "io.fluxzero.sdk.execution.generated", "TestCommandLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class TestCommandLogic {
                    @LocalHandler
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return SharedFormatter.format(command.value());
                    }
                }
                """);
        ComponentRegistry mainRegistry = new SourceComponentScanner().scan(mainSourceRoot);
        ComponentRegistry testRegistry = new SourceComponentScanner().scan(testSourceRoot);
        Path classes = tempDir.resolve("classes");
        Path testClasses = tempDir.resolve("test-classes");
        ComponentRegistryJson.write(mainRegistry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));
        ComponentRegistryJson.write(testRegistry, testClasses.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{testClasses.toUri().toURL(), classes.toUri().toURL()},
                OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder())
                    .whenCommand(new ExecutionCommand("one")).expectResult("shared:one");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void builderAutoAppliesGeneratedFluxzeroSourceInfrastructure(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeHandlerSource(sourceRoot, "CommandLogic", commandHandlerSource("auto", "@LocalHandler"));
        writeHandlerSource(sourceRoot, "SourceHandlerInterceptor", """
                import io.fluxzero.common.handling.HandlerInvoker;
                import io.fluxzero.sdk.common.serialization.DeserializingMessage;
                import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
                import java.util.function.Function;

                public class SourceHandlerInterceptor implements HandlerInterceptor {
                    @Override
                    public Function<DeserializingMessage, Object> interceptHandling(
                            Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
                        return message -> "infra:" + function.apply(message);
                    }
                }
                """);
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder())
                    .whenCommand(new ExecutionCommand("one")).expectResult("infra:auto:one");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void builderOrdersGeneratedFluxzeroSourceInfrastructure(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeHandlerSource(sourceRoot, "CommandLogic", commandHandlerSource("auto", "@LocalHandler"));
        writeHandlerSource(sourceRoot, "ALowPriorityInterceptor", """
                import io.fluxzero.common.handling.HandlerInvoker;
                import io.fluxzero.sdk.common.Order;
                import io.fluxzero.sdk.common.serialization.DeserializingMessage;
                import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
                import java.util.function.Function;

                @Order(20)
                public class ALowPriorityInterceptor implements HandlerInterceptor {
                    @Override
                    public Function<DeserializingMessage, Object> interceptHandling(
                            Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
                        return message -> "low(" + function.apply(message) + ")";
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "ZHighPriorityInterceptor", """
                import io.fluxzero.common.handling.HandlerInvoker;
                import io.fluxzero.sdk.common.Order;
                import io.fluxzero.sdk.common.serialization.DeserializingMessage;
                import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
                import java.util.function.Function;

                @Order(-20)
                public class ZHighPriorityInterceptor implements HandlerInterceptor {
                    @Override
                    public Function<DeserializingMessage, Object> interceptHandling(
                            Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
                        return message -> "high(" + function.apply(message) + ")";
                    }
                }
                """);
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder())
                    .whenCommand(new ExecutionCommand("one")).expectResult("high(low(auto:one))");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void builderInstantiatesSourceInfrastructureWithFluxzeroConfiguration(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeHandlerSource(sourceRoot, "CommandLogic", commandHandlerSource("auto", "@LocalHandler"));
        writeHandlerSource(sourceRoot, "ConfiguredHandlerInterceptor", """
                import io.fluxzero.common.handling.HandlerInvoker;
                import io.fluxzero.sdk.common.serialization.DeserializingMessage;
                import io.fluxzero.sdk.configuration.FluxzeroConfiguration;
                import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
                import java.util.function.Function;

                public class ConfiguredHandlerInterceptor implements HandlerInterceptor {
                    private final String prefix;

                    public ConfiguredHandlerInterceptor(FluxzeroConfiguration configuration) {
                        this.prefix = configuration.propertySource().get("source.infra.prefix", "missing");
                    }

                    @Override
                    public Function<DeserializingMessage, Object> interceptHandling(
                            Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
                        return message -> prefix + ":" + function.apply(message);
                    }
                }
                """);
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder()
                    .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                            "source.infra.prefix", "configured")).andThen(existing)))
                    .whenCommand(new ExecutionCommand("one")).expectResult("configured:auto:one");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void builderAppliesGeneratedFluxzeroSourceIdentityProvider(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeHandlerSource(sourceRoot, "SourceIdentityProvider", """
                import io.fluxzero.sdk.common.IdentityProvider;

                public class SourceIdentityProvider implements IdentityProvider {
                    @Override
                    public String nextFunctionalId() {
                        return "source-functional";
                    }

                    @Override
                    public String idForName(String name) {
                        return "source-" + name;
                    }

                    @Override
                    public String nextTechnicalId() {
                        return "source-technical";
                    }
                }
                """);
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder())
                    .whenApplying(fluxzero -> fluxzero.identityProvider().nextTechnicalId())
                    .expectResult("source-technical");
            TestFixture.create(DefaultFluxzero.builder())
                    .whenApplying(fluxzero -> fluxzero.identityProvider().idForName("customer"))
                    .expectResult("source-customer");
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void builderAppliesGeneratedFluxzeroSourceConfigurationComponents(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeHandlerSource(sourceRoot, "SourceSerializer", """
                import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;

                public class SourceSerializer extends JacksonSerializer {
                }
                """);
        writeHandlerSource(sourceRoot, "SourceDefaultResponseMapper", """
                import io.fluxzero.common.api.Metadata;
                import io.fluxzero.sdk.common.Message;
                import io.fluxzero.sdk.tracking.handling.DefaultResponseMapper;

                public class SourceDefaultResponseMapper extends DefaultResponseMapper {
                    @Override
                    public Message map(Object response) {
                        return new Message("default:" + response, Metadata.empty());
                    }

                    @Override
                    public Message map(Object response, Metadata metadata) {
                        return new Message("default:" + response, metadata);
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceWebResponseMapper", """
                import io.fluxzero.common.api.Metadata;
                import io.fluxzero.sdk.web.DefaultWebResponseMapper;
                import io.fluxzero.sdk.web.WebResponse;

                public class SourceWebResponseMapper extends DefaultWebResponseMapper {
                    @Override
                    public WebResponse map(Object response, Metadata metadata) {
                        return WebResponse.builder().status(200).payload("web:" + response).build();
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceValidator", """
                import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
                import io.fluxzero.sdk.tracking.handling.validation.Validator;
                import java.util.Optional;

                public class SourceValidator implements Validator {
                    @Override
                    public <T> Optional<ValidationException> checkValidity(T object, Class<?>... groups) {
                        return Optional.empty();
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceParameterResolver", """
                import io.fluxzero.common.handling.ParameterResolver;
                import io.fluxzero.sdk.common.serialization.DeserializingMessage;
                import java.lang.annotation.Annotation;
                import java.lang.reflect.Parameter;
                import java.util.function.Function;

                public class SourceParameterResolver implements ParameterResolver<DeserializingMessage> {
                    @Override
                    public Function<DeserializingMessage, Object> resolve(
                            Parameter parameter, Annotation methodAnnotation) {
                        return null;
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceCorrelationDataProvider", """
                import io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider;
                import java.util.Map;

                public class SourceCorrelationDataProvider implements CorrelationDataProvider {
                    @Override
                    public Map<String, String> getCorrelationData() {
                        return Map.of("source", "correlation");
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceUserProvider", """
                import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider;
                import io.fluxzero.sdk.tracking.handling.authentication.User;

                public class SourceUserProvider extends AbstractUserProvider {
                    public SourceUserProvider() {
                        super(SourceUser.class);
                    }

                    @Override
                    public User getUserById(Object userId) {
                        return new SourceUser("source-" + userId);
                    }

                    @Override
                    public User getSystemUser() {
                        return new SourceUser("source-system");
                    }

                    public static class SourceUser implements User {
                        private final String name;

                        public SourceUser(String name) {
                            this.name = name;
                        }

                        @Override
                        public boolean hasRole(String role) {
                            return "source".equals(role);
                        }

                        @Override
                        public String getName() {
                            return name;
                        }
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceCache", """
                import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;

                public class SourceCache extends SoftReferenceCache {
                }
                """);
        writeHandlerSource(sourceRoot, "SourceTaskScheduler", """
                import io.fluxzero.common.InMemoryTaskScheduler;

                public class SourceTaskScheduler extends InMemoryTaskScheduler {
                    public SourceTaskScheduler() {
                        super("source-task-scheduler");
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourcePropertySource", """
                import io.fluxzero.common.application.PropertySource;

                public class SourcePropertySource implements PropertySource {
                    @Override
                    public String get(String name) {
                        return "source.component.property".equals(name) ? "source-property" : null;
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceDispatchInterceptor", """
                import io.fluxzero.common.MessageType;
                import io.fluxzero.sdk.common.Message;
                import io.fluxzero.sdk.publishing.DispatchInterceptor;

                public class SourceDispatchInterceptor implements DispatchInterceptor {
                    @Override
                    public Message interceptDispatch(Message message, MessageType messageType, String topic) {
                        return message;
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceHandlerDecorator", """
                import io.fluxzero.common.handling.Handler;
                import io.fluxzero.sdk.common.serialization.DeserializingMessage;
                import io.fluxzero.sdk.tracking.handling.HandlerDecorator;

                public class SourceHandlerDecorator implements HandlerDecorator {
                    @Override
                    public Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
                        return handler;
                    }
                }
                """);
        writeHandlerSource(sourceRoot, "SourceBatchInterceptor", """
                import io.fluxzero.common.api.tracking.MessageBatch;
                import io.fluxzero.sdk.tracking.BatchInterceptor;
                import io.fluxzero.sdk.tracking.Tracker;
                import java.util.function.Consumer;

                public class SourceBatchInterceptor implements BatchInterceptor {
                    @Override
                    public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
                        return consumer;
                    }
                }
                """);
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            TestFixture.create(DefaultFluxzero.builder())
                    .whenApplying(fluxzero -> {
                        fluxzero.cache().put("source", "cache");
                        return String.join("|", List.of(
                                fluxzero.serializer().getClass().getSimpleName(),
                                fluxzero.configuration().snapshotSerializer().getClass().getSimpleName(),
                                fluxzero.configuration().documentSerializer().getClass().getSimpleName(),
                                fluxzero.configuration().defaultResponseMapper().map("payload").getPayload(),
                                fluxzero.configuration().webResponseMapper().map("payload").getPayload(),
                                fluxzero.configuration().validator().getClass().getSimpleName(),
                                Boolean.toString(fluxzero.configuration().parameterResolvers().stream().anyMatch(
                                        resolver -> resolver.getClass().getSimpleName().equals(
                                                "SourceParameterResolver"))),
                                fluxzero.correlationDataProvider().getCorrelationData().get("source"),
                                fluxzero.userProvider().getSystemUser().getName(),
                                fluxzero.cache().getClass().getSimpleName(),
                                fluxzero.cache().get("source"),
                                fluxzero.taskScheduler().getClass().getSimpleName(),
                                fluxzero.propertySource().get("source.component.property"),
                                Boolean.toString(fluxzero.configuration().dispatchInterceptors()
                                        .get(MessageType.COMMAND).stream().anyMatch(
                                                interceptor -> interceptor.getClass().getSimpleName().equals(
                                                        "SourceDispatchInterceptor"))),
                                Boolean.toString(fluxzero.configuration().handlerDecorators()
                                        .get(MessageType.COMMAND).stream().anyMatch(
                                                decorator -> decorator.getClass().getSimpleName().equals(
                                                        "SourceHandlerDecorator"))),
                                Boolean.toString(fluxzero.configuration().batchInterceptors()
                                        .get(MessageType.COMMAND).stream().anyMatch(
                                                interceptor -> interceptor.getClass().getSimpleName().equals(
                                                        "SourceBatchInterceptor")))));
                    })
                    .expectResult(String.join("|", List.of(
                            "SourceSerializer",
                            "SourceSerializer",
                            "SourceSerializer",
                            "default:payload",
                            "web:payload",
                            "SourceValidator",
                            "true",
                            "correlation",
                            "source-system",
                            "SourceCache",
                            "cache",
                            "SourceTaskScheduler",
                            "source-property",
                            "true",
                            "true",
                            "true")));
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Test
    void prefersGeneratedRegistryResourceOverRuntimeSourceScan(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeHandlerSource(sourceRoot, "CommandLogic", commandHandlerSource("generated", "@LocalHandler"));
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);
        Path classes = tempDir.resolve("classes");
        ComponentRegistryJson.write(registry, classes.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        writeHandlerSource(sourceRoot, "ExtraQueryLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionQuery;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class ExtraQueryLogic {
                    @LocalHandler
                    @HandleQuery
                    public String query(ExecutionQuery query) {
                        return "scanned:" + query.value();
                    }
                }
                """);

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, OnDemandExecutionTest.class.getClassLoader());
             OnDemandExecution execution = OnDemandExecution.builder()
                     .sourceRoot(sourceRoot)
                     .cacheRoot(tempDir.resolve("cache"))
                     .parentClassLoader(classLoader)
                     .scanSourceWhenRegistryMissing(false)
                     .startTracking(false)
                     .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            fixture.whenCommand(new ExecutionCommand("one")).expectResult("generated:one");
            fixture.resultTimeout(Duration.ofMillis(100))
                    .whenQuery(new ExecutionQuery("one")).expectExceptionalResult(TimeoutException.class);
        }
    }

    @Test
    void sharesCompiledSourceUnitAcrossRoutes(@TempDir Path tempDir) throws Exception {
        LoadCounter.reset();
        writeHandlerSource(tempDir, "MultiRouteLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionQuery;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class MultiRouteLogic {
                    static {
                        LoadCounter.loaded();
                    }

                    @LocalHandler
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return "command:" + command.value();
                    }

                    @LocalHandler
                    @HandleQuery
                    public String query(ExecutionQuery query) {
                        return "query:" + query.value();
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            fixture.whenCommand(new ExecutionCommand("one")).expectResult("command:one");
            fixture.whenQuery(new ExecutionQuery("two")).expectResult("query:two");
        }

        assertEquals(1, LoadCounter.loadedCount());
    }

    @Test
    void loadsAndInvokesSourcePayloadSelfHandler(@TempDir Path tempDir) throws Exception {
        LoadCounter.reset();
        writeHandlerSource(tempDir, "SourceSelfQuery", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;

                public class SourceSelfQuery {
                    static {
                        LoadCounter.loaded();
                    }

                    private final String value;

                    public SourceSelfQuery(String value) {
                        this.value = value;
                    }

                    @HandleQuery
                    public String handle() {
                        return "source:" + value;
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        HandlerRoute route = route(
                registry.findComponent("io.fluxzero.sdk.execution.generated.SourceSelfQuery").orElseThrow(),
                MessageType.QUERY);
        assertTrue(route.local());
        assertTrue(!route.tracked());
        assertEquals(java.util.Set.of("io.fluxzero.sdk.execution.generated.SourceSelfQuery"),
                     route.payloadTypeNames());

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            assertTrue(execution.loadType("io.fluxzero.sdk.execution.generated.Missing").isEmpty());
            Class<?> queryType = execution.loadType(
                    "io.fluxzero.sdk.execution.generated.SourceSelfQuery").orElseThrow();
            Object query = queryType.getConstructor(String.class).newInstance("one");

            fixture.whenQuery(query).expectResult("source:one");
            assertEquals(1, LoadCounter.loadedCount());
        }
    }

    @Test
    void sourceTypeResolverUsesUpcastedSerializedType(@TempDir Path tempDir) throws Exception {
        LoadCounter.reset();
        writeHandlerSource(tempDir, "SourceTypedPayload", """
                import io.fluxzero.common.serialization.RegisterType;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;

                @RegisterType
                public class SourceTypedPayload {
                    static {
                        LoadCounter.loaded();
                    }

                    public String value;

                    public SourceTypedPayload() {
                    }
                }
                """);
        String legacyType = "legacy.SourceTypedPayload";
        String sourceType = "io.fluxzero.sdk.execution.generated.SourceTypedPayload";
        SerializedMessage message = new SerializedMessage(
                new Data<>("{\"value\":\"wire\"}".getBytes(UTF_8), legacyType, 0, Data.JSON_FORMAT),
                Metadata.empty(), "message-id", 0L);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                fc.serializer().registerTypeCaster(legacyType, sourceType);
                execution.registerWith(fc);
                return List.of();
            });

            fixture.whenApplying(fc -> {
                var deserialized = fc.serializer().deserializeMessage(message, MessageType.QUERY);
                Class<?> payloadClass = deserialized.getPayloadClass();
                Object payload = deserialized.getPayload();
                assertEquals(sourceType, deserialized.getType());
                assertEquals(sourceType, payloadClass.getName());
                assertEquals(payloadClass, payload.getClass());
                return payloadClass.getField("value").get(payload);
            }).expectResult("wire");
            assertEquals(1, LoadCounter.loadedCount());
        }
    }

    @Test
    void prewarmsRegisteredSourceHandlersBeforeInvocation(@TempDir Path tempDir) throws Exception {
        LoadCounter.reset();
        writeHandlerSource(tempDir, "PrewarmCommandLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @LocalHandler
                public class PrewarmCommandLogic {
                    static {
                        LoadCounter.loaded();
                    }

                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return "command:" + command.value();
                    }
                }
                """);
        writeHandlerSource(tempDir, "PrewarmQueryLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionQuery;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @LocalHandler
                public class PrewarmQueryLogic {
                    static {
                        LoadCounter.loaded();
                    }

                    @HandleQuery
                    public String query(ExecutionQuery query) {
                        return "query:" + query.value();
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });
            assertEquals(0, LoadCounter.loadedCount());

            execution.prewarm();
            assertEquals(2, LoadCounter.loadedCount());

            fixture.whenCommand(new ExecutionCommand("one")).expectResult("command:one");
            fixture.whenQuery(new ExecutionQuery("two")).expectResult("query:two");
            assertEquals(2, LoadCounter.loadedCount());
        }
    }

    @Test
    void prewarmsOnlySelectedMessageTypes(@TempDir Path tempDir) throws Exception {
        LoadCounter.reset();
        writeHandlerSource(tempDir, "SelectedCommandLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @LocalHandler
                public class SelectedCommandLogic {
                    static {
                        LoadCounter.loaded();
                    }

                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return "command:" + command.value();
                    }
                }
                """);
        writeHandlerSource(tempDir, "SelectedQueryLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionQuery;
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.LoadCounter;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @LocalHandler
                public class SelectedQueryLogic {
                    static {
                        LoadCounter.loaded();
                    }

                    @HandleQuery
                    public String query(ExecutionQuery query) {
                        return "query:" + query.value();
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            execution.prewarm(MessageType.COMMAND);
            assertEquals(1, LoadCounter.loadedCount());

            fixture.whenQuery(new ExecutionQuery("two")).expectResult("query:two");
            assertEquals(2, LoadCounter.loadedCount());
        }
    }

    @Test
    void recompilesChangedSourceOnNextInvocation(@TempDir Path tempDir) throws Exception {
        Path source = writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("v1", "@LocalHandler"));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .cacheTtl(Duration.ofMinutes(10))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            fixture.whenCommand(new ExecutionCommand("one")).expectResult("v1:one");
            Files.writeString(source, source("CommandLogic", commandHandlerSource("v2", "@LocalHandler")));
            fixture.whenCommand(new ExecutionCommand("two")).expectResult("v2:two");
        }
    }

    @Test
    void recompilesWhenSourceDependencyChanges(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "CommandLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class CommandLogic {
                    @LocalHandler
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return CommandFormatter.format(command.value());
                    }
                }
                """);
        Path helper = writeHandlerSource(tempDir, "CommandFormatter", """
                public class CommandFormatter {
                    public static String format(String value) {
                        return "helper-v1:" + value;
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .cacheTtl(Duration.ofMinutes(10))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            assertTrue(execution.loadType("io.fluxzero.sdk.execution.generated.CommandFormatter").isPresent());
            fixture.whenCommand(new ExecutionCommand("one")).expectResult("helper-v1:one");
            Files.writeString(helper, source("CommandFormatter", """
                    public class CommandFormatter {
                        public static String format(String value) {
                            return "helper-v2:" + value;
                        }
                    }
                    """));
            fixture.whenCommand(new ExecutionCommand("two")).expectResult("helper-v2:two");
        }
    }

    @Test
    void prewarmCopiesSourceDependencyClassesIntoUnitCache(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "PrewarmWithHelperLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @LocalHandler
                public class PrewarmWithHelperLogic {
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return PrewarmFormatter.format(command.value());
                    }
                }
                """);
        writeHandlerSource(tempDir, "PrewarmFormatter", """
                public class PrewarmFormatter {
                    public static String format(String value) {
                        return "prewarm-helper:" + value;
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            execution.prewarm();
            fixture.whenCommand(new ExecutionCommand("one")).expectResult("prewarm-helper:one");
        }
    }

    @Test
    void canDisableSourceChangeChecksOnInvocation(@TempDir Path tempDir) throws Exception {
        Path source = writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("v1", "@LocalHandler"));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .cacheTtl(Duration.ofMinutes(10))
                .checkSourceChangesOnInvocation(false)
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            fixture.whenCommand(new ExecutionCommand("one")).expectResult("v1:one");
            Files.writeString(source, source("CommandLogic", commandHandlerSource("v2", "@LocalHandler")));
            fixture.whenCommand(new ExecutionCommand("two")).expectResult("v1:two");
        }
    }

    @Test
    void canOptIntoImmediateIdleEviction(@TempDir Path tempDir) throws Exception {
        Path source = writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("v1", "@LocalHandler"));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .cacheTtl(Duration.ZERO)
                .checkSourceChangesOnInvocation(false)
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            fixture.whenCommand(new ExecutionCommand("one")).expectResult("v1:one");
            Files.writeString(source, source("CommandLogic", commandHandlerSource("v2", "@LocalHandler")));
            fixture.whenCommand(new ExecutionCommand("two")).expectResult("v2:two");
        }
    }

    @Test
    void handlesCommandThroughTrackingWhenLocalRegistrationIsDisabled(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "AsyncCommandLogic", commandHandlerSource("async", ""));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .registerLocalHandlers(false)
                .build()) {
            TestFixture.createAsync(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenCommand(new ExecutionCommand("one")).expectResult("async:one");
        }
    }

    @Test
    void nonLocalSourceDoesNotBypassLocalHandlerRules(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("nonlocal", ""));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).resultTimeout(Duration.ofMillis(100))
                    .whenCommand(new ExecutionCommand("one")).expectExceptionalResult(TimeoutException.class);
        }
    }

    @Test
    void localOnlySourceDoesNotStartTracking(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("local", "@LocalHandler"));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .registerLocalHandlers(false)
                .build()) {
            TestFixture.createAsync(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).resultTimeout(Duration.ofMillis(100))
                    .whenCommand(new ExecutionCommand("one")).expectExceptionalResult(TimeoutException.class);
        }
    }

    @Test
    void refreshRegistersNewSourceHandlerWithoutRestart(@TempDir Path tempDir) throws Exception {
        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).resultTimeout(Duration.ofMillis(100));

            fixture.whenCommand(new ExecutionCommand("before"))
                    .expectExceptionalResult(TimeoutException.class);

            writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("added", "@LocalHandler"));

            assertTrue(execution.refresh());
            assertEquals(1, sourceHandlerRouteCount(fixture.getFluxzero().componentRegistry(), tempDir));
            fixture.whenCommand(new ExecutionCommand("after")).expectResult("added:after");
        }
    }

    @Test
    void refreshUnregistersDeletedSourceHandlerWithoutRestart(@TempDir Path tempDir) throws Exception {
        Path source = writeHandlerSource(tempDir, "CommandLogic", commandHandlerSource("deleted", "@LocalHandler"));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).resultTimeout(Duration.ofMillis(100));

            fixture.whenCommand(new ExecutionCommand("before")).expectResult("deleted:before");
            Files.delete(source);

            assertTrue(execution.refresh());
            assertEquals(0, sourceHandlerRouteCount(fixture.getFluxzero().componentRegistry(), tempDir));
            fixture.whenCommand(new ExecutionCommand("after"))
                    .expectExceptionalResult(TimeoutException.class);
        }
    }

    private static long sourceHandlerRouteCount(ComponentRegistry registry, Path sourceRoot) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        return registry.components().stream()
                .filter(component -> component.sourceFile() != null)
                .filter(component -> component.sourceFile().toAbsolutePath().normalize().startsWith(root))
                .flatMap(component -> component.handlerRoutes().stream())
                .count();
    }

    @Test
    void refreshAddsSourcePayloadComponentToTypeResolver(@TempDir Path tempDir) throws Exception {
        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            assertTrue(execution.loadType("io.fluxzero.sdk.execution.generated.LaterPayload").isEmpty());

            writeHandlerSource(tempDir, "LaterPayload", """
                    public record LaterPayload(String value) {
                    }
                    """);

            assertTrue(execution.refresh());
            Class<?> payloadType = execution.loadType("io.fluxzero.sdk.execution.generated.LaterPayload")
                    .orElseThrow();
            assertEquals("io.fluxzero.sdk.execution.generated.LaterPayload", payloadType.getName());
        }
    }

    @Test
    void refreshRemovesDeletedSourcePayloadComponentFromTypeResolver(@TempDir Path tempDir) throws Exception {
        Path source = writeHandlerSource(tempDir, "TemporaryPayload", """
                public record TemporaryPayload(String value) {
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            });

            assertTrue(execution.loadType("io.fluxzero.sdk.execution.generated.TemporaryPayload").isPresent());
            Files.delete(source);

            assertTrue(execution.refresh());
            assertTrue(execution.loadType("io.fluxzero.sdk.execution.generated.TemporaryPayload").isEmpty());
        }
    }

    @Test
    void localSourceCanOptIntoTracking(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(
                tempDir, "CommandLogic",
                commandHandlerSource("external", "@LocalHandler(allowExternalMessages = true)"));

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .registerLocalHandlers(false)
                .build()) {
            TestFixture.createAsync(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenCommand(new ExecutionCommand("one")).expectResult("external:one");
        }
    }

    @Test
    void disabledSourceHandlerIsIndexedButNotRegistered(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "DisabledLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class DisabledLogic {
                    @LocalHandler
                    @HandleCommand(disabled = true)
                    public String handle(ExecutionCommand command) {
                        return command.value();
                    }
                }
                """);

        HandlerRoute route = route(new SourceComponentScanner().scan(tempDir).components().getFirst(), MessageType.COMMAND);
        assertTrue(route.disabled());

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).resultTimeout(Duration.ofMillis(100))
                    .whenCommand(new ExecutionCommand("one")).expectExceptionalResult(TimeoutException.class);
        }
    }

    @Test
    void reportsCompilationFailureAsExceptionalResult(@TempDir Path tempDir) throws Exception {
        writeHandlerSource(tempDir, "BrokenLogic", """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class BrokenLogic {
                    @LocalHandler
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return missingSymbol;
                    }
                }
                """);

        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .startTracking(false)
                .build()) {
            TestFixture.create(fc -> {
                execution.registerWith(fc);
                return List.of();
            }).whenCommand(new ExecutionCommand("one")).expectExceptionalResult(OnDemandCompilationException.class);
        }
    }

    private static String commandHandlerSource(String prefix, String localHandlerAnnotation) {
        return """
                import io.fluxzero.sdk.execution.OnDemandExecutionTest.ExecutionCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                public class CommandLogic {
                    %s
                    @HandleCommand
                    public String handle(ExecutionCommand command) {
                        return "%s:" + command.value();
                    }
                }
                """.formatted(localHandlerAnnotation, prefix);
    }

    private static Path writeHandlerSource(Path sourceRoot, String className, String body) throws Exception {
        return writeHandlerSource(sourceRoot, "io.fluxzero.sdk.execution.generated", className, body);
    }

    private static Path writeHandlerSource(Path sourceRoot, String packageName, String className, String body) throws Exception {
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve(className + ".java");
        Files.writeString(source, source(className, packageName, body));
        return source;
    }

    private static Path writePackagedSource(Path sourceRoot, String packageName, String className, String body)
            throws Exception {
        Path packageRoot = sourceRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageRoot);
        Path source = packageRoot.resolve(className + ".java");
        Files.writeString(source, source(className, packageName, body));
        return source;
    }

    private static Path writePackageInfo(Path sourceRoot, String annotation) throws Exception {
        return writePackageInfo(sourceRoot, "io.fluxzero.sdk.execution.generated", annotation);
    }

    private static Path writePackageInfo(Path sourceRoot, String packageName, String annotation) throws Exception {
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve("package-info.java");
        Files.writeString(source, """
                @%s
                package %s;
                """.formatted(annotation, packageName));
        return source;
    }

    private static String source(String className, String body) {
        return source(className, "io.fluxzero.sdk.execution.generated", body);
    }

    private static String source(String className, String packageName, String body) {
        return """
                package %s;

                %s
                """.formatted(packageName, body.replace("public class CommandLogic", "public class " + className));
    }

    private static HandlerRoute route(ComponentDescriptor unit, MessageType messageType) {
        return unit.handlerRoutes().stream()
                .filter(route -> route.messageType() == messageType)
                .findFirst().orElseThrow();
    }

    public record ExecutionCommand(String value) {
    }

    public record ExecutionEvent(String value) {
    }

    public record ExecutionQuery(String value) {
    }

    public record ExecutionRequest(String value) implements Request<String> {
    }

    public static class LoadCounter {
        private static final AtomicInteger loaded = new AtomicInteger();

        public static void loaded() {
            loaded.incrementAndGet();
        }

        static int loadedCount() {
            return loaded.get();
        }

        static void reset() {
            loaded.set(0);
        }
    }
}
