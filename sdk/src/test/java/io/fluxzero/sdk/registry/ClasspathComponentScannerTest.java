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
import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;
import io.fluxzero.sdk.registry.compiled.CompiledPackageHandler;
import io.fluxzero.sdk.registry.compiled.child.CompiledChildHandler;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathComponentScannerTest {

    @Test
    void indexesCompiledComponentMetadata() {
        ComponentRegistry registry = new ClasspathComponentScanner().scan(
                CompiledPackageHandler.class,
                CompiledPackageHandler.CompiledCommand.class,
                CompiledPackageHandler.CompiledResult.class);

        assertNull(registry.sourceRoot());
        PackageDescriptor packageDescriptor = registry.packages().stream()
                .filter(p -> p.packageName().equals("io.fluxzero.sdk.registry.compiled"))
                .findFirst().orElseThrow();
        assertNull(packageDescriptor.sourceFile());
        assertEquals("compiled-package", packageDescriptor.consumerMetadata().orElseThrow().name());
        assertTrue(packageDescriptor.capabilities().contains(ComponentCapability.PACKAGE_LOCAL_HANDLER));
        assertTrue(packageDescriptor.capabilities().contains(ComponentCapability.REGISTERED_TYPE));
        assertTrue(packageDescriptor.registeredTypes().getFirst().candidateTypeNames().stream()
                .anyMatch(name -> name.endsWith("CompiledPackageHandler.CompiledCommand")));

        ComponentDescriptor component = registry.components().stream()
                .filter(c -> c.fullClassName().equals(CompiledPackageHandler.class.getName()))
                .findFirst().orElseThrow();
        assertNull(component.sourceFile());
        assertTrue(component.capabilities().contains(ComponentCapability.CLASSPATH_COMPONENT));
        assertTrue(component.capabilities().contains(ComponentCapability.HANDLER));
        assertTrue(component.capabilities().contains(ComponentCapability.WEB_REQUEST_HANDLER));
        assertEquals("compiled-package", component.consumerMetadata().orElseThrow().name());

        HandlerRoute command = route(component, MessageType.COMMAND);
        assertFalse(command.local());
        assertTrue(command.tracked());
        assertTrue(command.passive());
        assertTrue(command.skipExpiredRequests());
        assertEquals("HandleCommand", command.annotationMetadata().orElseThrow().name());
        assertEquals("handle", command.executableMetadata().orElseThrow().name());
        assertEquals(List.of("NotBlank"), command.executableMetadata().orElseThrow()
                .parameters().getFirst().annotations().stream().map(AnnotationDescriptor::name).toList());
        assertEquals(command.allowedClassNames(), command.payloadTypeNames());
        assertTrue(command.allowedClassNames().contains(
                CompiledPackageHandler.CompiledCommand.class.getCanonicalName()));

        HandlerRoute query = route(component, MessageType.QUERY);
        assertTrue(query.disabled());

        HandlerRoute web = route(component, MessageType.WEBREQUEST);
        WebRouteDescriptor webRoute = web.webRoutes().getFirst();
        assertEquals(List.of("/compiled/logic/items/{id}", "/compiled/logic/items"), webRoute.paths());
        assertEquals(List.of("GET"), webRoute.methods());
        assertFalse(webRoute.autoHead());
        assertFalse(webRoute.autoOptions());
    }

    @Test
    void inheritedPackageMetadataAppliesToLowerPackages() {
        ComponentDescriptor component = new ClasspathComponentScanner().scan(CompiledChildHandler.class)
                .components().getFirst();

        HandlerRoute route = route(component, MessageType.COMMAND);
        assertTrue(route.local());
        assertTrue(route.tracked());
        assertEquals("compiled-package", component.consumerMetadata().orElseThrow().name());
    }

    @Test
    void indexesCompiledPayloadSelfHandlersAsLocalComponentRoutes() {
        ComponentRegistry registry = new ClasspathComponentScanner().scan(
                CompiledSelfQuery.class, CompiledTrackedSelfCommand.class);

        HandlerRoute query = route(
                registry.findComponent(CompiledSelfQuery.class.getName()).orElseThrow(), MessageType.QUERY);
        HandlerRoute command = route(
                registry.findComponent(CompiledTrackedSelfCommand.class.getName()).orElseThrow(), MessageType.COMMAND);

        assertTrue(query.local());
        assertFalse(query.tracked());
        assertEquals(java.util.Set.of(CompiledSelfQuery.class.getCanonicalName()), query.payloadTypeNames());
        assertFalse(command.local());
        assertTrue(command.tracked());
        assertEquals(java.util.Set.of(CompiledTrackedSelfCommand.class.getCanonicalName()),
                     command.payloadTypeNames());
    }

    @Test
    void indexesCompiledInfrastructureCapabilities() {
        ComponentRegistry registry = new ClasspathComponentScanner().scan(
                CompiledIdentityProvider.class, CompiledCache.class, CompiledTaskScheduler.class,
                CompiledPropertySource.class);
        ComponentDescriptor component = registry
                .findComponent(CompiledIdentityProvider.class.getName()).orElseThrow();

        assertTrue(component.capabilities().contains(ComponentCapability.IDENTITY_PROVIDER));
        assertTrue(component.superTypeNames().contains(IdentityProvider.class.getName()));
        assertTrue(registry.findComponent(CompiledCache.class.getName()).orElseThrow()
                           .capabilities().contains(ComponentCapability.CACHE));
        assertTrue(registry.findComponent(CompiledTaskScheduler.class.getName()).orElseThrow()
                           .capabilities().contains(ComponentCapability.TASK_SCHEDULER));
        assertTrue(registry.findComponent(CompiledPropertySource.class.getName()).orElseThrow()
                           .capabilities().contains(ComponentCapability.PROPERTY_SOURCE));
    }

    private static HandlerRoute route(ComponentDescriptor component, MessageType messageType) {
        return component.handlerRoutes().stream()
                .filter(route -> route.messageType() == messageType)
                .findFirst().orElseThrow();
    }

    private record CompiledSelfQuery(String value) {
        @HandleQuery
        String handle() {
            return value;
        }
    }

    @TrackSelf
    private record CompiledTrackedSelfCommand(String value) {
        @HandleCommand
        String handle() {
            return value;
        }
    }

    private static class CompiledIdentityProvider implements IdentityProvider {
        @Override
        public String nextFunctionalId() {
            return "compiled-functional";
        }

        @Override
        public String idForName(String name) {
            return "compiled-" + name;
        }
    }

    private static class CompiledCache extends SoftReferenceCache {
    }

    private static class CompiledTaskScheduler extends InMemoryTaskScheduler {
    }

    private static class CompiledPropertySource implements PropertySource {
        @Override
        public String get(String name) {
            return null;
        }
    }
}
