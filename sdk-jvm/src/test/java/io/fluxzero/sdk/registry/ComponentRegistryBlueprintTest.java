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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentRegistryBlueprintTest {

    @Test
    void rendersMarkdownBlueprint() {
        ComponentRegistry registry = registry();

        String markdown = ComponentRegistryBlueprint.from(registry).toMarkdown();

        assertTrue(markdown.contains("# Fluxzero App Blueprint"));
        assertTrue(markdown.contains("| Components | 1 |"));
        assertTrue(markdown.contains("| Properties | 1 |"));
        assertTrue(markdown.contains("| Handler routes | 2 |"));
        assertTrue(markdown.contains("```mermaid"));
        assertTrue(markdown.contains("| BlueprintHandler | io.fluxzero.example |"));
        assertTrue(markdown.contains("COMMAND"));
        assertTrue(markdown.contains("GET /blueprint/{id}"));
        assertTrue(markdown.contains("handle(command: BlueprintCommand) -> BlueprintResult"));
        assertTrue(markdown.contains("| BlueprintHandler | id | String | EntityId |"));
        assertTrue(markdown.contains("package-consumer"));
        assertTrue(markdown.contains("| Source root | src/main/fluxzero |"));
        assertTrue(markdown.contains("| io.fluxzero.example | CONSUMER, REGISTERED_TYPE |"));
        assertTrue(markdown.contains("io/fluxzero/example/BlueprintHandler.java"));
    }

    @Test
    void writesMarkdownBlueprint(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("reports/fluxzero-blueprint.md");

        ComponentRegistryBlueprint.from(registry()).withTitle("Downstream Blueprint").writeMarkdown(output);

        assertTrue(Files.isRegularFile(output));
        String markdown = Files.readString(output);
        assertTrue(markdown.startsWith("# Downstream Blueprint"));
        assertEquals(markdown, ComponentRegistryBlueprint.from(registry()).withTitle("Downstream Blueprint")
                .toMarkdown());
    }

    @Test
    void includesImplicitPackagesForComponentsWithoutPackageInfo() {
        HandlerRoute commandRoute = new HandlerRoute(
                MessageType.COMMAND,
                null,
                new ExecutableDescriptor(ExecutableKind.METHOD, "handle", "java.lang.String",
                                         List.of(new ParameterDescriptor("command", "java.lang.String", List.of())),
                                         List.of()),
                false, false, false, true, false,
                Set.of("java.lang.String"), Set.of(), List.of());
        ComponentDescriptor component = new ComponentDescriptor(
                Path.of("src/main/fluxzero/com/example/ImplicitHandler.java"),
                null,
                ComponentKind.CLASS,
                "com.example",
                "ImplicitHandler",
                List.of(),
                List.of(),
                List.of(),
                List.of(commandRoute.executableMetadata().orElseThrow()),
                Set.of(commandRoute),
                List.of(),
                null,
                Set.of(ComponentCapability.SOURCE_COMPONENT, ComponentCapability.HANDLER));
        ComponentRegistry registry = new ComponentRegistry(Path.of("src/main/fluxzero"), List.of(), List.of(component));

        String markdown = ComponentRegistryBlueprint.from(registry).toMarkdown();

        assertTrue(markdown.contains("| Packages | 1 |"));
        assertTrue(markdown.contains("| com.example |  |  |  |  |"));
    }

    private static ComponentRegistry registry() {
        AnnotationDescriptor consumerAnnotation = new AnnotationDescriptor(
                "Consumer", "io.fluxzero.sdk.tracking.Consumer",
                Map.of("name", List.of("package-consumer")));
        ConsumerDescriptor consumer = new ConsumerDescriptor(
                "package-consumer", Map.of("name", List.of("package-consumer")), consumerAnnotation);
        AnnotationDescriptor registerTypeAnnotation = new AnnotationDescriptor(
                "RegisterType", "io.fluxzero.common.serialization.RegisterType",
                Map.of("contains", List.of("Blueprint")));
        RegisteredTypeDescriptor registeredType = new RegisteredTypeDescriptor(
                "io.fluxzero.example", List.of("Blueprint"),
                List.of("io.fluxzero.example.BlueprintCommand"), registerTypeAnnotation);
        PackageDescriptor packageDescriptor = new PackageDescriptor(
                "io.fluxzero.example", Path.of("src/main/fluxzero/io/fluxzero/example/package-info.java"),
                List.of(consumerAnnotation), List.of(registeredType), consumer,
                Set.of(ComponentCapability.CONSUMER, ComponentCapability.REGISTERED_TYPE));
        HandlerRoute commandRoute = new HandlerRoute(
                MessageType.COMMAND,
                new AnnotationDescriptor("HandleCommand", "io.fluxzero.sdk.tracking.handling.HandleCommand",
                                         Map.of()),
                new ExecutableDescriptor(ExecutableKind.METHOD, "handle",
                                         "io.fluxzero.example.BlueprintResult",
                                         List.of(new ParameterDescriptor(
                                                 "command", "io.fluxzero.example.BlueprintCommand", List.of())),
                                         List.of()),
                false, true, true, true, true,
                Set.of("io.fluxzero.example.BlueprintCommand"),
                Set.of("io.fluxzero.example.BlueprintCommand"),
                List.of());
        HandlerRoute webRoute = new HandlerRoute(
                MessageType.WEBREQUEST,
                new AnnotationDescriptor("HandleGet", "io.fluxzero.sdk.web.HandleGet",
                                         Map.of("value", List.of("/blueprint/{id}"))),
                new ExecutableDescriptor(ExecutableKind.METHOD, "get", "java.lang.String",
                                         List.of(new ParameterDescriptor("id", "java.lang.String", List.of())),
                                         List.of()),
                false, false, false, true, true,
                Set.of(), Set.of(),
                List.of(new WebRouteDescriptor(List.of("/blueprint/{id}"), List.of("GET"), true, true)));
        ComponentDescriptor component = new ComponentDescriptor(
                Path.of("src/main/fluxzero/io/fluxzero/example/BlueprintHandler.java"),
                packageDescriptor.sourceFile(),
                ComponentKind.CLASS,
                "io.fluxzero.example",
                "BlueprintHandler",
                List.of(),
                List.of(),
                List.of(new PropertyDescriptor(
                        "id", "java.lang.String", "java.lang.String",
                        List.of(new AnnotationDescriptor(
                                "EntityId", "io.fluxzero.sdk.modeling.EntityId", Map.of())))),
                List.of(commandRoute.executableMetadata().orElseThrow(), webRoute.executableMetadata().orElseThrow()),
                Set.of(commandRoute, webRoute),
                List.of(),
                consumer,
                Set.of(ComponentCapability.SOURCE_COMPONENT, ComponentCapability.HANDLER,
                       ComponentCapability.LOCAL_HANDLER, ComponentCapability.TRACKING_HANDLER,
                       ComponentCapability.WEB_REQUEST_HANDLER));
        return new ComponentRegistry(Path.of("src/main/fluxzero"), List.of(packageDescriptor), List.of(component));
    }
}
