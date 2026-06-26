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
import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.execution.OnDemandExecution;
import io.fluxzero.sdk.registry.compiled.CompiledPackageHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FluxzeroComponentRegistryTest {

    @Test
    void normalHandlerRegistrationContributesClasspathComponentRegistry() {
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableShutdownHook()
                .disableScheduledCommandHandler()
                .build(LocalClient.newInstance(null));
        try {
            Registration registration = fluxzero.registerHandlers(new CompiledPackageHandler());

            ComponentDescriptor component = component(fluxzero.componentRegistry(), CompiledPackageHandler.class);
            assertNull(fluxzero.componentRegistry().sourceRoot());
            assertTrue(component.capabilities().contains(ComponentCapability.CLASSPATH_COMPONENT));
            assertTrue(component.capabilities().contains(ComponentCapability.HANDLER));
            assertTrue(component.messageTypes().containsAll(Set.of(MessageType.COMMAND, MessageType.QUERY,
                                                                   MessageType.WEBREQUEST)));
            assertTrue(route(component, MessageType.COMMAND).allowedClassNames()
                    .contains(CompiledPackageHandler.CompiledCommand.class.getCanonicalName()));

            registration.cancel();
            assertFalse(fluxzero.componentRegistry().components().stream()
                                .anyMatch(c -> c.fullClassName().equals(CompiledPackageHandler.class.getName())));
        } finally {
            fluxzero.close(true);
        }
    }

    @Test
    void onDemandExecutionContributesSourceComponentRegistry(@TempDir Path tempDir) throws Exception {
        writeOnDemandSource(tempDir);
        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .cacheTtl(Duration.ofMinutes(10))
                .startTracking(false)
                .build()) {
            Fluxzero fluxzero = DefaultFluxzero.builder()
                    .disableShutdownHook()
                    .disableScheduledCommandHandler()
                    .build(LocalClient.newInstance(null));
            try {
                Registration registration = execution.registerWith(fluxzero);

                ComponentRegistry registry = fluxzero.componentRegistry();
                assertEquals(tempDir, registry.sourceRoot());

                ComponentDescriptor component = registry.components().stream()
                        .filter(c -> c.fullClassName().equals("io.fluxzero.sdk.registry.generated.SourceRegistryHandler"))
                        .findFirst().orElseThrow();
                assertTrue(component.capabilities().contains(ComponentCapability.HANDLER));
                HandlerRoute route = route(component, MessageType.COMMAND);
                assertTrue(route.local());
                assertFalse(route.tracked());
                assertEquals(Set.of(SourceRegistryCommand.class.getCanonicalName()), route.payloadTypeNames());

                registration.cancel();
                assertTrue(fluxzero.componentRegistry().isEmpty());
            } finally {
                fluxzero.close(true);
            }
        }
    }

    @Test
    void builderLoadsGeneratedComponentRegistryResources(@TempDir Path tempDir) throws Exception {
        ComponentRegistryJson.write(ComponentRegistryJsonTest.registry(),
                                    tempDir.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader =
                     new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()}, previousClassLoader)) {
            Thread.currentThread().setContextClassLoader(classLoader);
            Fluxzero fluxzero = DefaultFluxzero.builder()
                    .disableShutdownHook()
                    .disableScheduledCommandHandler()
                    .build(LocalClient.newInstance(null));
            try {
                assertTrue(fluxzero.componentRegistry()
                                   .findComponent("io.fluxzero.sdk.registry.json.JsonHandler").isPresent());
            } finally {
                fluxzero.close(true);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    @Test
    void writesBlueprintOnlyWhenConfigured(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("target/fluxzero-blueprint.md");
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableShutdownHook()
                .disableScheduledCommandHandler()
                .replacePropertySource(existing -> name -> ComponentRegistryBlueprint.BLUEPRINT_PROPERTY.equals(name)
                        ? output.toString() : existing.get(name))
                .build(LocalClient.newInstance(null));
        try {
            Registration registration = fluxzero.registerComponentRegistry(ComponentRegistryJsonTest.registry());

            assertTrue(Files.isRegularFile(output));
            String markdown = Files.readString(output);
            assertTrue(markdown.contains("## Component Graph"));
            assertTrue(markdown.contains("## Consumer Graph"));
            assertTrue(markdown.contains("JsonHandler"));
            assertTrue(markdown.contains("json-consumer"));

            registration.cancel();
        } finally {
            fluxzero.close(true);
        }
    }

    private static ComponentDescriptor component(ComponentRegistry registry, Class<?> type) {
        return registry.components().stream()
                .filter(c -> c.fullClassName().equals(type.getName()))
                .findFirst().orElseThrow();
    }

    private static HandlerRoute route(ComponentDescriptor component, MessageType messageType) {
        return component.handlerRoutes().stream()
                .filter(r -> r.messageType() == messageType)
                .findFirst().orElseThrow();
    }

    private static void writeOnDemandSource(Path sourceRoot) throws Exception {
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("SourceRegistryHandler.java"), """
                package io.fluxzero.sdk.registry.generated;

                import io.fluxzero.sdk.registry.FluxzeroComponentRegistryTest.SourceRegistryCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;

                @LocalHandler
                public class SourceRegistryHandler {
                    @HandleCommand
                    public String handle(SourceRegistryCommand command) {
                        return command.value();
                    }
                }
                """);
    }

    public record SourceRegistryCommand(String value) {
    }
}
