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
import java.util.Set;

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
                import io.fluxzero.sdk.common.serialization.casting.Upcast;
                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.Path;
                import io.fluxzero.sdk.web.PathParam;

                @Consumer(name = "type-consumer", threads = 3)
                @io.fluxzero.common.serialization.RegisterType(rootClass = RichLogic.class,
                                                               contains = "RichLogic")
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

                    @Upcast(type = "legacy.RichLogic", revision = 0)
                    public String upcast(String value) {
                        return value;
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
        assertEquals("io.fluxzero.sdk.registry.generated.RichLogic",
                     component.registeredTypes().getFirst().root());
        assertEquals(List.of("io.fluxzero.sdk.registry.generated.RichLogic"),
                     component.registeredTypes().getFirst().candidateTypeNames());
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
        ParameterDescriptor webParameter = web.executableMetadata().orElseThrow().parameters().getFirst();
        AnnotationDescriptor pathParam = webParameter.annotations().getFirst();
        assertEquals("id", webParameter.name());
        assertEquals("PathParam", pathParam.name());
        assertTrue(pathParam.isOrHas("WebParam", "io.fluxzero.sdk.web.WebParam"));
        assertEquals(List.of("PATH"), pathParam.find("WebParam", "io.fluxzero.sdk.web.WebParam")
                .orElseThrow().values("type"));

        AnnotationDescriptor upcast = component.executables().stream()
                .filter(executable -> executable.name().equals("upcast"))
                .findFirst().orElseThrow()
                .annotations().getFirst();
        assertTrue(upcast.isOrHas("Cast", "io.fluxzero.sdk.common.serialization.casting.Cast"));
        assertEquals(List.of("1"), upcast.find("Cast", "io.fluxzero.sdk.common.serialization.casting.Cast")
                .orElseThrow().values("revisionDelta"));
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
    void webRoutesPreservePathHierarchyAndAbsoluteResets(@TempDir Path tempDir) throws Exception {
        Path parentPackage = tempDir.resolve("io/fluxzero/sdk/registry/webpaths");
        Path childPackage = parentPackage.resolve("child");
        Files.createDirectories(childPackage);
        Files.writeString(parentPackage.resolve("package-info.java"), """
                @io.fluxzero.sdk.web.Path("web-root")
                package io.fluxzero.sdk.registry.webpaths;
                """);
        Files.writeString(childPackage.resolve("package-info.java"), """
                @io.fluxzero.sdk.web.Path("")
                package io.fluxzero.sdk.registry.webpaths.child;
                """);
        Files.writeString(childPackage.resolve("SourceWebPathHandler.java"), """
                package io.fluxzero.sdk.registry.webpaths.child;

                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.Path;

                @Path("type")
                public class SourceWebPathHandler {
                    @Path("method")
                    @HandleGet("items")
                    public String stacked() {
                        return "stacked";
                    }

                    @Path("/reset")
                    @HandleGet("items")
                    public String reset() {
                        return "reset";
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.webpaths.child.SourceWebPathHandler").orElseThrow();

        assertEquals(List.of("/web-root/child/type/method/items"), webRoute(component, "stacked").paths());
        assertEquals(List.of("/reset/items"), webRoute(component, "reset").paths());
    }

    @Test
    void webRoutesResolveUniqueFinalStringPathConstants(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceWebPathHandler", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.Path;

                public class SourceWebPathHandler {
                    static final String endpointUrl = "/endpoint";

                    @Path(endpointUrl)
                    static class Endpoint {
                        @HandleGet
                        public String get() {
                            return "ok";
                        }
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceWebPathHandler$Endpoint").orElseThrow();

        assertEquals(List.of("/endpoint"), webRoute(component, "get").paths());
    }

    @Test
    void webRoutesUsePackageSegmentForBlankTypePathDefault(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceWebPathHandler", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.Path;

                @Path
                public class SourceWebPathHandler {
                    @HandleGet("items")
                    public String get() {
                        return "ok";
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceWebPathHandler").orElseThrow();

        assertEquals(List.of("/generated/items"), webRoute(component, "get").paths());
    }

    @Test
    void webRoutesInheritEnclosingTypePathForNestedHandlers(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceSocketEndpoint", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.web.HandleSocketMessage;
                import io.fluxzero.sdk.web.HandleSocketOpen;
                import io.fluxzero.sdk.web.Path;
                import io.fluxzero.sdk.web.SocketEndpoint;
                import io.fluxzero.sdk.web.SocketSession;

                public class SourceSocketEndpoint {
                    @Path("/endpoint")
                    static class EndpointContainer {
                        @SocketEndpoint
                        static class Endpoint {
                            @HandleSocketOpen
                            Object onOpen(SocketSession session) {
                                return "open";
                            }

                            @HandleSocketMessage
                            Object onMessage(SocketSession session) {
                                return "message";
                            }
                        }
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceSocketEndpoint$EndpointContainer$Endpoint")
                .orElseThrow();

        assertEquals(List.of("/endpoint"), webRoute(component, "onOpen").paths());
        assertEquals(List.of("/endpoint"), webRoute(component, "onMessage").paths());
    }

    @Test
    void indexesNestedAnnotationAttributesFromSource(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceSocketEndpoint", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.web.HandleSocketOpen;
                import io.fluxzero.sdk.web.SocketEndpoint;
                import io.fluxzero.sdk.web.SocketEndpoint.AliveCheck;
                import static java.util.concurrent.TimeUnit.MILLISECONDS;

                @SocketEndpoint(aliveCheck = @AliveCheck(value = false, timeUnit = MILLISECONDS,
                                                         pingDelay = 7, pingTimeout = 3))
                public class SourceSocketEndpoint {
                    @HandleSocketOpen
                    public void open() {
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceSocketEndpoint").orElseThrow();
        AnnotationDescriptor socketEndpoint = component.annotations().stream()
                .filter(annotation -> annotation.qualifiedName().equals("io.fluxzero.sdk.web.SocketEndpoint"))
                .findFirst().orElseThrow();
        AnnotationDescriptor aliveCheck = socketEndpoint.nestedAnnotations("aliveCheck").getFirst();

        assertEquals("io.fluxzero.sdk.web.SocketEndpoint.AliveCheck", aliveCheck.qualifiedName());
        assertEquals(List.of("false"), aliveCheck.values("value"));
        assertEquals(List.of("MILLISECONDS"), aliveCheck.values("timeUnit"));
        assertEquals(List.of("7"), aliveCheck.values("pingDelay"));
        assertEquals(List.of("3"), aliveCheck.values("pingTimeout"));
    }

    @Test
    void resolvesPrimitiveSourceConstantsInNestedAnnotationAttributes(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceSocketEndpoint", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.web.HandleSocketOpen;
                import io.fluxzero.sdk.web.SocketEndpoint;
                import io.fluxzero.sdk.web.SocketEndpoint.AliveCheck;

                @SocketEndpoint(aliveCheck = @AliveCheck(pingDelay = pingDelay, pingTimeout = pingTimeout))
                public class SourceSocketEndpoint {
                    static final int pingDelay = 30, pingTimeout = 10;

                    @HandleSocketOpen
                    public void open() {
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceSocketEndpoint").orElseThrow();
        AnnotationDescriptor socketEndpoint = component.annotations().stream()
                .filter(annotation -> annotation.qualifiedName().equals("io.fluxzero.sdk.web.SocketEndpoint"))
                .findFirst().orElseThrow();
        AnnotationDescriptor aliveCheck = socketEndpoint.nestedAnnotations("aliveCheck").getFirst();

        assertEquals(List.of("30"), aliveCheck.values("pingDelay"));
        assertEquals(List.of("10"), aliveCheck.values("pingTimeout"));
    }

    @Test
    void indexesNestedAnnotationTextBlockValuesFromSource(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceApiDocHandler", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.web.ApiDocComponent;
                import io.fluxzero.sdk.web.ApiDocInfo;
                import io.fluxzero.sdk.web.HandleGet;

                @ApiDocInfo(components = @ApiDocComponent(path = "responses.error", json = \"\"\"
                        {"description":"Invalid request"}
                        \"\"\"))
                public class SourceApiDocHandler {
                    @HandleGet("/info")
                    public String info() {
                        return "ok";
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceApiDocHandler").orElseThrow();
        AnnotationDescriptor apiDocInfo = component.annotations().stream()
                .filter(annotation -> annotation.qualifiedName().equals("io.fluxzero.sdk.web.ApiDocInfo"))
                .findFirst().orElseThrow();
        AnnotationDescriptor componentMetadata = apiDocInfo.nestedAnnotations("components").getFirst();

        assertEquals(List.of("responses.error"), componentMetadata.values("path"));
        assertEquals(List.of("{\"description\":\"Invalid request\"}\n"), componentMetadata.values("json"));
    }

    @Test
    void ignoresFullyQualifiedAnnotationsOnNestedTypesWhenParsingExecutables(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "NestedAnnotatedType", """
                package io.fluxzero.sdk.registry.generated;

                public class NestedAnnotatedType {
                    @org.springframework.core.annotation.Order(-20)
                    static class Nested {
                        String value() {
                            return "nested";
                        }
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        ComponentDescriptor component = registry
                .findComponent("io.fluxzero.sdk.registry.generated.NestedAnnotatedType").orElseThrow();

        assertTrue(component.executables().stream().noneMatch(executable -> executable.name().equals("Order")));
        ComponentRegistryJson.fromJson(ComponentRegistryJson.toJson(registry));
    }

    @Test
    void indexesNestedTypesWithJvmComponentNamesAndCanonicalPayloadNames(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "NestedHandlerFixture", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                public class NestedHandlerFixture {
                    static class Command {
                    }

                    static class Handler {
                        @HandleCommand
                        String handle(Command command) {
                            return "ok";
                        }
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        ComponentDescriptor handler = registry
                .findComponent("io.fluxzero.sdk.registry.generated.NestedHandlerFixture$Handler").orElseThrow();
        HandlerRoute route = route(handler, MessageType.COMMAND);

        assertEquals("io.fluxzero.sdk.registry.generated.NestedHandlerFixture$Handler", handler.fullClassName());
        assertEquals(Set.of("io.fluxzero.sdk.registry.generated.NestedHandlerFixture.Command"),
                     route.payloadTypeNames());
        assertEquals("io.fluxzero.sdk.registry.generated.NestedHandlerFixture.Command",
                     route.executableMetadata().orElseThrow().parameters().getFirst().typeName());
        ComponentRegistryJson.fromJson(ComponentRegistryJson.toJson(registry));
    }

    @Test
    void preservesGenericSuperTypeNames(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "SourceRequest", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.handling.Request;

                public class SourceRequest implements Request<SourceResponse> {
                }

                class SourceResponse {
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.SourceRequest").orElseThrow();

        assertEquals(List.of(
                "io.fluxzero.sdk.tracking.handling.Request<io.fluxzero.sdk.registry.generated.SourceResponse>"),
                     component.superTypeNames());
    }

    @Test
    void indexesImplicitDefaultConstructorsForClasses(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "ImplicitConstructorHandler", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                public class ImplicitConstructorHandler {
                    @HandleCommand
                    String handle(String command) {
                        return command;
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.ImplicitConstructorHandler").orElseThrow();

        ExecutableDescriptor constructor = component.executables().stream()
                .filter(executable -> executable.kind() == ExecutableKind.CONSTRUCTOR)
                .findFirst().orElseThrow();
        assertEquals("<init>", constructor.name());
        assertTrue(constructor.parameters().isEmpty());
    }

    @Test
    void indexesAllConcreteHandlerRouteTypes(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "AllRoutesLogic", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.tracking.handling.*;
                import io.fluxzero.sdk.web.HandleWeb;
                import io.fluxzero.sdk.web.HandleWebResponse;

                public class AllRoutesLogic {
                    @HandleCommand
                    public void command(String payload) {
                    }

                    @HandleEvent
                    public void event(String payload) {
                    }

                    @HandleNotification
                    public void notification(String payload) {
                    }

                    @HandleQuery
                    public String query(String payload) {
                        return payload;
                    }

                    @HandleResult
                    public void result(String payload) {
                    }

                    @HandleSchedule
                    public void schedule(String payload) {
                    }

                    @HandleError
                    public void error(Throwable payload) {
                    }

                    @HandleMetrics
                    public void metrics(String payload) {
                    }

                    @HandleWeb
                    public String web(String payload) {
                        return payload;
                    }

                    @HandleWebResponse
                    public void webResponse(String payload) {
                    }

                    @HandleDocument
                    public void document(String payload) {
                    }

                    @HandleCustom("custom-topic")
                    public void custom(String payload) {
                    }
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);

        assertEquals(java.util.Set.of(MessageType.values()), registry.messageTypes());
    }

    @Test
    void resolvesKnownFluxzeroAnnotationsFromSimpleNames(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "AnnotatedModelLogic", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.common.search.*;
                import io.fluxzero.common.serialization.*;
                import io.fluxzero.sdk.common.serialization.*;
                import io.fluxzero.sdk.modeling.*;
                import io.fluxzero.sdk.persisting.eventsourcing.*;
                import io.fluxzero.sdk.persisting.search.*;
                import io.fluxzero.sdk.publishing.dataprotection.*;
                import io.fluxzero.sdk.publishing.routing.*;
                import io.fluxzero.sdk.tracking.handling.*;

                @Aggregate
                @Stateful
                @Searchable
                @SearchInclude
                @Revision(2)
                @FilterContent
                @ProtectData
                public class AnnotatedModelLogic {
                    @HandleCommand
                    @Apply
                    @RoutingKey("model-route")
                    @DropProtectedData
                    public void handle(@Association String command) {
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.AnnotatedModelLogic").orElseThrow();
        ExecutableDescriptor executable = component.executables().stream()
                .filter(e -> e.name().equals("handle"))
                .findFirst().orElseThrow();

        assertTrue(hasAnnotation(component.annotations(), "io.fluxzero.sdk.modeling.Aggregate"));
        assertTrue(hasAnnotation(component.annotations(), "io.fluxzero.sdk.tracking.handling.Stateful"));
        assertTrue(hasAnnotation(component.annotations(), "io.fluxzero.sdk.persisting.search.Searchable"));
        assertTrue(hasAnnotation(component.annotations(), "io.fluxzero.common.search.SearchInclude"));
        assertTrue(hasAnnotation(component.annotations(), "io.fluxzero.common.serialization.Revision"));
        assertTrue(hasAnnotation(component.annotations(), "io.fluxzero.sdk.common.serialization.FilterContent"));
        assertTrue(hasAnnotation(component.annotations(), "io.fluxzero.sdk.publishing.dataprotection.ProtectData"));
        assertTrue(hasAnnotation(executable.annotations(), "io.fluxzero.sdk.persisting.eventsourcing.Apply"));
        assertTrue(hasAnnotation(executable.annotations(), "io.fluxzero.sdk.publishing.routing.RoutingKey"));
        assertTrue(hasAnnotation(executable.annotations(), "io.fluxzero.sdk.publishing.dataprotection.DropProtectedData"));
        assertTrue(hasAnnotation(executable.parameters().getFirst().annotations(),
                                 "io.fluxzero.sdk.tracking.handling.Association"));
    }

    @Test
    void resolvesSourceDeclaredMetaAnnotations(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "MetaAnnotatedLogic", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.common.serialization.RegisterType;
                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole;
                import io.fluxzero.sdk.web.HandleWeb;
                import io.fluxzero.sdk.web.Path;
                import static io.fluxzero.sdk.web.HttpRequestMethod.GET;

                @RequiresAnyRole("admin")
                @interface AdminOnly {
                }

                @HandleWeb(value = "items", method = GET, autoHead = false, autoOptions = false)
                @interface MetaWeb {
                }

                @Consumer(name = "meta-consumer")
                @RegisterType(contains = "Meta")
                @Path("meta-type")
                public class MetaAnnotatedLogic {
                    @AdminOnly
                    @HandleCommand(passive = true, allowedClasses = MetaCommand.class)
                    public void handle(MetaCommand command) {
                    }

                    @MetaWeb
                    public String get() {
                        return "ok";
                    }
                }

                record MetaCommand(String value) {
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        ComponentDescriptor component = registry.findComponent(
                "io.fluxzero.sdk.registry.generated.MetaAnnotatedLogic").orElseThrow();
        HandlerRoute command = route(component, MessageType.COMMAND);
        WebRouteDescriptor web = webRoute(component, "get");

        assertTrue(registry.findComponent("io.fluxzero.sdk.registry.generated.MetaWeb").isEmpty());
        assertEquals("meta-consumer", component.consumerMetadata().orElseThrow().name());
        assertFalse(component.registeredTypes().isEmpty());
        assertEquals("HandleCommand", command.annotation().name());
        assertTrue(command.passive());
        assertEquals(java.util.Set.of("io.fluxzero.sdk.registry.generated.MetaCommand"), command.allowedClassNames());
        assertTrue(command.executableMetadata().orElseThrow().annotations().stream()
                           .anyMatch(annotation -> annotation.isOrHas(
                                   "RequiresAnyRole",
                                   "io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole")));
        assertEquals("HandleWeb", route(component, MessageType.WEBREQUEST).annotation().name());
        assertEquals(List.of("/meta-type/items"), web.paths());
        assertEquals(List.of("GET"), web.methods());
        assertFalse(web.autoHead());
        assertFalse(web.autoOptions());
    }

    @Test
    void indexesSourcePropertyAndRecordComponentAnnotations(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "PropertyPayload", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.modeling.EntityId;
                import io.fluxzero.sdk.publishing.dataprotection.ProtectData;

                public record PropertyPayload(@EntityId String id, @ProtectData String secret) {
                }
                """);
        writeSource(tempDir, "PropertyModel", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.modeling.EntityId;
                import io.fluxzero.sdk.publishing.dataprotection.ProtectData;
                import io.fluxzero.sdk.tracking.handling.Association;
                import io.fluxzero.sdk.modeling.Member;

                import java.util.List;

                public class PropertyModel {
                    @EntityId
                    private String id;

                    @Member
                    private List<PropertyPayload> children;

                    @Association
                    @ProtectData
                    private String accountId;
                }
                """);

        ComponentRegistry registry = new SourceComponentScanner().scan(tempDir);
        ComponentDescriptor payload = registry.findComponent(
                "io.fluxzero.sdk.registry.generated.PropertyPayload").orElseThrow();
        ComponentDescriptor model = registry.findComponent(
                "io.fluxzero.sdk.registry.generated.PropertyModel").orElseThrow();

        assertTrue(hasAnnotation(property(payload, "id").annotations(), "io.fluxzero.sdk.modeling.EntityId"));
        assertTrue(hasAnnotation(property(payload, "secret").annotations(),
                                 "io.fluxzero.sdk.publishing.dataprotection.ProtectData"));
        assertEquals("java.util.List<io.fluxzero.sdk.registry.generated.PropertyPayload>",
                     property(model, "children").genericTypeName());
        assertTrue(hasAnnotation(property(model, "id").annotations(), "io.fluxzero.sdk.modeling.EntityId"));
        assertTrue(hasAnnotation(property(model, "children").annotations(),
                                 "io.fluxzero.sdk.modeling.Member"));
        assertTrue(hasAnnotation(property(model, "accountId").annotations(),
                                 "io.fluxzero.sdk.tracking.handling.Association"));
        assertTrue(hasAnnotation(property(model, "accountId").annotations(),
                                 "io.fluxzero.sdk.publishing.dataprotection.ProtectData"));
    }

    @Test
    void indexesRecordComponentsWithPatternBracesAndTypeUseAnnotations(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "ValidatedRecord", """
                package io.fluxzero.sdk.registry.generated;

                import jakarta.validation.constraints.NotBlank;
                import jakarta.validation.constraints.NotEmpty;
                import jakarta.validation.constraints.Pattern;
                import jakarta.validation.constraints.Size;
                import java.util.List;

                public record ValidatedRecord(@NotBlank String name,
                                              @NotEmpty List<@Size(min = 2, max = 2) String> values,
                                              @Pattern(regexp = "[A-Z]{2}") String code) {
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.ValidatedRecord").orElseThrow();

        assertTrue(hasAnnotation(property(component, "name").annotations(),
                                 "jakarta.validation.constraints.NotBlank"));
        assertTrue(hasAnnotation(property(component, "values").annotations(),
                                 "jakarta.validation.constraints.NotEmpty"));
        TypeUseDescriptor valuesTypeUse = property(component, "values").typeUse();
        assertEquals("java.util.List", valuesTypeUse.typeName());
        assertEquals("java.lang.String", valuesTypeUse.typeArguments().getFirst().typeName());
        assertTrue(hasAnnotation(valuesTypeUse.typeArguments().getFirst().annotations(),
                                 "jakarta.validation.constraints.Size"));
        assertEquals(List.of("2"), valuesTypeUse.typeArguments().getFirst()
                .annotations().getFirst().values("min"));
        assertTrue(hasAnnotation(property(component, "code").annotations(),
                                 "jakarta.validation.constraints.Pattern"));
    }

    @Test
    void resolvesNestedSourceDeclaredConstraintAnnotations(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "NestedConstraintHost", """
                package io.fluxzero.sdk.registry.generated;

                import jakarta.validation.Constraint;

                public class NestedConstraintHost {
                    @Constraint(validatedBy = NestedConstraintHost.NestedValidator.class)
                    @interface NestedConstraint {
                        String message() default "invalid";
                    }

                    record Payload(@NestedConstraint String value) {
                    }

                    static class NestedValidator {
                    }
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.NestedConstraintHost$Payload").orElseThrow();
        AnnotationDescriptor annotation = property(component, "value").annotations().getFirst();

        assertEquals("io.fluxzero.sdk.registry.generated.NestedConstraintHost.NestedConstraint",
                     annotation.qualifiedName());
        AnnotationDescriptor constraint = annotation.metaAnnotations().stream()
                .filter(meta -> meta.qualifiedName().equals("jakarta.validation.Constraint"))
                .findFirst().orElseThrow();
        assertEquals(List.of("io.fluxzero.sdk.registry.generated.NestedConstraintHost.NestedValidator"),
                     constraint.values("validatedBy"));
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
    void normalizesAnnotationEnumAndKnownConstantValues(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "ConstantAnnotatedComponent", """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.common.Order;
                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.ConsumerConfiguration;
                import io.fluxzero.sdk.tracking.ConsumerHandlingMode;

                @Order(Order.LOWEST_PRECEDENCE)
                @Consumer(name = "constant-consumer",
                        handlingMode = ConsumerHandlingMode.ASYNC,
                        maxFetchBytes = ConsumerConfiguration.DEFAULT_MAX_FETCH_BYTES,
                        durationUnit = java.time.temporal.ChronoUnit.MILLIS)
                public class ConstantAnnotatedComponent {
                }
                """);

        ComponentDescriptor component = new SourceComponentScanner().scan(tempDir)
                .findComponent("io.fluxzero.sdk.registry.generated.ConstantAnnotatedComponent").orElseThrow();

        AnnotationDescriptor order = component.annotations().stream()
                .filter(annotation -> annotation.name().equals("Order"))
                .findFirst().orElseThrow();
        AnnotationDescriptor consumer = component.consumerMetadata().orElseThrow().annotation();

        assertEquals(String.valueOf(Integer.MAX_VALUE), order.firstValue("value").orElseThrow());
        assertEquals("ASYNC", consumer.firstValue("handlingMode").orElseThrow());
        assertEquals(String.valueOf(100L * 1024L * 1024L), consumer.firstValue("maxFetchBytes").orElseThrow());
        assertEquals("MILLIS", consumer.firstValue("durationUnit").orElseThrow());
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

    private static WebRouteDescriptor webRoute(ComponentDescriptor component, String executableName) {
        return component.handlerRoutes().stream()
                .filter(route -> route.messageType() == MessageType.WEBREQUEST)
                .filter(route -> route.executableMetadata().orElseThrow().name().equals(executableName))
                .findFirst().orElseThrow()
                .webRoutes().getFirst();
    }

    private static boolean hasAnnotation(List<AnnotationDescriptor> annotations, String qualifiedName) {
        return annotations.stream().anyMatch(annotation -> annotation.qualifiedName().equals(qualifiedName));
    }

    private static PropertyDescriptor property(ComponentDescriptor component, String name) {
        return component.properties().stream()
                .filter(property -> property.name().equals(name))
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
