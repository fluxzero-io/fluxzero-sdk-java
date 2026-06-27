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

import io.fluxzero.sdk.registry.parity.ParityCommand;
import io.fluxzero.sdk.registry.parity.ParityHandler;
import io.fluxzero.sdk.registry.parity.ParityIdentityProvider;
import io.fluxzero.sdk.registry.parity.ParityModel;
import io.fluxzero.sdk.registry.parity.ParityResult;
import io.fluxzero.sdk.registry.parity.ParitySelfQuery;
import io.fluxzero.sdk.registry.parity.realapp.RealAppCommand;
import io.fluxzero.sdk.registry.parity.realapp.RealAppHandlers;
import io.fluxzero.sdk.registry.parity.realapp.RealAppLine;
import io.fluxzero.sdk.registry.parity.realapp.RealAppLocalQuery;
import io.fluxzero.sdk.registry.parity.realapp.RealAppModel;
import io.fluxzero.sdk.registry.parity.realapp.RealAppResult;
import io.fluxzero.sdk.registry.parity.realapp.RealAppSelfCommand;
import io.fluxzero.sdk.registry.parity.realapp.infra.RealAppCache;
import io.fluxzero.sdk.registry.parity.realapp.infra.RealAppIdentityProvider;
import io.fluxzero.sdk.registry.parity.realapp.infra.RealAppPropertySource;
import io.fluxzero.sdk.registry.parity.realapp.infra.RealAppScheduler;
import io.fluxzero.sdk.registry.parity.realapp.web.RealAppEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

class SourceClasspathRegistryParityTest {

    @Test
    void sourceAndClasspathScannersProduceEquivalentFluxzeroSemantics(@TempDir Path sourceRoot) throws Exception {
        writeParitySources(sourceRoot);

        ComponentRegistry source = new SourceComponentScanner().scan(sourceRoot).normalized();
        ComponentRegistry classpath = new ClasspathComponentScanner().scan(
                ParityCommand.class, ParityHandler.class, ParityIdentityProvider.class, ParityModel.class,
                ParityResult.class, ParitySelfQuery.class).normalized();

        ComponentRegistryParityAssertions.assertSemanticParity(classpath, source);
    }

    @Test
    void realAppSourceAndClasspathScannersProduceEquivalentFluxzeroSemantics() {
        Path sourceRoot = realAppSourceRoot();

        ComponentRegistry source = new SourceComponentScanner().scan(sourceRoot).normalized();
        ComponentRegistry classpath = new ClasspathComponentScanner().scan(List.of(
                ParityCommand.class,
                ParityHandler.class,
                ParityIdentityProvider.class,
                ParityModel.class,
                ParityResult.class,
                ParitySelfQuery.class,
                RealAppCache.class,
                RealAppCommand.class,
                RealAppEndpoint.class,
                RealAppHandlers.class,
                RealAppIdentityProvider.class,
                RealAppLine.class,
                RealAppLocalQuery.class,
                RealAppModel.class,
                RealAppPropertySource.class,
                RealAppResult.class,
                RealAppScheduler.class,
                RealAppSelfCommand.class
        )).normalized();

        ComponentRegistryParityAssertions.assertSemanticParity(classpath, source);
    }

    private static Path realAppSourceRoot() {
        String relative = "src/test/java/io/fluxzero/sdk/registry/parity";
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        return Stream.of(workingDirectory.resolve(relative), workingDirectory.resolve("sdk-jvm").resolve(relative))
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not locate real-app parity source root from "
                                                       + workingDirectory));
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
        Files.writeString(sourceRoot.resolve("ParityModel.java"), """
                package io.fluxzero.sdk.registry.parity;

                import io.fluxzero.sdk.modeling.EntityId;
                import io.fluxzero.sdk.publishing.dataprotection.ProtectData;
                import io.fluxzero.sdk.tracking.handling.Association;

                public class ParityModel {
                    @EntityId
                    private String id;

                    @Association
                    @ProtectData
                    private String accountId;
                }
                """);
        Files.writeString(sourceRoot.resolve("ParitySelfQuery.java"), """
                package io.fluxzero.sdk.registry.parity;

                import io.fluxzero.sdk.tracking.handling.HandleQuery;

                public record ParitySelfQuery(String value) {
                    @HandleQuery
                    public String handle() {
                        return value;
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("ParityIdentityProvider.java"), """
                package io.fluxzero.sdk.registry.parity;

                import io.fluxzero.sdk.common.IdentityProvider;

                public class ParityIdentityProvider implements IdentityProvider {
                    @Override
                    public String nextFunctionalId() {
                        return "parity-functional";
                    }

                    @Override
                    public String idForName(String name) {
                        return "parity-" + name;
                    }
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
