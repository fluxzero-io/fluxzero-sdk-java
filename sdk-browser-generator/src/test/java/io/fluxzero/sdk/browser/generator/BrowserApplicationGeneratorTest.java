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

package io.fluxzero.sdk.browser.generator;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentCapability;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentKind;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.ExecutableKind;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.RegisteredTypeDescriptor;
import io.fluxzero.sdk.registry.RegistryComponentMetadataLookup;
import io.fluxzero.sdk.registry.WebRouteDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserApplicationGeneratorTest {

    @Test
    void generatesBrowserApplicationAndManifestFromRegistry() {
        ComponentRegistry registry = new ComponentRegistry(null, List.of(), List.of(new ComponentDescriptor(
                null,
                null,
                ComponentKind.RECORD,
                "example",
                "CreateOrder",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Set.of(
                        new HandlerRoute(
                                MessageType.COMMAND,
                                new AnnotationDescriptor("HandleCommand", "io.fluxzero.sdk.HandleCommand", Map.of()),
                                null,
                                false,
                                false,
                                false,
                                true,
                                true,
                                Set.of("example.CreateOrder"),
                                Set.of("example.CreateOrder"),
                                List.of()),
                        new HandlerRoute(
                                MessageType.WEBREQUEST,
                                new AnnotationDescriptor("HandleGet", "io.fluxzero.sdk.web.HandleGet", Map.of()),
                                null,
                                false,
                                false,
                                false,
                                true,
                                false,
                                Set.of(),
                                Set.of(),
                                List.of(new WebRouteDescriptor(List.of("/orders/{id}"), List.of("GET"), true, true)))),
                List.of(new RegisteredTypeDescriptor(
                        "example",
                        List.of("Order"),
                        List.of("example.CreateOrder", "example.OrderCreated"),
                        new AnnotationDescriptor("RegisterType", "io.fluxzero.sdk.RegisterType", Map.of()))),
                null,
                Set.of(ComponentCapability.HANDLER, ComponentCapability.WEB_REQUEST_HANDLER,
                       ComponentCapability.REGISTERED_TYPE))));

        BrowserGenerationResult result = new BrowserApplicationGenerator().generate(registry);

        assertEquals(2, result.sources().size());
        assertEquals(1, result.counters().get("components"));
        assertEquals(2, result.counters().get("handlers"));
        assertEquals(1, result.counters().get("webRoutes"));
        assertTrue(result.manifestJson().contains("\"handler.command\""));
        assertTrue(result.manifestJson().contains("\"web.pathParam\""));
        assertTrue(result.manifestJson().contains("\"example.CreateOrder\""));
        assertTrue(result.manifestJson().contains("\"window.fluxzeroConformance.runAll()\""));
        assertTrue(result.sources().getFirst().content().contains("BrowserExecutionCore"));
        assertTrue(result.sources().getFirst().content().contains("MessageType.COMMAND"));
        assertTrue(result.sources().getFirst().content().contains("generatedRegistry()"));
        assertTrue(result.sources().getFirst().content().contains("core.register(\"example.CreateOrder\""));
    }

    @Test
    void generatesFromSharedMetadataLookupFacade() {
        ComponentRegistry registry = new ComponentRegistry(null, List.of(), List.of(new ComponentDescriptor(
                null,
                null,
                ComponentKind.CLASS,
                "example",
                "LookupBackedHandler",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Set.of(new HandlerRoute(
                        MessageType.COMMAND,
                        new AnnotationDescriptor("HandleCommand", "io.fluxzero.sdk.HandleCommand", Map.of()),
                        null,
                        false,
                        false,
                        false,
                        true,
                        true,
                        Set.of("example.LookupCommand"),
                        Set.of(),
                        List.of())),
                List.of(),
                null,
                Set.of(ComponentCapability.HANDLER))));

        BrowserGenerationResult result = new BrowserApplicationGenerator().generate(
                RegistryComponentMetadataLookup.of(registry));

        assertEquals(1, result.counters().get("components"));
        assertTrue(result.sources().getFirst().content().contains("LookupBackedHandler"));
    }

    @Test
    void generatedApplicationLowersAuthMetadataToSharedPolicyRules() {
        ExecutableDescriptor executable = new ExecutableDescriptor(
                ExecutableKind.METHOD,
                "handle",
                "java.lang.String",
                List.of(),
                List.of(new AnnotationDescriptor(
                        "RequiresAnyRole",
                        "io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole",
                        Map.of("value", List.of("admin")))));
        ComponentRegistry registry = new ComponentRegistry(null, List.of(), List.of(new ComponentDescriptor(
                null,
                null,
                ComponentKind.CLASS,
                "example",
                "SecuredHandler",
                List.of(),
                List.of(new AnnotationDescriptor(
                        "RequiresUser",
                        "io.fluxzero.sdk.tracking.handling.authentication.RequiresUser",
                        Map.of())),
                List.of(),
                List.of(executable),
                Set.of(new HandlerRoute(
                        MessageType.COMMAND,
                        new AnnotationDescriptor("HandleCommand", "io.fluxzero.sdk.HandleCommand", Map.of()),
                        executable,
                        false,
                        false,
                        false,
                        true,
                        true,
                        Set.of("example.SecuredCommand"),
                        Set.of(),
                        List.of())),
                List.of(),
                null,
                Set.of(ComponentCapability.HANDLER))));

        String source = new BrowserApplicationGenerator().generate(registry).sources().getFirst().content();

        assertTrue(source.contains("GeneratedAuthorizedHandler"));
        assertTrue(source.contains("AuthorizationPolicy.evaluate"));
        assertTrue(source.contains("new io.fluxzero.sdk.tracking.handling.authentication.AuthorizationRule(\"admin\""));
        assertTrue(source.contains("generatedUserMetadata(\"admin\")"));
    }
}
