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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fluxzero.sdk.browser.generator.BrowserApplicationGenerator;
import io.fluxzero.sdk.browser.generator.BrowserGeneratedSource;
import io.fluxzero.sdk.browser.generator.BrowserGeneratorOptions;
import io.fluxzero.sdk.browser.generator.BrowserGenerationResult;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.SourceComponentScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserConformanceE2ETest {
    private static final String TEAVM_ASSET_ROOT = "https://teavm.org/playground/";
    private static final List<String> TEAVM_ASSETS = List.of(
            "compiler.wasm",
            "compiler.wasm-runtime.js",
            "compile-classlib-teavm.bin",
            "runtime-classlib-teavm.bin");

    @Test
    void generatedFluxzeroBrowserAppRunsInARealBrowser() throws Exception {
        Path webRoot = prepareBrowserHarness();
        try (StaticFileServer server = StaticFileServer.start(webRoot);
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(chromeLaunchOptions())) {
            Page page = browser.newPage();
            page.setDefaultTimeout(120_000);
            page.navigate(server.url());
            page.waitForFunction("() => window.fluxzeroConformance?.ready === true");

            Object result = page.evaluate("() => window.fluxzeroConformance.runAll()");

            Map<?, ?> report = (Map<?, ?>) result;
            assertEquals(0, ((Number) report.get("failed")).intValue(), String.valueOf(report));
            assertTrue(((Number) report.get("passed")).intValue() >= 60, String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"handler.command\""), String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"web.socket\""), String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains(
                    "BrowserConformanceApplication:COMMAND:io.fluxzero.browser.conformance.app.CreateOrder"),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains(
                    "\"dispatchInterceptor.metadata\":\"intercepted:corr-1\""), String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"recursivePublicationGuard.blocked\":1"),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"codec.upcast\":\"upcasted\""), String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"auth.requiresUser\":\"UNAUTHENTICATED:false\""),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"auth.requiresAnyRole\":\"NONE:true\""),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"auth.forbidsUser\":\"UNAUTHORIZED:false\""),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"auth.noUserRequired\":\"NONE:true\""),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"keyValue\":\"cached\""), String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"scheduler.runDue\":1"), String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"web.status\":200"), String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"web.body\":\"order-1:details:corr-1:s1:browser:body-order\""),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("message:session-1:hello"), String.valueOf(report));
            assertTrue(((Number) report.get("wasmBytes")).intValue() > 0, String.valueOf(report));
            assertTrue(((Number) report.get("compileMillis")).intValue() >= 0, String.valueOf(report));
        }
    }

    private static BrowserType.LaunchOptions chromeLaunchOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        Path macChrome = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        if (Files.isExecutable(macChrome)) {
            options.setExecutablePath(macChrome);
        }
        return options;
    }

    private static Path prepareBrowserHarness() throws IOException, InterruptedException {
        Path webRoot = Path.of("target/browser-conformance-e2e");
        Path assets = webRoot.resolve("assets");
        Files.createDirectories(assets);
        downloadTeaVmAssets(assets);
        copyReactorJar("../common-api/target/common-api-0-SNAPSHOT.jar", assets.resolve("common-api.jar"));
        copyReactorJar("../sdk-api/target/sdk-api-0-SNAPSHOT.jar", assets.resolve("sdk-api.jar"));
        copyReactorJar("../sdk-browser/target/sdk-browser-0-SNAPSHOT.jar", assets.resolve("sdk-browser.jar"));
        Files.writeString(webRoot.resolve("index.html"), indexHtml(), StandardCharsets.UTF_8);
        Files.writeString(webRoot.resolve("app.js"), appJs(), StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("sources.json"), sourceBundleJson(), StandardCharsets.UTF_8);
        return webRoot;
    }

    private static void downloadTeaVmAssets(Path assets) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        for (String asset : TEAVM_ASSETS) {
            Path target = assets.resolve(asset);
            if (Files.exists(target)) {
                continue;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(TEAVM_ASSET_ROOT + asset)).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IOException("Failed to download " + asset + ": HTTP " + response.statusCode());
            }
            Files.write(target, response.body());
        }
    }

    private static void copyReactorJar(String source, Path target) throws IOException {
        Path sourcePath = Path.of(source);
        if (!Files.exists(sourcePath)) {
            throw new IOException("Expected reactor artifact at " + sourcePath.toAbsolutePath());
        }
        Files.copy(sourcePath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sourceBundleJson() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        addSource(sources, "../common-api/src/main/java/io/fluxzero/common/MessageType.java");
        addSource(sources, "../sdk-api/src/main/java/io/fluxzero/sdk/publishing/CommandGateway.java");
        addSource(sources, "../sdk-api/src/main/java/io/fluxzero/sdk/publishing/QueryGateway.java");
        addSource(sources, "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationSubject.java");
        addSource(sources, "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationRule.java");
        addSource(sources, "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationFailure.java");
        addSource(sources, "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationDecision.java");
        addSource(sources, "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationPolicy.java");
        addSources(sources, "../sdk-browser/src/main/java");
        BrowserGenerationResult generated = new BrowserApplicationGenerator().generate(
                sourceRegistry(),
                new BrowserGeneratorOptions("io.fluxzero.browser.generated",
                                            "GeneratedFluxzeroBrowserApplication", true));
        for (BrowserGeneratedSource source : generated.sources()) {
            if (source.path().endsWith(".java")) {
                sources.put(source.path(), source.content());
            }
        }
        sources.put("io/fluxzero/browser/generated/Main.java", """
                package io.fluxzero.browser.generated;

                public final class Main {
                    private Main() {
                    }

                    public static void main(String[] args) {
                        System.out.println(new GeneratedFluxzeroBrowserApplication().runAll());
                    }
                }
                """);

        StringBuilder json = new StringBuilder("{\n");
        int index = 0;
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("  \"").append(escapeJson(entry.getKey())).append("\": \"")
                    .append(escapeJson(entry.getValue())).append('"');
        }
        json.append("\n}\n");
        return json.toString();
    }

    private static void addSources(Map<String, String> sources, String sourceRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of(sourceRoot))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                addSource(sources, path.toString());
            }
        }
    }

    private static void addSource(Map<String, String> sources, String source) throws IOException {
        Path path = Path.of(source);
        int javaIndex = -1;
        for (int i = 0; i < path.getNameCount(); i++) {
            if ("java".equals(path.getName(i).toString())) {
                javaIndex = i;
            }
        }
        if (javaIndex < 0) {
            throw new IOException("Cannot derive Java source path for " + source);
        }
        sources.put(path.subpath(javaIndex + 1, path.getNameCount()).toString().replace('\\', '/'),
                    Files.readString(path));
    }

    private static ComponentRegistry sourceRegistry() {
        return new SourceComponentScanner().scan(Path.of("src/test/fluxzero")).normalized();
    }

    private static String indexHtml() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <title>Fluxzero Browser Conformance</title>
                </head>
                <body>
                    <main>
                        <h1>Fluxzero Browser Conformance</h1>
                        <pre id="status">Loading</pre>
                    </main>
                    <script type="module" src="./app.js"></script>
                </body>
                </html>
                """;
    }

    private static String appJs() {
        return """
                import { load } from "./assets/compiler.wasm-runtime.js";

                const status = document.querySelector("#status");
                let compilerPromise;
                let lastReport = null;

                window.fluxzeroConformance = {
                    ready: true,
                    runAll,
                    run: async (name) => {
                        const report = await runAll();
                        return {
                            ...report,
                            requested: name
                        };
                    },
                    report: () => lastReport
                };
                status.textContent = "Ready";

                async function runAll() {
                    const compiler = await compilerInstance();
                    const diagnostics = [];
                    const registration = compiler.onDiagnostic((diagnostic) => diagnostics.push(readDiagnostic(diagnostic)));
                    try {
                        compiler.clearSourceFiles();
                        compiler.clearOutputFiles();
                        const sources = await fetchJson("./assets/sources.json");
                        for (const [path, source] of Object.entries(sources)) {
                            compiler.addSourceFile(path.substring(path.lastIndexOf("/") + 1), source);
                        }
                        const compileStarted = performance.now();
                        const compiled = compiler.compile();
                        const compileMillis = Math.round(performance.now() - compileStarted);
                        if (!compiled) {
                            throw new Error(`Browser javac failed:\\n${diagnostics.join("\\n")}`);
                        }
                        const wasmStarted = performance.now();
                        const generated = compiler.generateWebAssembly({
                            outputName: "conformance",
                            mainClass: "io.fluxzero.browser.generated.Main"
                        });
                        const generateMillis = Math.round(performance.now() - wasmStarted);
                        if (!generated) {
                            throw new Error(`TeaVM generation failed:\\n${diagnostics.join("\\n")}`);
                        }
                        const wasmBytes = compiler.getWebAssemblyOutputFile("conformance.wasm");
                        const logs = await captureConsole(async () => {
                            const app = await load(wasmBytes);
                            app.exports.main([]);
                        });
                        const raw = logs.findLast((line) => line.trim().startsWith("{")) ?? "{}";
                        const report = JSON.parse(raw);
                        lastReport = {
                            ...report,
                            raw,
                            diagnostics,
                            compileMillis,
                            generateMillis,
                            wasmBytes: wasmBytes.byteLength
                        };
                        status.textContent = JSON.stringify(lastReport, null, 2);
                        return lastReport;
                    } finally {
                        registration?.destroy?.();
                    }
                }

                async function compilerInstance() {
                    if (!compilerPromise) {
                        compilerPromise = initializeCompiler();
                    }
                    return compilerPromise;
                }

                async function initializeCompiler() {
                    status.textContent = "Loading TeaVM compiler";
                    const teavm = await load("./assets/compiler.wasm");
                    const compiler = teavm.exports.createCompiler();
                    const [sdk, runtimeClasslib, commonApi, sdkApi, sdkBrowser] = await Promise.all([
                        fetchBytes("./assets/compile-classlib-teavm.bin"),
                        fetchBytes("./assets/runtime-classlib-teavm.bin"),
                        fetchBytes("./assets/common-api.jar"),
                        fetchBytes("./assets/sdk-api.jar"),
                        fetchBytes("./assets/sdk-browser.jar")
                    ]);
                    compiler.setSdk(sdk);
                    compiler.setTeaVMClasslib(runtimeClasslib);
                    compiler.addJarFile(commonApi);
                    compiler.addJarFile(sdkApi);
                    compiler.addJarFile(sdkBrowser);
                    status.textContent = "Compiler loaded";
                    return compiler;
                }

                async function fetchBytes(url) {
                    const response = await fetch(url);
                    if (!response.ok) {
                        throw new Error(`Failed to fetch ${url}: ${response.status} ${response.statusText}`);
                    }
                    return new Int8Array(await response.arrayBuffer());
                }

                async function fetchJson(url) {
                    const response = await fetch(url);
                    if (!response.ok) {
                        throw new Error(`Failed to fetch ${url}: ${response.status} ${response.statusText}`);
                    }
                    return response.json();
                }

                async function captureConsole(action) {
                    const originalLog = console.log;
                    const originalError = console.error;
                    const messages = [];
                    console.log = (...args) => {
                        messages.push(args.join(" "));
                        originalLog(...args);
                    };
                    console.error = (...args) => {
                        messages.push(args.join(" "));
                        originalError(...args);
                    };
                    try {
                        await action();
                        return messages;
                    } finally {
                        console.log = originalLog;
                        console.error = originalError;
                    }
                }

                function readDiagnostic(diagnostic) {
                    const type = readDiagnosticProperty(diagnostic, "type", "diagnostic");
                    const severity = readDiagnosticProperty(diagnostic, "severity", "other");
                    const fileName = readDiagnosticProperty(diagnostic, "fileName", "");
                    const lineNumber = readDiagnosticProperty(diagnostic, "lineNumber", "?");
                    const message = readDiagnosticProperty(diagnostic, "message", "(message unavailable)");
                    return `[${type}/${severity}] ${fileName}:${lineNumber} ${message}`;
                }

                function readDiagnosticProperty(diagnostic, property, fallback) {
                    try {
                        return diagnostic[property] ?? fallback;
                    } catch (error) {
                        return fallback;
                    }
                }
                """;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static final class StaticFileServer implements AutoCloseable {
        private final HttpServer server;

        private StaticFileServer(HttpServer server) {
            this.server = server;
        }

        static StaticFileServer start(Path root) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> serve(root, exchange));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return new StaticFileServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void serve(Path root, HttpExchange exchange) throws IOException {
            Path requested = root.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
            if (!requested.startsWith(root) || Files.isDirectory(requested)) {
                requested = root.resolve("index.html");
            }
            if (!Files.exists(requested)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(requested);
            exchange.getResponseHeaders().add("Content-Type", contentType(requested));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private static String contentType(Path path) {
            String fileName = path.getFileName().toString();
            if (fileName.endsWith(".html")) {
                return "text/html; charset=utf-8";
            }
            if (fileName.endsWith(".js")) {
                return "text/javascript; charset=utf-8";
            }
            if (fileName.endsWith(".json")) {
                return "application/json; charset=utf-8";
            }
            if (fileName.endsWith(".wasm")) {
                return "application/wasm";
            }
            return "application/octet-stream";
        }
    }
}
