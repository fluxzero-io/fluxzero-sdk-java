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

import io.fluxzero.common.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceComponentScannerTest {

    @Test
    void indexesRichSourceMetadata(@TempDir Path tempDir) throws Exception {
        writePackageInfo(tempDir, """
                @io.fluxzero.sdk.tracking.handling.LocalHandler(allowExternalMessages = true)
                @io.fluxzero.sdk.tracking.Consumer(name = "package-consumer", threads = 2)
                @io.fluxzero.common.serialization.RegisterType(contains = {"ExecutionCommand", "ExecutionResult"})
                @io.fluxzero.sdk.web.Path("/api")
                package io.fluxzero.sdk.registry.generated;
                """);
        writeSource(tempDir, "ExecutionCommand", """
                package io.fluxzero.sdk.registry.generated;

                public record ExecutionCommand(String value) {
                }
                """);
        writeSource(tempDir, "ExecutionResult", """
                package io.fluxzero.sdk.registry.generated;

                public record ExecutionResult(String value) {
                }
                """);
        writeSource(tempDir, "RichLogic", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.registry.SourceComponentScannerTest.ExternalCommand;
                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.Path;
                import io.fluxzero.sdk.web.PathParam;

                @Consumer(name = "type-consumer", threads = 3)
                @io.fluxzero.common.serialization.RegisterType(root = "io.fluxzero.sdk.registry.generated",
                                                               contains = "Execution")
                @Path("logic")
                public class RichLogic {
                    @LocalHandler(false)
                    @HandleCommand(allowedClasses = ExternalCommand.class, passive = true, skipExpiredRequests = true)
                    public ExecutionResult handle(@jakarta.validation.constraints.NotBlank ExternalCommand command,
                                                  String ignored) {
                        return new ExecutionResult(command.value());
                    }

                    @HandleQuery(disabled = true)
                    public String disabled(ExternalCommand query) {
                        return query.value();
                    }

                    @HandleGet(value = {"items/{id}", "items"}, autoHead = false, autoOptions = false)
                    public String get(@PathParam String id) {
                        return id;
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        ComponentDescriptor component = registry.components().stream()
                .filter(c -> c.className().equals("RichLogic"))
                .findFirst().orElseThrow();
        PackageDescriptor packageDescriptor = registry.packages().getFirst();

        assertEquals(ComponentKind.CLASS, component.componentKind());
        assertEquals("io.fluxzero.sdk.registry.generated.RichLogic", component.fullClassName());
        assertEquals("package-consumer", packageDescriptor.consumerMetadata().orElseThrow().name());
        assertEquals("type-consumer", component.consumerMetadata().orElseThrow().name());
        assertTrue(component.capabilities().contains(ComponentCapability.CONSUMER));
        assertTrue(component.capabilities().contains(ComponentCapability.REGISTERED_TYPE));
        assertTrue(packageDescriptor.capabilities().contains(ComponentCapability.PACKAGE_LOCAL_HANDLER));
        assertTrue(registry.registeredTypes()
                .flatMap(registeredType -> registeredType.candidateTypeNames().stream())
                .anyMatch(typeName -> typeName.endsWith(".ExecutionCommand")));

        HandlerRoute command = route(component, MessageType.COMMAND);
        assertFalse(command.local());
        assertTrue(command.tracked());
        assertTrue(command.passive());
        assertTrue(command.skipExpiredRequests());
        assertEquals("HandleCommand", command.annotationMetadata().orElseThrow().name());
        assertEquals("handle", command.executableMetadata().orElseThrow().name());
        assertEquals("io.fluxzero.sdk.registry.generated.ExecutionResult",
                     command.executableMetadata().orElseThrow().returnTypeName());
        assertEquals("io.fluxzero.sdk.registry.SourceComponentScannerTest.ExternalCommand",
                     command.allowedClassNames().iterator().next());
        assertEquals(command.allowedClassNames(), command.payloadTypeNames());
        assertEquals("command", command.executableMetadata().orElseThrow().parameters().getFirst().name());
        assertEquals("NotBlank", command.executableMetadata().orElseThrow()
                .parameters().getFirst().annotations().getFirst().name());

        HandlerRoute disabled = route(component, MessageType.QUERY);
        assertTrue(disabled.disabled());

        HandlerRoute web = route(component, MessageType.WEBREQUEST);
        WebRouteDescriptor webRoute = web.webRoutes().getFirst();
        assertEquals(List.of("/api/logic/items/{id}", "/api/logic/items"), webRoute.paths());
        assertEquals(List.of("GET"), webRoute.methods());
        assertFalse(webRoute.autoHead());
        assertFalse(webRoute.autoOptions());
        assertEquals("id", web.executableMetadata().orElseThrow().parameters().getFirst().name());
        assertEquals("PathParam", web.executableMetadata().orElseThrow()
                .parameters().getFirst().annotations().getFirst().name());
    }

    @Test
    void scanningSourceDoesNotCompileOrCreateCacheOutput(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(sourceRoot);
        writeSource(sourceRoot, "ScanOnlyLogic", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                public class ScanOnlyLogic {
                    @HandleCommand
                    public String handle(String command) {
                        return command;
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot);

        assertEquals(1, registry.components().size());
        assertFalse(Files.exists(cacheRoot));
        try (var files = Files.walk(tempDir)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".class")));
        }
    }

    @Test
    void indexesSourcePayloadSelfHandlersAsLocalComponentRoutes(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SelfQuery", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.handling.HandleQuery;

                public record SelfQuery(String value) {
                    @HandleQuery
                    public String handle() {
                        return value;
                    }
                }
                """);
        writeSource(tempDir, "TrackedSelfCommand", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.TrackSelf;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                @TrackSelf
                public record TrackedSelfCommand(String value) {
                    @HandleCommand
                    public String handle() {
                        return value;
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        HandlerRoute query = route(
                registry.findComponent("io.fluxzero.sdk.registry.generated.SelfQuery").orElseThrow(),
                MessageType.QUERY);
        HandlerRoute command = route(
                registry.findComponent("io.fluxzero.sdk.registry.generated.TrackedSelfCommand").orElseThrow(),
                MessageType.COMMAND);

        assertTrue(query.local());
        assertFalse(query.tracked());
        assertEquals(java.util.Set.of("io.fluxzero.sdk.registry.generated.SelfQuery"), query.payloadTypeNames());
        assertFalse(command.local());
        assertTrue(command.tracked());
        assertEquals(java.util.Set.of("io.fluxzero.sdk.registry.generated.TrackedSelfCommand"),
                     command.payloadTypeNames());
    }

    @Test
    void indexesSourceInfrastructureCapabilities(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceInfrastructure", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.common.TaskScheduler;
                import io.fluxzero.common.application.PropertySource;
                import io.fluxzero.common.caching.Cache;
                import io.fluxzero.sdk.common.IdentityProvider;
                import io.fluxzero.sdk.common.serialization.Serializer;
                import io.fluxzero.sdk.persisting.search.DocumentSerializer;
                import io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider;
                import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
                import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
                import io.fluxzero.sdk.web.WebResponseMapper;

                public class SourceInfrastructure implements HandlerInterceptor, WebResponseMapper, Serializer,
                        DocumentSerializer, CorrelationDataProvider, IdentityProvider, UserProvider, Cache,
                        TaskScheduler, PropertySource {
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceInfrastructure").orElseThrow();

        assertTrue(component.superTypeNames().containsAll(List.of(
                "io.fluxzero.sdk.tracking.handling.HandlerInterceptor",
                "io.fluxzero.sdk.web.WebResponseMapper",
                "io.fluxzero.sdk.common.serialization.Serializer",
                "io.fluxzero.sdk.persisting.search.DocumentSerializer",
                "io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider",
                "io.fluxzero.sdk.common.IdentityProvider",
                "io.fluxzero.sdk.tracking.handling.authentication.UserProvider",
                "io.fluxzero.common.caching.Cache",
                "io.fluxzero.common.TaskScheduler",
                "io.fluxzero.common.application.PropertySource")));
        assertTrue(component.capabilities().contains(ComponentCapability.HANDLER_INTERCEPTOR));
        assertTrue(component.capabilities().contains(ComponentCapability.HANDLER_DECORATOR));
        assertTrue(component.capabilities().contains(ComponentCapability.WEB_RESPONSE_MAPPER));
        assertTrue(component.capabilities().contains(ComponentCapability.RESPONSE_MAPPER));
        assertTrue(component.capabilities().contains(ComponentCapability.SERIALIZER));
        assertTrue(component.capabilities().contains(ComponentCapability.DOCUMENT_SERIALIZER));
        assertTrue(component.capabilities().contains(ComponentCapability.CORRELATION_DATA_PROVIDER));
        assertTrue(component.capabilities().contains(ComponentCapability.IDENTITY_PROVIDER));
        assertTrue(component.capabilities().contains(ComponentCapability.USER_PROVIDER));
        assertTrue(component.capabilities().contains(ComponentCapability.CACHE));
        assertTrue(component.capabilities().contains(ComponentCapability.TASK_SCHEDULER));
        assertTrue(component.capabilities().contains(ComponentCapability.PROPERTY_SOURCE));
    }

    @Test
    void canWriteJsonArtifactAsSourceOnlyProducer(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path output = tempDir.resolve("target/META-INF/fluxzero/component-registry.json");
        writeSource(sourceRoot, "SourceProducerLogic", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                public class SourceProducerLogic {
                    @HandleCommand
                    public String handle(String command) {
                        return command;
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().writeJson(sourceRoot, output);

        assertTrue(Files.isRegularFile(output));
        assertEquals(registry.normalized(), ComponentRegistryJson.read(output).normalized());
    }

    private static HandlerRoute route(ComponentDescriptor component, MessageType messageType) {
        return component.handlerRoutes().stream()
                .filter(route -> route.messageType() == messageType)
                .findFirst().orElseThrow();
    }

    private static void writePackageInfo(Path sourceRoot, String source) throws Exception {
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("package-info.java"), source);
    }

    private static void writeSource(Path sourceRoot, String className, String source) throws Exception {
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve(className + ".java"), source);
    }

    public record ExternalCommand(String value) {
    }
}
