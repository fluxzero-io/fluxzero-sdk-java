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

class ComponentRegistryGeneratorTest {

    @Test
    void generatesRegistryJsonAndBlueprintFromFluxzeroSourceRoot(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeGeneratorFixture(sourceRoot);
        Path output = tempDir.resolve("target/classes").resolve(ComponentRegistryJson.DEFAULT_RESOURCE);
        Path blueprint = tempDir.resolve("target/fluxzero-blueprint.md");

        ComponentRegistry registry = ComponentRegistryGenerator.generate(
                sourceRoot, output, blueprint, "Generated Source Registry");

        assertEquals(sourceRoot, registry.sourceRoot());
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.isRegularFile(blueprint));

        ComponentRegistry read = ComponentRegistryJson.read(output);
        ComponentDescriptor component = read.findComponent(
                "io.fluxzero.sdk.registry.generatorfixture.GeneratorHandler").orElseThrow();
        assertTrue(component.capabilities().contains(ComponentCapability.HANDLER));
        assertEquals(MessageType.COMMAND, component.handlerRoutes().iterator().next().messageType());
        String markdown = Files.readString(blueprint);
        assertTrue(markdown.contains("Generated Source Registry"));
        assertTrue(markdown.contains("GeneratorHandler"));
        assertTrue(markdown.contains("Consumer Graph"));
    }

    @Test
    void generatesRegistryJsonFromFluxzeroTestSourceRoot(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve(ComponentRegistryGenerator.DEFAULT_TEST_SOURCE_ROOT);
        writeGeneratorFixture(sourceRoot);
        Path output = tempDir.resolve(ComponentRegistryGenerator.DEFAULT_TEST_OUTPUT);

        ComponentRegistry registry = ComponentRegistryGenerator.generate(
                sourceRoot, output, null, "Generated Test Source Registry");

        assertEquals(sourceRoot, registry.sourceRoot());
        assertTrue(Files.isRegularFile(output));
        ComponentRegistry read = ComponentRegistryJson.read(output);
        assertTrue(read.findComponent("io.fluxzero.sdk.registry.generatorfixture.GeneratorHandler").isPresent());
    }

    @Test
    void skipsMissingSourceRootByDefault(@TempDir Path tempDir) {
        Path output = tempDir.resolve("target/classes").resolve(ComponentRegistryJson.DEFAULT_RESOURCE);
        Path blueprint = tempDir.resolve("target/fluxzero-blueprint.md");

        ComponentRegistry registry = ComponentRegistryGenerator.generate(
                tempDir.resolve("missing"), output, blueprint, "Missing Source Registry");

        assertTrue(registry.isEmpty());
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(blueprint));
    }

    @Test
    void mergesExistingRegistryOutputWhenRequested(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/fluxzero");
        writeGeneratorFixture(sourceRoot);
        Path output = tempDir.resolve("target/classes").resolve(ComponentRegistryJson.DEFAULT_RESOURCE);
        ComponentRegistryJson.write(existingRegistry(), output);

        ComponentRegistry registry = ComponentRegistryGenerator.generate(
                sourceRoot, output, null, "Merged Source Registry", false, true);

        assertTrue(registry.findComponent("io.fluxzero.sdk.registry.generatorfixture.ExistingHandler").isPresent());
        assertTrue(registry.findComponent("io.fluxzero.sdk.registry.generatorfixture.GeneratorHandler").isPresent());
        ComponentRegistry read = ComponentRegistryJson.read(output);
        assertEquals(registry.normalized(), read.normalized());
    }

    private static void writeGeneratorFixture(Path sourceRoot) throws Exception {
        Path packageDir = sourceRoot.resolve("io/fluxzero/sdk/registry/generatorfixture");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("GeneratorCommand.java"), """
                package io.fluxzero.sdk.registry.generatorfixture;

                public record GeneratorCommand(String value) {
                }
                """);
        Files.writeString(packageDir.resolve("GeneratorHandler.java"), """
                package io.fluxzero.sdk.registry.generatorfixture;

                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @Consumer(name = "generator-consumer", threads = 1)
                @LocalHandler
                public class GeneratorHandler {
                    @HandleCommand
                    public String handle(GeneratorCommand command) {
                        return command.value();
                    }
                }
                """);
    }

    private static ComponentRegistry existingRegistry() {
        HandlerRoute route = new HandlerRoute(
                MessageType.QUERY, true, false,
                Set.of("io.fluxzero.sdk.registry.generatorfixture.ExistingQuery"));
        ComponentDescriptor component = new ComponentDescriptor(
                null, null, "io.fluxzero.sdk.registry.generatorfixture", "ExistingHandler",
                Set.of(route), Set.of(ComponentCapability.HANDLER));
        return new ComponentRegistry(null, List.of(), List.of(component));
    }
}
