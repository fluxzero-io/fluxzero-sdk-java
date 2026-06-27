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

package io.fluxzero.browser.conformance;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.browser.generator.BrowserApplicationGenerator;
import io.fluxzero.sdk.browser.generator.BrowserConformanceFeature;
import io.fluxzero.sdk.browser.generator.BrowserGenerationResult;
import io.fluxzero.sdk.registry.ComponentCapability;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentKind;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.WebRouteDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserConformanceModuleTest {

    @Test
    void generatorManifestContainsTheFullBrowserConformanceMatrix() {
        BrowserGenerationResult result = new BrowserApplicationGenerator().generate(registryFixture());

        for (BrowserConformanceFeature feature : BrowserApplicationGenerator.defaultConformanceFeatures()) {
            assertTrue(result.manifestJson().contains("\"" + feature.name() + "\""), feature.name());
        }
        assertTrue(result.manifestJson().contains("\"window.fluxzeroConformance.runAll()\""));
        assertTrue(result.sources().getFirst().content().contains("BrowserExecutionCore"));
    }

    @Test
    void curatedSourceAppExercisesOrdinaryFluxzeroAnnotations() throws IOException {
        String source = Files.readString(Path.of(
                "src/test/fluxzero/io/fluxzero/browser/conformance/app/BrowserConformanceApplication.java"));

        List<String> requiredSnippets = List.of(
                "@HandleCommand", "@HandleQuery", "@HandleEvent", "@HandleNotification", "@HandleError",
                "@HandleMetrics", "@HandleResult", "@HandleCustom", "@HandleDocument", "@HandleSchedule",
                "@HandleGet", "@HandlePost", "@HandleSocketHandshake", "@HandleSocketMessage", "@TrackSelf",
                "@Stateful", "@Aggregate", "@Apply", "@RegisterType", "@RequiresUser", "@RequiresAnyRole",
                "@NoUserRequired", "@ProtectData", "@DropProtectedData", "@FilterContent", "@RoutingKey");
        for (String snippet : requiredSnippets) {
            assertTrue(source.contains(snippet), snippet);
        }
    }

    @Test
    void curatedSourceAppCompilesAgainstBrowserSafeApiWithoutJvmSdk() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK compiler is required for the source contract test");
        Path source = Path.of("src/test/fluxzero/io/fluxzero/browser/conformance/app/BrowserConformanceApplication.java");
        Path output = Path.of("target/browser-safe-api-source-compile");
        Files.createDirectories(output);
        DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null,
                                                                                   StandardCharsets.UTF_8)) {
            List<String> options = List.of(
                    "--release", "21",
                    "-proc:none",
                    "-parameters",
                    "-d", output.toString(),
                    "-classpath", browserSafeApiClasspath());
            Boolean compiled = compiler.getTask(
                    null, fileManager, diagnostics, options, null, fileManager.getJavaFileObjects(source)).call();
            assertEquals(Boolean.TRUE, compiled, () -> diagnostics.getDiagnostics().toString());
        }
    }

    @Test
    void nodeConformanceManifestChecksPass() throws Exception {
        Process process = new ProcessBuilder("node", "src/test/js/node-conformance.mjs")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("manifest covers every browser-native app-level feature"), output);
    }

    @Test
    void playwrightAcceptanceContractIsDocumented() throws IOException {
        String source = Files.readString(Path.of("src/test/js/playwright-conformance.mjs"));

        assertTrue(source.contains("window.fluxzeroConformance.runAll()"));
        assertTrue(source.contains("FLUXZERO_BROWSER_CONFORMANCE_URL"));
    }

    private static ComponentRegistry registryFixture() {
        ComponentDescriptor component = new ComponentDescriptor(
                null,
                null,
                ComponentKind.CLASS,
                "io.fluxzero.browser.conformance.app",
                "BrowserConformanceApplication",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Set.of(
                        new HandlerRoute(MessageType.COMMAND, true, true, Set.of("CreateOrder")),
                        new HandlerRoute(MessageType.QUERY, true, false, Set.of("GetOrder")),
                        new HandlerRoute(
                                MessageType.WEBREQUEST,
                                null,
                                null,
                                false,
                                false,
                                false,
                                true,
                                false,
                                Set.of(),
                                Set.of(),
                                List.of(new WebRouteDescriptor(List.of("/orders/{orderId}"), List.of("GET"), true,
                                                               true)))),
                List.of(),
                null,
                Set.of(ComponentCapability.HANDLER, ComponentCapability.WEB_REQUEST_HANDLER));
        return new ComponentRegistry(null, List.of(), List.of(component));
    }

    private static String browserSafeApiClasspath() {
        return String.join(
                java.io.File.pathSeparator,
                Path.of("../common-api/target/classes").toAbsolutePath().normalize().toString(),
                Path.of("../sdk-api/target/classes").toAbsolutePath().normalize().toString());
    }
}
