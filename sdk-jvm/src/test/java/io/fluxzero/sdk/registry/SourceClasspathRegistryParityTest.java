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
import io.fluxzero.sdk.registry.parity.ParityCommand;
import io.fluxzero.sdk.registry.parity.ParityHandler;
import io.fluxzero.sdk.registry.parity.ParityResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceClasspathRegistryParityTest {

    @Test
    void sourceAndClasspathScannersProduceEquivalentFluxzeroSemantics(@TempDir Path sourceRoot) throws Exception {
        writeParitySources(sourceRoot);

        ComponentRegistry source = new SourceComponentScanner().scan(sourceRoot).normalized();
        ComponentRegistry classpath = new ClasspathComponentScanner().scan(
                ParityCommand.class, ParityHandler.class, ParityResult.class).normalized();

        assertPackageParity(source, classpath);
        assertComponentParity(source, classpath);
        assertRouteParity(source, classpath, MessageType.COMMAND);
        assertRouteParity(source, classpath, MessageType.QUERY);
        assertRouteParity(source, classpath, MessageType.WEBREQUEST);
    }

    private static void assertPackageParity(ComponentRegistry source, ComponentRegistry classpath) {
        PackageDescriptor sourcePackage = source.packages().stream()
                .filter(p -> p.packageName().equals("io.fluxzero.sdk.registry.parity"))
                .findFirst().orElseThrow();
        PackageDescriptor classpathPackage = classpath.packages().stream()
                .filter(p -> p.packageName().equals("io.fluxzero.sdk.registry.parity"))
                .findFirst().orElseThrow();

        assertEquals(sourcePackage.consumerMetadata().orElseThrow().name(),
                     classpathPackage.consumerMetadata().orElseThrow().name());
        assertEquals(sourcePackage.capabilities(), classpathPackage.capabilities());
        assertEquals(sourcePackage.registeredTypes().getFirst().root(), classpathPackage.registeredTypes().getFirst().root());
        assertEquals(sourcePackage.registeredTypes().getFirst().contains(),
                     classpathPackage.registeredTypes().getFirst().contains());
        assertEquals(sourcePackage.registeredTypes().getFirst().candidateTypeNames(),
                     classpathPackage.registeredTypes().getFirst().candidateTypeNames());
    }

    private static void assertComponentParity(ComponentRegistry source, ComponentRegistry classpath) {
        ComponentDescriptor sourceComponent = source.findComponent(ParityHandler.class).orElseThrow();
        ComponentDescriptor classpathComponent = classpath.findComponent(ParityHandler.class).orElseThrow();

        assertEquals(sourceComponent.componentKind(), classpathComponent.componentKind());
        assertEquals(sourceComponent.fullClassName(), classpathComponent.fullClassName());
        assertEquals(sourceComponent.consumerMetadata().orElseThrow().name(),
                     classpathComponent.consumerMetadata().orElseThrow().name());
        assertEquals(withoutDiscoveryMode(sourceComponent.capabilities()),
                     withoutDiscoveryMode(classpathComponent.capabilities()));
        assertEquals(sourceComponent.messageTypes(), classpathComponent.messageTypes());
        assertEquals(sourceComponent.registeredTypes().getFirst().root(),
                     classpathComponent.registeredTypes().getFirst().root());
        assertEquals(sourceComponent.registeredTypes().getFirst().contains(),
                     classpathComponent.registeredTypes().getFirst().contains());
        assertEquals(sourceComponent.registeredTypes().getFirst().candidateTypeNames(),
                     classpathComponent.registeredTypes().getFirst().candidateTypeNames());
    }

    private static void assertRouteParity(ComponentRegistry source, ComponentRegistry classpath,
                                          MessageType messageType) {
        HandlerRoute sourceRoute = source.findComponent(ParityHandler.class).orElseThrow()
                .route(messageType).orElseThrow();
        HandlerRoute classpathRoute = classpath.findComponent(ParityHandler.class).orElseThrow()
                .route(messageType).orElseThrow();

        assertEquals(sourceRoute.messageType(), classpathRoute.messageType());
        assertEquals(sourceRoute.disabled(), classpathRoute.disabled());
        assertEquals(sourceRoute.passive(), classpathRoute.passive());
        assertEquals(sourceRoute.skipExpiredRequests(), classpathRoute.skipExpiredRequests());
        assertEquals(sourceRoute.local(), classpathRoute.local());
        assertEquals(sourceRoute.tracked(), classpathRoute.tracked());
        assertEquals(sourceRoute.payloadTypeNames(), classpathRoute.payloadTypeNames());
        assertEquals(sourceRoute.allowedClassNames(), classpathRoute.allowedClassNames());
        assertEquals(sourceRoute.annotationMetadata().orElseThrow().name(),
                     classpathRoute.annotationMetadata().orElseThrow().name());
        assertExecutableParity(sourceRoute, classpathRoute);
        assertEquals(sourceRoute.webRoutes(), classpathRoute.webRoutes());
    }

    private static void assertExecutableParity(HandlerRoute sourceRoute, HandlerRoute classpathRoute) {
        ExecutableDescriptor source = sourceRoute.executableMetadata().orElseThrow();
        ExecutableDescriptor classpath = classpathRoute.executableMetadata().orElseThrow();

        assertEquals(source.kind(), classpath.kind());
        assertEquals(source.name(), classpath.name());
        assertEquals(source.returnTypeName(), classpath.returnTypeName());
        assertEquals(source.parameters().stream().map(ParameterDescriptor::typeName).toList(),
                     classpath.parameters().stream().map(ParameterDescriptor::typeName).toList());
        assertEquals(source.parameters().stream()
                             .map(p -> p.annotations().stream().map(AnnotationDescriptor::name).toList()).toList(),
                     classpath.parameters().stream()
                             .map(p -> p.annotations().stream().map(AnnotationDescriptor::name).toList()).toList());
    }

    private static Set<ComponentCapability> withoutDiscoveryMode(Set<ComponentCapability> capabilities) {
        EnumSet<ComponentCapability> result = EnumSet.copyOf(capabilities);
        result.remove(ComponentCapability.SOURCE_COMPONENT);
        result.remove(ComponentCapability.CLASSPATH_COMPONENT);
        return result;
    }

    private static void writeParitySources(Path sourceRoot) throws Exception {
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("package-info.java"), """
                @io.fluxzero.sdk.tracking.handling.LocalHandler(allowExternalMessages = true)
                @io.fluxzero.sdk.tracking.Consumer(name = "parity-package", threads = 2)
                @io.fluxzero.common.serialization.RegisterType(contains = "Parity")
                @io.fluxzero.sdk.web.Path("/parity")
                package io.fluxzero.sdk.registry.parity;
                """);
        Files.writeString(sourceRoot.resolve("ParityCommand.java"), """
                package io.fluxzero.sdk.registry.parity;

                public record ParityCommand(String value) {
                }
                """);
        Files.writeString(sourceRoot.resolve("ParityResult.java"), """
                package io.fluxzero.sdk.registry.parity;

                public record ParityResult(String value) {
                }
                """);
        Files.writeString(sourceRoot.resolve("ParityHandler.java"), """
                package io.fluxzero.sdk.registry.parity;

                import io.fluxzero.common.serialization.RegisterType;
                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.Path;
                import io.fluxzero.sdk.web.PathParam;
                import jakarta.validation.constraints.NotBlank;

                @Consumer(name = "parity-type", threads = 3)
                @RegisterType(rootClass = ParityHandler.class, contains = "ParityHandler")
                @Path("logic")
                public class ParityHandler {
                    @LocalHandler(false)
                    @HandleCommand(allowedClasses = ParityCommand.class, passive = true, skipExpiredRequests = true)
                    public ParityResult handle(@NotBlank ParityCommand command, String ignored) {
                        return new ParityResult(command.value());
                    }

                    @HandleQuery(disabled = true)
                    public String disabled(ParityCommand query) {
                        return query.value();
                    }

                    @HandleGet(value = {"items/{id}", "items"}, autoHead = false, autoOptions = false)
                    public String get(@PathParam String id) {
                        return id;
                    }
                }
                """);
    }
}
