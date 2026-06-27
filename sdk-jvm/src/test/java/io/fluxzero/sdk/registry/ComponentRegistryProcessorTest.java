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

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentRegistryProcessorTest {

    @Test
    void generatesComponentRegistryJsonDuringJavac(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path output = tempDir.resolve("classes");
        writeProcessorFixture(sourceRoot);

        compile(sourceRoot, output);

        ComponentRegistry registry = ComponentRegistryJson.read(output.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));
        assertFalse(registry.components().isEmpty());
        assertEquals("processor-package", registry.packages().getFirst().consumerMetadata().orElseThrow().name());
        assertTrue(registry.packages().getFirst().registeredTypes().getFirst()
                           .candidateTypeNames().contains("io.fluxzero.sdk.registry.processorfixture.ProcessorCommand"));

        ComponentDescriptor component = registry.findComponent(
                "io.fluxzero.sdk.registry.processorfixture.ProcessorHandler").orElseThrow();
        assertTrue(component.capabilities().contains(ComponentCapability.SOURCE_COMPONENT));
        assertTrue(component.capabilities().contains(ComponentCapability.HANDLER));
        assertEquals("processor-handler", component.consumerMetadata().orElseThrow().name());
        assertEquals(Set.of(MessageType.values()), component.messageTypes());
        assertTrue(hasAnnotation(property(component, "id").annotations(), "io.fluxzero.sdk.modeling.EntityId"));
        assertTrue(hasAnnotation(property(component, "command").annotations(),
                                 "io.fluxzero.sdk.tracking.handling.Association"));

        HandlerRoute command = route(component, MessageType.COMMAND);
        assertFalse(command.local());
        assertTrue(command.tracked());
        assertTrue(command.passive());
        assertTrue(command.skipExpiredRequests());
        assertEquals("io.fluxzero.sdk.registry.processorfixture.ProcessorCommand",
                     command.allowedClassNames().iterator().next());
        assertEquals("command", command.executableMetadata().orElseThrow().parameters().getFirst().name());

        HandlerRoute web = route(component, MessageType.WEBREQUEST);
        WebRouteDescriptor webRoute = web.webRoutes().getFirst();
        assertEquals(List.of("/processor/logic/items/{id}", "/processor/logic/items"), webRoute.paths());
        assertEquals(List.of("GET"), webRoute.methods());
        assertFalse(webRoute.autoHead());
        assertFalse(webRoute.autoOptions());

        ComponentDescriptor webPathComponent = registry.findComponent(
                "io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorWebPathHandler").orElseThrow();
        assertEquals(List.of("/processor/web-root/child/type/method/items"),
                     webRoute(webPathComponent, "stacked").paths());
        assertEquals(List.of("/reset/items"), webRoute(webPathComponent, "reset").paths());

        ComponentDescriptor nestedInherited = registry.components().stream()
                .filter(descriptor -> descriptor.fullClassName()
                        .equals("io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorNestedWebPathHandler$Inherited"))
                .findFirst().orElseThrow();
        ComponentDescriptor nestedOwnPath = registry.components().stream()
                .filter(descriptor -> descriptor.fullClassName()
                        .equals("io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorNestedWebPathHandler$OwnPath"))
                .findFirst().orElseThrow();
        ComponentDescriptor nestedSocket = registry.components().stream()
                .filter(descriptor -> descriptor.fullClassName()
                        .equals("io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorNestedWebPathHandler$SocketEndpoint"))
                .findFirst().orElseThrow();
        assertEquals(List.of("/processor/web-root/child/outer/items"), webRoute(nestedInherited, "get").paths());
        assertEquals(List.of("/inner/items"), webRoute(nestedOwnPath, "get").paths());
        assertEquals(List.of("/processor/web-root/child/outer"), webRoute(nestedSocket, "open").paths());
        assertEquals(List.of("WS_OPEN"), webRoute(nestedSocket, "open").methods());

        ComponentDescriptor self = registry.findComponent(
                "io.fluxzero.sdk.registry.processorfixture.ProcessorSelfQuery").orElseThrow();
        HandlerRoute selfQuery = route(self, MessageType.QUERY);
        assertTrue(selfQuery.local());
        assertTrue(selfQuery.tracked());
        assertEquals(Set.of("io.fluxzero.sdk.registry.processorfixture.ProcessorSelfQuery"),
                     selfQuery.payloadTypeNames());

        ComponentDescriptor identityProvider = registry.findComponent(
                "io.fluxzero.sdk.registry.processorfixture.ProcessorIdentityProvider").orElseThrow();
        assertTrue(identityProvider.capabilities().contains(ComponentCapability.IDENTITY_PROVIDER));
        assertTrue(registry.findComponent("io.fluxzero.sdk.registry.processorfixture.ProcessorCache").orElseThrow()
                           .capabilities().contains(ComponentCapability.CACHE));
        assertTrue(registry.findComponent(
                        "io.fluxzero.sdk.registry.processorfixture.ProcessorTaskScheduler").orElseThrow()
                           .capabilities().contains(ComponentCapability.TASK_SCHEDULER));
        assertTrue(registry.findComponent(
                        "io.fluxzero.sdk.registry.processorfixture.ProcessorPropertySource").orElseThrow()
                           .capabilities().contains(ComponentCapability.PROPERTY_SOURCE));
        ComponentDescriptor payload = registry.findComponent(
                "io.fluxzero.sdk.registry.processorfixture.ProcessorCommand").orElseThrow();
        assertTrue(hasAnnotation(property(payload, "value").annotations(),
                                 "io.fluxzero.sdk.publishing.dataprotection.ProtectData"));
    }

    @Test
    void generatedProcessorRegistryHasClasspathSemanticParity(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path output = tempDir.resolve("classes");
        writeProcessorFixture(sourceRoot);

        compile(sourceRoot, output);

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{output.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            ComponentRegistry generated = ComponentRegistryJson.load(classLoader).getFirst().normalized();
            ComponentRegistry classpath = new ClasspathComponentScanner().scan(List.of(
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorCache"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorCommand"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorHandler"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorIdentityProvider"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorPropertySource"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorResult"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorSelfQuery"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.ProcessorTaskScheduler"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorNestedWebPathHandler"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorNestedWebPathHandler$Inherited"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorNestedWebPathHandler$OwnPath"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorNestedWebPathHandler$SocketEndpoint"),
                    load(classLoader, "io.fluxzero.sdk.registry.processorfixture.web.child.ProcessorWebPathHandler")
            )).normalized();

            ComponentRegistryParityAssertions.assertSemanticParity(classpath, generated);
        }
    }

    @Test
    void canBeDisabledWithCompilerOption(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path output = tempDir.resolve("classes");
        writeProcessorFixture(sourceRoot);

        compile(sourceRoot, output, "-A" + ComponentRegistryProcessor.ENABLED_OPTION + "=false");

        assertFalse(Files.exists(output.resolve(ComponentRegistryJson.DEFAULT_RESOURCE)));
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

    private static PropertyDescriptor property(ComponentDescriptor component, String name) {
        return component.properties().stream()
                .filter(property -> property.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static boolean hasAnnotation(List<AnnotationDescriptor> annotations, String qualifiedName) {
        return annotations.stream().anyMatch(annotation -> annotation.qualifiedName().equals(qualifiedName));
    }

    private static Class<?> load(ClassLoader classLoader, String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Failed to load compiled fixture class: " + className, e);
        }
    }

    private static void compile(Path sourceRoot, Path output, String... additionalOptions) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "ComponentRegistryProcessorTest requires a JDK compiler");
        Files.createDirectories(output);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<Path> sourceFiles;
            try (var files = Files.walk(sourceRoot)) {
                sourceFiles = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            }
            List<String> options = new ArrayList<>(List.of(
                    "--release", "21",
                    "-parameters",
                    "-classpath", System.getProperty("java.class.path", ""),
                    "-d", output.toString(),
                    "-processor", ComponentRegistryProcessor.class.getName()));
            options.addAll(List.of(additionalOptions));
            Boolean success = compiler.getTask(
                    null, fileManager, diagnostics, options, null,
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles)).call();
            assertTrue(Boolean.TRUE.equals(success), () -> diagnostics(diagnostics));
        }
    }

    private static String diagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .map(ComponentRegistryProcessorTest::diagnostic)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private static String diagnostic(Diagnostic<?> diagnostic) {
        return "%s:%s:%s: %s".formatted(
                diagnostic.getSource(), diagnostic.getLineNumber(), diagnostic.getColumnNumber(),
                diagnostic.getMessage(null));
    }

    private static void writeProcessorFixture(Path sourceRoot) throws Exception {
        Path packageDir = sourceRoot.resolve("io/fluxzero/sdk/registry/processorfixture");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("package-info.java"), """
                @io.fluxzero.sdk.tracking.handling.LocalHandler(allowExternalMessages = true)
                @io.fluxzero.sdk.tracking.Consumer(name = "processor-package", threads = 2)
                @io.fluxzero.common.serialization.RegisterType(contains = {"ProcessorCommand", "ProcessorResult"})
                @io.fluxzero.sdk.web.Path("/processor")
                package io.fluxzero.sdk.registry.processorfixture;
                """);
        Files.writeString(packageDir.resolve("ProcessorCommand.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                import io.fluxzero.sdk.publishing.dataprotection.ProtectData;

                public record ProcessorCommand(@ProtectData String value) {
                }
                """);
        Files.writeString(packageDir.resolve("ProcessorResult.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                public record ProcessorResult(String value) {
                }
                """);
        Files.writeString(packageDir.resolve("ProcessorHandler.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.handling.HandleCustom;
                import io.fluxzero.sdk.tracking.handling.HandleDocument;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleError;
                import io.fluxzero.sdk.tracking.handling.HandleEvent;
                import io.fluxzero.sdk.tracking.handling.HandleMetrics;
                import io.fluxzero.sdk.tracking.handling.HandleNotification;
                import io.fluxzero.sdk.tracking.handling.HandleResult;
                import io.fluxzero.sdk.tracking.handling.HandleSchedule;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.modeling.EntityId;
                import io.fluxzero.sdk.tracking.handling.Association;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.HandleWebResponse;
                import io.fluxzero.sdk.web.Path;
                import io.fluxzero.sdk.web.PathParam;

                @Consumer(name = "processor-handler", threads = 3)
                @Path("logic")
                public class ProcessorHandler {
                    @EntityId
                    private String id;

                    @Association
                    private ProcessorCommand command;

                    @LocalHandler(false)
                    @HandleCommand(allowedClasses = ProcessorCommand.class, passive = true, skipExpiredRequests = true)
                    public ProcessorResult handle(@jakarta.validation.constraints.NotBlank ProcessorCommand command) {
                        return new ProcessorResult(command.value());
                    }

                    @HandleEvent
                    public void event(ProcessorCommand command) {
                    }

                    @HandleNotification
                    public void notification(ProcessorCommand command) {
                    }

                    @HandleQuery
                    public ProcessorResult query(ProcessorCommand command) {
                        return new ProcessorResult(command.value());
                    }

                    @HandleResult
                    public void result(ProcessorResult result) {
                    }

                    @HandleSchedule
                    public void schedule(ProcessorCommand command) {
                    }

                    @HandleError
                    public void error(Throwable error) {
                    }

                    @HandleMetrics
                    public void metrics(ProcessorCommand command) {
                    }

                    @HandleWebResponse
                    public void webResponse(String response) {
                    }

                    @HandleDocument
                    public void document(ProcessorResult result) {
                    }

                    @HandleCustom("processor-custom")
                    public void custom(ProcessorCommand command) {
                    }

                    @HandleGet(value = {"items/{id}", "items"}, autoHead = false, autoOptions = false)
                    public String get(@PathParam String id) {
                        return id;
                    }
                }
                """);
        Files.writeString(packageDir.resolve("ProcessorSelfQuery.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                import io.fluxzero.sdk.tracking.handling.HandleQuery;

                public record ProcessorSelfQuery(String value) {
                    @HandleQuery
                    public String handle() {
                        return value;
                    }
                }
                """);
        Path webPackageDir = packageDir.resolve("web");
        Path childWebPackageDir = webPackageDir.resolve("child");
        Files.createDirectories(childWebPackageDir);
        Files.writeString(webPackageDir.resolve("package-info.java"), """
                @io.fluxzero.sdk.web.Path("web-root")
                package io.fluxzero.sdk.registry.processorfixture.web;
                """);
        Files.writeString(childWebPackageDir.resolve("package-info.java"), """
                @io.fluxzero.sdk.web.Path("")
                package io.fluxzero.sdk.registry.processorfixture.web.child;
                """);
        Files.writeString(childWebPackageDir.resolve("ProcessorWebPathHandler.java"), """
                package io.fluxzero.sdk.registry.processorfixture.web.child;

                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.Path;

                @Path("type")
                public class ProcessorWebPathHandler {
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
        Files.writeString(childWebPackageDir.resolve("ProcessorNestedWebPathHandler.java"), """
                package io.fluxzero.sdk.registry.processorfixture.web.child;

                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.HandleSocketOpen;
                import io.fluxzero.sdk.web.Path;

                @Path("outer")
                public class ProcessorNestedWebPathHandler {
                    public static class Inherited {
                        @HandleGet("items")
                        public String get() {
                            return "inherited";
                        }
                    }

                    @Path("/inner")
                    public static class OwnPath {
                        @HandleGet("items")
                        public String get() {
                            return "own";
                        }
                    }

                    public static class SocketEndpoint {
                        @HandleSocketOpen
                        public String open() {
                            return "open";
                        }
                    }
                }
                """);
        Files.writeString(packageDir.resolve("ProcessorIdentityProvider.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                import io.fluxzero.sdk.common.IdentityProvider;

                public class ProcessorIdentityProvider implements IdentityProvider {
                    @Override
                    public String nextFunctionalId() {
                        return "processor-functional";
                    }

                    @Override
                    public String idForName(String name) {
                        return "processor-" + name;
                    }
                }
                """);
        Files.writeString(packageDir.resolve("ProcessorCache.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;

                public class ProcessorCache extends SoftReferenceCache {
                }
                """);
        Files.writeString(packageDir.resolve("ProcessorTaskScheduler.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                import io.fluxzero.common.InMemoryTaskScheduler;

                public class ProcessorTaskScheduler extends InMemoryTaskScheduler {
                }
                """);
        Files.writeString(packageDir.resolve("ProcessorPropertySource.java"), """
                package io.fluxzero.sdk.registry.processorfixture;

                import io.fluxzero.common.application.PropertySource;

                public class ProcessorPropertySource implements PropertySource {
                    @Override
                    public String get(String name) {
                        return null;
                    }
                }
                """);
    }
}
