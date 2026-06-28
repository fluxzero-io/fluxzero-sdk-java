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

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentRegistryJsonTest {

    @Test
    void roundTripsComponentRegistryJson() {
        ComponentRegistry registry = registry();

        ComponentRegistry result = ComponentRegistryJson.fromJson(ComponentRegistryJson.toJson(registry));

        assertEquals(registry.normalized(), result.normalized());
        String json = ComponentRegistryJson.toJson(registry);
        assertTrue(json.contains("\"componentKind\""));
        assertTrue(json.contains("\"properties\""));
        assertTrue(json.contains("\"nestedAnnotations\""));
        assertFalse(json.contains("@class"));
    }

    @Test
    void loadsGeneratedRegistryResources(@TempDir Path tempDir) throws Exception {
        Path resource = tempDir.resolve(ComponentRegistryJson.DEFAULT_RESOURCE);
        ComponentRegistryJson.write(registry(), resource);

        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()}, null)) {
            List<ComponentRegistry> registries = ComponentRegistryJson.load(classLoader);

            assertEquals(1, registries.size());
            assertEquals("io.fluxzero.sdk.registry.json.JsonHandler",
                         registries.getFirst().components().getFirst().fullClassName());
            assertTrue(registries.getFirst().components().getFirst().routes().getFirst().annotation()
                               .isOrHas("RequiresAnyRole",
                                        "io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole"));
        }
    }

    @Test
    void preservesEmptyWebRoutePaths() {
        AnnotationDescriptor annotation = new AnnotationDescriptor(
                "HandleWeb", "io.fluxzero.sdk.web.HandleWeb", Map.of("value", List.of("")));
        ExecutableDescriptor executable = new ExecutableDescriptor(
                ExecutableKind.METHOD, "web", "java.lang.String", List.of(), List.of(annotation));
        HandlerRoute route = new HandlerRoute(
                MessageType.WEBREQUEST, annotation, executable, false, false, false,
                false, true, Set.of(), Set.of(), List.of(new WebRouteDescriptor(
                List.of(""), List.of("ANY"), true, true)));
        ComponentDescriptor component = new ComponentDescriptor(
                null, null, ComponentKind.CLASS, "io.fluxzero.sdk.registry.json", "RootWebHandler",
                List.of(), List.of(), List.of(), List.of(executable), Set.of(route), List.of(), null,
                Set.of(ComponentCapability.HANDLER, ComponentCapability.WEB_REQUEST_HANDLER));

        ComponentRegistry result = ComponentRegistryJson.fromJson(ComponentRegistryJson.toJson(
                new ComponentRegistry(null, List.of(), List.of(component))));

        assertEquals(List.of(""), result.components().getFirst().routes().getFirst().webRoutes().getFirst().paths());
    }

    static ComponentRegistry registry() {
        AnnotationDescriptor consumerAnnotation = new AnnotationDescriptor(
                "Consumer", "io.fluxzero.sdk.tracking.Consumer", Map.of("name", List.of("json-consumer")));
        ConsumerDescriptor consumer = new ConsumerDescriptor(
                "json-consumer", consumerAnnotation.attributes(), consumerAnnotation);
        AnnotationDescriptor roleAnnotation = new AnnotationDescriptor(
                "RequiresAnyRole", "io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole",
                Map.of("value", List.of("admin")));
        AnnotationDescriptor nestedAnnotation = new AnnotationDescriptor(
                "Nested", "io.fluxzero.sdk.registry.json.Nested",
                Map.of("enabled", List.of("true")));
        AnnotationDescriptor handlerAnnotation = new AnnotationDescriptor(
                "HandleCommand", "io.fluxzero.sdk.tracking.handling.HandleCommand",
                Map.of("allowedClasses", List.of("io.fluxzero.sdk.registry.json.JsonCommand"),
                       "passive", List.of("true")),
                Map.of("nested", List.of(nestedAnnotation)),
                List.of(roleAnnotation));
        ExecutableDescriptor executable = new ExecutableDescriptor(
                ExecutableKind.METHOD, "handle", "io.fluxzero.sdk.registry.json.JsonResult",
                List.of(new ParameterDescriptor(
                        "command", "io.fluxzero.sdk.registry.json.JsonCommand", List.of())),
                List.of(handlerAnnotation));
        HandlerRoute route = new HandlerRoute(
                MessageType.COMMAND, handlerAnnotation, executable, false, true, false,
                true, false, Set.of("io.fluxzero.sdk.registry.json.JsonCommand"),
                Set.of("io.fluxzero.sdk.registry.json.JsonCommand"), List.of());
        ComponentDescriptor component = new ComponentDescriptor(
                Path.of("src/main/fluxzero/io/fluxzero/sdk/registry/json/JsonHandler.java"),
                null,
                ComponentKind.CLASS,
                "io.fluxzero.sdk.registry.json",
                "JsonHandler",
                List.of(),
                List.of(consumerAnnotation),
                List.of(new PropertyDescriptor(
                        "id", "java.lang.String", "java.lang.String",
                        List.of(new AnnotationDescriptor(
                                "EntityId", "io.fluxzero.sdk.modeling.EntityId", Map.of())))),
                List.of(executable),
                Set.of(route),
                List.of(),
                consumer,
                Set.of(ComponentCapability.SOURCE_COMPONENT, ComponentCapability.HANDLER,
                       ComponentCapability.LOCAL_HANDLER, ComponentCapability.CONSUMER));
        return new ComponentRegistry(Path.of("src/main/fluxzero"), List.of(), List.of(component));
    }
}
