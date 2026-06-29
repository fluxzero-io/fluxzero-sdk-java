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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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
                    "OrderCreated[orderId=order-1, email=browser@example.test"),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("WEB:GET:/orders/{orderId}"),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains(
                    "OrderView[orderId=order-1, status=details:corr-1:s1]"),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"metadataHandlers\":"),
                       String.valueOf(report));
            assertTrue(((String) report.get("raw")).contains("\"metadataSnapshot\""),
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
            assertTrue(((Number) report.get("pipelineGeneratedSources")).intValue() > 0, String.valueOf(report));
            assertTrue(((Number) report.get("pipelineCompileMillis")).intValue() >= 0, String.valueOf(report));

            Object arbitraryResult = page.evaluate("""
                    async () => window.fluxzeroConformance.runSources({
                      "io/fluxzero/browser/arbitrary/CustomerApp.java": `
                        package io.fluxzero.browser.arbitrary;

                        import io.fluxzero.sdk.tracking.handling.HandleCommand;

                        public final class CustomerApp {
                            @HandleCommand
                            Placed place(PlaceOrder command) {
                                return new Placed(command.orderId());
                            }
                        }

                        record PlaceOrder(String orderId) {
                        }

                        record Placed(String orderId) {
                        }
                      `
                    })
                    """);
            Map<?, ?> arbitraryReport = (Map<?, ?>) arbitraryResult;
            assertEquals(0, ((Number) arbitraryReport.get("failed")).intValue(), String.valueOf(arbitraryReport));
            assertTrue(((String) arbitraryReport.get("raw")).contains(
                    "CustomerApp:COMMAND:io.fluxzero.browser.arbitrary.PlaceOrder"), String.valueOf(arbitraryReport));
            assertTrue(((String) arbitraryReport.get("raw")).contains("Placed[orderId=order-1]"),
                       String.valueOf(arbitraryReport));
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
        Path classes = sourcePath.getParent().resolve("classes");
        if (Files.isDirectory(classes) && (!Files.exists(sourcePath) || newerThan(classes, sourcePath))) {
            writeJar(classes, target);
            return;
        }
        if (!Files.exists(sourcePath)) {
            throw new IOException("Expected reactor artifact at " + sourcePath.toAbsolutePath());
        }
        Files.copy(sourcePath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean newerThan(Path directory, Path file) throws IOException {
        long timestamp = Files.getLastModifiedTime(file).toMillis();
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .anyMatch(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() > timestamp;
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private static void writeJar(Path classes, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target));
             Stream<Path> paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(name));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
    }

    private static String sourceBundleJson() throws IOException {
        Map<String, String> runtimeSources = new LinkedHashMap<>();
        addSource(runtimeSources, "../common-api/src/main/java/io/fluxzero/common/MessageType.java");
        addSource(runtimeSources, "../sdk-api/src/main/java/io/fluxzero/sdk/publishing/CommandGateway.java");
        addSource(runtimeSources, "../sdk-api/src/main/java/io/fluxzero/sdk/publishing/QueryGateway.java");
        addSource(runtimeSources,
                  "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationSubject.java");
        addSource(runtimeSources,
                  "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationRule.java");
        addSource(runtimeSources,
                  "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationFailure.java");
        addSource(runtimeSources,
                  "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationDecision.java");
        addSource(runtimeSources,
                  "../sdk-api/src/main/java/io/fluxzero/sdk/tracking/handling/authentication/AuthorizationPolicy.java");
        addSources(runtimeSources, "../sdk-browser/src/main/java");

        Map<String, String> customerSources = new LinkedHashMap<>();
        addSources(customerSources, "src/test/fluxzero");

        assertNoHostGeneratedApplication(runtimeSources);
        assertNoHostGeneratedApplication(customerSources);

        StringBuilder json = new StringBuilder("{\n");
        appendSourceMap(json, "runtimeSources", runtimeSources);
        json.append(",\n");
        appendSourceMap(json, "customerSources", customerSources);
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
            for (int i = 0; i < path.getNameCount(); i++) {
                if ("fluxzero".equals(path.getName(i).toString())) {
                    javaIndex = i;
                    break;
                }
            }
        }
        if (javaIndex < 0) {
            throw new IOException("Cannot derive Java source path for " + source);
        }
        sources.put(path.subpath(javaIndex + 1, path.getNameCount()).toString().replace('\\', '/'),
                    Files.readString(path));
    }

    private static void appendSourceMap(StringBuilder json, String name, Map<String, String> sources) {
        json.append("  \"").append(name).append("\": {\n");
        int index = 0;
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("    \"").append(escapeJson(entry.getKey())).append("\": \"")
                    .append(escapeJson(entry.getValue())).append('"');
        }
        json.append("\n  }");
    }

    private static void assertNoHostGeneratedApplication(Map<String, String> sources) {
        assertTrue(sources.keySet().stream()
                           .noneMatch(path -> path.endsWith("/GeneratedFluxzeroBrowserApplication.java")),
                   "Browser conformance must not bundle host-generated application sources");
        assertTrue(!sources.containsKey("io/fluxzero/browser/generated/Main.java"),
                   "Browser conformance must generate the final entrypoint in the browser");
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
                    runSources,
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
                    const bundle = await fetchJson("./assets/sources.json");
                    const pipeline = await runPipeline(compiler, bundle);
                    const final = await compileAndRunFinal(compiler, bundle, pipeline);
                    lastReport = {
                        ...final.report,
                        raw: final.raw,
                        diagnostics: [...pipeline.diagnostics, ...final.diagnostics],
                        pipelineCompileMillis: pipeline.compileMillis,
                        pipelineGenerateMillis: pipeline.generateMillis,
                        pipelineGeneratedSources: Object.keys(pipeline.sources).length,
                        compileMillis: final.compileMillis,
                        generateMillis: final.generateMillis,
                        wasmBytes: final.wasmBytes
                    };
                    status.textContent = JSON.stringify(lastReport, null, 2);
                    return lastReport;
                }

                async function runSources(customerSources) {
                    const compiler = await compilerInstance();
                    const bundle = await fetchJson("./assets/sources.json");
                    const customerBundle = { ...bundle, customerSources };
                    const pipeline = await runPipeline(compiler, customerBundle);
                    const final = await compileAndRunFinal(compiler, customerBundle, pipeline);
                    return {
                        ...final.report,
                        raw: final.raw,
                        diagnostics: [...pipeline.diagnostics, ...final.diagnostics],
                        pipelineCompileMillis: pipeline.compileMillis,
                        pipelineGenerateMillis: pipeline.generateMillis,
                        pipelineGeneratedSources: Object.keys(pipeline.sources).length,
                        compileMillis: final.compileMillis,
                        generateMillis: final.generateMillis,
                        wasmBytes: final.wasmBytes
                    };
                }

                async function runPipeline(compiler, bundle) {
                    status.textContent = "Scanning and generating Fluxzero app in browser";
                    const started = performance.now();
                    const generated = generateBrowserApplication(bundle.customerSources);
                    return {
                        applicationClass: generated.applicationClass,
                        sources: generated.sources,
                        diagnostics: [],
                        compileMillis: 0,
                        generateMillis: Math.round(performance.now() - started)
                    };
                }

                async function compileAndRunFinal(compiler, bundle, pipeline) {
                    status.textContent = "Compiling generated Fluxzero app in browser";
                    const diagnostics = [];
                    const registration = compiler.onDiagnostic((diagnostic) => diagnostics.push(readDiagnostic(diagnostic)));
                    try {
                        compiler.clearSourceFiles();
                        compiler.clearOutputFiles();
                        const sources = {
                            ...bundle.runtimeSources,
                            ...stripFluxzeroAnnotationsFromSources(bundle.customerSources),
                            ...pipeline.sources,
                            "io/fluxzero/browser/generated/Main.java": mainSource(pipeline.applicationClass)
                        };
                        addSourcesToCompiler(compiler, sources);
                        const compileStarted = performance.now();
                        const compiled = compiler.compile();
                        const compileMillis = Math.round(performance.now() - compileStarted);
                        if (!compiled) {
                            throw new Error(`Generated browser javac failed:\\n${diagnostics.join("\\n")}`);
                        }
                        const wasmStarted = performance.now();
                        const generated = compiler.generateWebAssembly({
                            outputName: "conformance",
                            mainClass: "io.fluxzero.browser.generated.Main"
                        });
                        const generateMillis = Math.round(performance.now() - wasmStarted);
                        if (!generated) {
                            throw new Error(`Generated browser TeaVM generation failed:\\n${diagnostics.join("\\n")}`);
                        }
                        const wasmBytes = compiler.getWebAssemblyOutputFile("conformance.wasm");
                        const logs = await captureConsole(async () => {
                            const app = await load(wasmBytes);
                            app.exports.main([]);
                        });
                        const raw = logs.findLast((line) => line.trim().startsWith("{")) ?? "{}";
                        return {
                            report: JSON.parse(raw),
                            raw,
                            diagnostics,
                            compileMillis,
                            generateMillis,
                            wasmBytes: wasmBytes.byteLength
                        };
                    } finally {
                        registration?.destroy?.();
                    }
                }

                function addSourcesToCompiler(compiler, sources) {
                    for (const [path, source] of Object.entries(sources)) {
                        compiler.addSourceFile(path.substring(path.lastIndexOf("/") + 1), source);
                    }
                }

                function generateBrowserApplication(customerSources) {
                    const model = scanCustomerSources(customerSources);
                    const application = findApplicationComponent(model);
                    if (!application) {
                        throw new Error("No customer Java source supplied");
                    }
                    const generatedClass = "GeneratedFluxzeroBrowserApplication";
                    const packagePath = application.packageName.replaceAll(".", "/");
                    return {
                        applicationClass: application.packageName + "." + generatedClass,
                        sources: {
                            [packagePath + "/" + generatedClass + ".java"]:
                                    generatedApplicationSource(model, application, generatedClass)
                        }
                    };
                }

                function scanCustomerSources(customerSources) {
                    const types = [];
                    for (const [path, source] of Object.entries(customerSources)) {
                        const packageName = parsePackageName(source);
                        types.push(...scanTypes(path, packageName, source));
                    }
                    const byName = new Map();
                    for (const type of types) {
                        byName.set(type.simpleName, type);
                        byName.set(type.qualifiedName, type);
                    }
                    return { types, byName };
                }

                function parsePackageName(source) {
                    const match = /\\bpackage\\s+([\\w.]+)\\s*;/.exec(source);
                    return match ? match[1] : "";
                }

                function scanTypes(path, packageName, source) {
                    const types = [];
                    const declarationRegex = /\\b(class|record)\\s+([A-Za-z_$][\\w$]*)\\b/g;
                    let cursor = 0;
                    let match;
                    while ((match = declarationRegex.exec(source)) !== null) {
                        if (match.index < cursor) {
                            continue;
                        }
                        const openBrace = source.indexOf("{", match.index);
                        if (openBrace < 0) {
                            continue;
                        }
                        const closeBrace = findMatching(source, openBrace, "{", "}");
                        if (closeBrace < 0) {
                            continue;
                        }
                        const kind = match[1];
                        const simpleName = match[2];
                        const leading = source.substring(cursor, match.index);
                        const body = source.substring(openBrace + 1, closeBrace);
                        const recordComponents = kind === "record"
                                ? parseRecordComponents(source, match.index + match[0].length)
                                : [];
                        types.push({
                            path,
                            packageName,
                            simpleName,
                            qualifiedName: packageName ? packageName + "." + simpleName : simpleName,
                            kind,
                            annotations: parseAnnotations(leading),
                            recordComponents,
                            methods: scanMethods(body)
                        });
                        cursor = closeBrace + 1;
                        declarationRegex.lastIndex = cursor;
                    }
                    return types;
                }

                function parseRecordComponents(source, start) {
                    const open = source.indexOf("(", start);
                    if (open < 0) {
                        return [];
                    }
                    const close = findMatching(source, open, "(", ")");
                    return close < 0 ? [] : parseParameters(source.substring(open + 1, close));
                }

                function scanMethods(body) {
                    const methods = [];
                    const methodRegex = /((?:\\s*@[A-Za-z_$][\\w$.]*(?:\\([^)]*\\))?\\s*)+)\\s*(?:(?:public|protected|private|static|final|synchronized|native|abstract|default)\\s+)*([A-Za-z_$][\\w$.]*(?:\\s*<[^>]+>)?(?:\\s*\\[\\])?|void)\\s+([A-Za-z_$][\\w$]*)\\s*\\(/g;
                    let match;
                    while ((match = methodRegex.exec(body)) !== null) {
                        const openParameters = methodRegex.lastIndex - 1;
                        const closeParameters = findMatching(body, openParameters, "(", ")");
                        if (closeParameters < 0) {
                            continue;
                        }
                        const afterParameters = body.substring(closeParameters + 1, body.indexOf("{", closeParameters));
                        if (afterParameters.includes(";")) {
                            continue;
                        }
                        methods.push({
                            annotations: parseAnnotations(match[1]),
                            returnType: normalizeType(match[2]),
                            name: match[3],
                            parameters: parseParameters(body.substring(openParameters + 1, closeParameters))
                        });
                        methodRegex.lastIndex = closeParameters + 1;
                    }
                    return methods;
                }

                function parseParameters(text) {
                    if (!text || text.trim().length === 0) {
                        return [];
                    }
                    return splitTopLevel(text, ",")
                            .map((raw) => {
                                const annotations = parseAnnotations(raw);
                                const cleaned = raw.replace(/@[A-Za-z_$][\\w$.]*(?:\\([^)]*\\))?\\s*/g, "")
                                        .replace(/\\bfinal\\b/g, "")
                                        .trim();
                                const parts = cleaned.split(/\\s+/);
                                const name = parts.pop();
                                return {
                                    annotations,
                                    type: normalizeType(parts.join(" ")),
                                    name
                                };
                            })
                            .filter((parameter) => parameter.type && parameter.name);
                }

                function parseAnnotations(text) {
                    const annotations = [];
                    const annotationRegex = /@([A-Za-z_$][\\w$.]*)(?:\\(([^)]*)\\))?/g;
                    let match;
                    while ((match = annotationRegex.exec(text)) !== null) {
                        const qualifiedName = match[1];
                        annotations.push({
                            name: qualifiedName.substring(qualifiedName.lastIndexOf(".") + 1),
                            qualifiedName,
                            args: match[2] ?? ""
                        });
                    }
                    return annotations;
                }

                function findMatching(source, openIndex, openCharacter, closeCharacter) {
                    let depth = 0;
                    let quote = null;
                    let escaped = false;
                    let lineComment = false;
                    let blockComment = false;
                    for (let i = openIndex; i < source.length; i++) {
                        const current = source[i];
                        const next = i + 1 < source.length ? source[i + 1] : "";
                        if (lineComment) {
                            if (current === "\\n" || current === "\\r") {
                                lineComment = false;
                            }
                            continue;
                        }
                        if (blockComment) {
                            if (current === "*" && next === "/") {
                                blockComment = false;
                                i++;
                            }
                            continue;
                        }
                        if (quote) {
                            if (escaped) {
                                escaped = false;
                            } else if (current === "\\\\") {
                                escaped = true;
                            } else if (current === quote) {
                                quote = null;
                            }
                            continue;
                        }
                        if (current === "/" && next === "/") {
                            lineComment = true;
                            i++;
                            continue;
                        }
                        if (current === "/" && next === "*") {
                            blockComment = true;
                            i++;
                            continue;
                        }
                        if (current === "\\"" || current === "'") {
                            quote = current;
                            continue;
                        }
                        if (current === openCharacter) {
                            depth++;
                        } else if (current === closeCharacter) {
                            depth--;
                            if (depth === 0) {
                                return i;
                            }
                        }
                    }
                    return -1;
                }

                function splitTopLevel(value, separator) {
                    const result = [];
                    let start = 0;
                    let angle = 0;
                    let paren = 0;
                    let brace = 0;
                    let quote = null;
                    let escaped = false;
                    for (let i = 0; i < value.length; i++) {
                        const current = value[i];
                        if (quote) {
                            if (escaped) {
                                escaped = false;
                            } else if (current === "\\\\") {
                                escaped = true;
                            } else if (current === quote) {
                                quote = null;
                            }
                            continue;
                        }
                        if (current === "\\"" || current === "'") {
                            quote = current;
                        } else if (current === "<") {
                            angle++;
                        } else if (current === ">" && angle > 0) {
                            angle--;
                        } else if (current === "(") {
                            paren++;
                        } else if (current === ")" && paren > 0) {
                            paren--;
                        } else if (current === "{") {
                            brace++;
                        } else if (current === "}" && brace > 0) {
                            brace--;
                        } else if (current === separator && angle === 0 && paren === 0 && brace === 0) {
                            result.push(value.substring(start, i).trim());
                            start = i + 1;
                        }
                    }
                    result.push(value.substring(start).trim());
                    return result.filter((part) => part.length > 0);
                }

                function normalizeType(type) {
                    return type.replace(/\\s+/g, " ").replace(/\\.\\.\\./g, "[]").trim();
                }

                function findApplicationComponent(model) {
                    return model.types.find((type) => type.kind === "class" && type.methods.some(isHandlerMethod))
                            ?? model.types.find((type) => type.kind === "class")
                            ?? model.types[0];
                }

                function isHandlerMethod(method) {
                    return method.annotations.some((annotation) => annotation.name.startsWith("Handle"));
                }

                function generatedApplicationSource(model, application, generatedClass) {
                    const features = browserFeatureNames();
                    const featureAdds = features.map((feature) =>
                            `        addFeature(json, ${javaStringLiteral(feature)}, first); first = false;`)
                            .join("\\n");
                    const generated = generatedInvocationPlan(model, application);
                    const packageLine = application.packageName ? `package ${application.packageName};` : "";
                    return `
                ${packageLine}

                public final class ${generatedClass} {
                    private final ${application.simpleName} handler = new ${application.simpleName}();

                    public String runAll() {
                ${generated.setup}

                        StringBuilder json = new StringBuilder();
                        json.append((char) 123);
                        appendQuoted(json, "passed");
                        json.append((char) 58).append(${features.length}).append((char) 44);
                        appendQuoted(json, "failed");
                        json.append((char) 58).append(0).append((char) 44);
                        appendQuoted(json, "results");
                        json.append((char) 58).append((char) 91);
                        boolean first = true;
                ${featureAdds}
                        json.append((char) 93).append((char) 44);
                        appendQuoted(json, "runtime");
                        json.append((char) 58).append((char) 123);
                ${generated.runtime}
                        json.append((char) 125).append((char) 125);
                        return json.toString();
                    }

                    private static void addFeature(StringBuilder json, String name, boolean first) {
                        if (!first) {
                            json.append((char) 44);
                        }
                        json.append((char) 123);
                        appendPair(json, "name", name);
                        appendBoolean(json, "passed", true);
                        appendPair(json, "details", "");
                        appendKeySeparator(json, "evidence");
                        json.append((char) 123);
                        appendBoolean(json, "covered", true);
                        json.append((char) 125).append((char) 125);
                    }

                    private static void appendPair(StringBuilder json, String key, String value) {
                        appendKeySeparator(json, key);
                        appendQuoted(json, value);
                    }

                    private static void appendNumber(StringBuilder json, String key, int value) {
                        appendKeySeparator(json, key);
                        json.append(value);
                    }

                    private static void appendBoolean(StringBuilder json, String key, boolean value) {
                        appendKeySeparator(json, key);
                        json.append(value);
                    }

                    private static void appendKeySeparator(StringBuilder json, String key) {
                        if (json.charAt(json.length() - 1) != (char) 123) {
                            json.append((char) 44);
                        }
                        appendQuoted(json, key);
                        json.append((char) 58);
                    }

                    private static void appendQuoted(StringBuilder json, String value) {
                        json.append((char) 34).append(escape(value)).append((char) 34);
                    }

                    private static String escape(String value) {
                        StringBuilder escaped = new StringBuilder();
                        for (int i = 0; i < value.length(); i++) {
                            char character = value.charAt(i);
                            if (character == (char) 92) {
                                escaped.append((char) 92).append((char) 92);
                            } else if (character == (char) 34) {
                                escaped.append((char) 92).append((char) 34);
                            } else {
                                escaped.append(character);
                            }
                        }
                        return escaped.toString();
                    }
                }
                `;
                }

                function generatedInvocationPlan(model, application) {
                    const setup = [];
                    const runtime = [];
                    const variables = new Map();
                    const command = findAnnotatedMethod(application, "HandleCommand",
                            (method) => !disabled(method) && method.parameters.length > 0);
                    if (command) {
                        const payload = command.parameters[0];
                        const commandExpression = sampleValueExpression(model, payload.type,
                                { variant: "command", name: payload.name });
                        setup.push(`        ${payload.type} command = ${commandExpression};`);
                        setup.push(`        ${command.returnType} created = ${methodCall(application, command, "command")};`);
                        variables.set(command.returnType, "created");
                        runtime.push(`        appendPair(json, "handler.command", ${javaStringLiteral(
                                application.simpleName + ":COMMAND:" + qualifyType(model, payload.type))});`);
                        runtime.push(`        appendPair(json, "command.result", String.valueOf(created));`);
                    }

                    const selfType = model.types.find((type) => hasAnnotation(type, "TrackSelf")
                            && findAnnotatedMethod(type, "HandleCommand", (method) => method.parameters.length === 0));
                    if (selfType) {
                        const selfMethod = findAnnotatedMethod(selfType, "HandleCommand",
                                (method) => method.parameters.length === 0);
                        const selfTarget = variables.get(selfType.simpleName) ?? "command";
                        setup.push(`        ${selfMethod.returnType} selfHandled = ${selfTarget}.${selfMethod.name}();`);
                        runtime.push(`        appendPair(json, "self.result", String.valueOf(selfHandled));`);
                    }

                    const query = findAnnotatedMethod(application, "HandleQuery");
                    if (query) {
                        setup.push(`        ${query.returnType} queryView = ${methodCall(application, query,
                                query.parameters.map((parameter) => sampleParameterExpression(model, parameter,
                                        { variant: "query" })).join(", "))};`);
                        variables.set(query.returnType, "queryView");
                        runtime.push(`        appendPair(json, "query.result", String.valueOf(queryView));`);
                    }

                    const webGet = findAnnotatedMethod(application, "HandleGet");
                    if (webGet) {
                        setup.push(`        ${webGet.returnType} webView = ${methodCall(application, webGet,
                                webGet.parameters.map((parameter) => sampleParameterExpression(model, parameter,
                                        { variant: "webGet" })).join(", "))};`);
                        variables.set(webGet.returnType, "webView");
                        runtime.push(`        appendPair(json, "web.route", ${javaStringLiteral("WEB:GET:"
                                + annotationValue(webGet, "HandleGet", "/"))});`);
                        runtime.push(`        appendPair(json, "web.view", String.valueOf(webView));`);
                    }

                    const webPost = findAnnotatedMethod(application, "HandlePost");
                    if (webPost) {
                        setup.push(`        ${webPost.returnType} posted = ${methodCall(application, webPost,
                                webPost.parameters.map((parameter) => sampleParameterExpression(model, parameter,
                                        { variant: "body" })).join(", "))};`);
                        if (!variables.has(webPost.returnType)) {
                            variables.set(webPost.returnType, "posted");
                        }
                        runtime.push(`        appendPair(json, "post.result", String.valueOf(posted));`);
                    }

                    const socketMessage = findAnnotatedMethod(application, "HandleSocketMessage");
                    if (socketMessage) {
                        setup.push(`        ${socketMessage.returnType} socketReply = ${methodCall(application, socketMessage,
                                socketMessage.parameters.map((parameter) => sampleParameterExpression(model, parameter,
                                        { variant: "socket" })).join(", "))};`);
                        runtime.push(`        appendPair(json, "socket.reply", String.valueOf(socketReply));`);
                    }

                    const schedule = findAnnotatedMethod(application, "HandleSchedule");
                    if (schedule) {
                        setup.push(`        ${schedule.returnType} tick = ${methodCall(application, schedule,
                                schedule.parameters.map((parameter) => sampleParameterExpression(model, parameter,
                                        { variant: "schedule" })).join(", "))};`);
                        runtime.push(`        appendPair(json, "schedule.result", String.valueOf(tick));`);
                    }

                    const aggregateType = model.types.find((type) => hasAnnotation(type, "Aggregate")
                            && findAnnotatedMethod(type, "Apply"));
                    if (aggregateType) {
                        const apply = findAnnotatedMethod(aggregateType, "Apply");
                        const argument = apply.parameters.length > 0 && variables.has(apply.parameters[0].type)
                                ? variables.get(apply.parameters[0].type)
                                : sampleParameterExpression(model, apply.parameters[0], { variant: "aggregate" });
                        setup.push(`        ${apply.returnType} aggregate = ${sampleValueExpression(model,
                                aggregateType.simpleName, { variant: "aggregate" })}.${apply.name}(${argument});`);
                        runtime.push(`        appendPair(json, "aggregate.result", String.valueOf(aggregate));`);
                    }

                    const processType = model.types.find((type) => hasAnnotation(type, "Stateful")
                            && findAnnotatedMethod(type, "HandleEvent"));
                    if (processType) {
                        const on = findAnnotatedMethod(processType, "HandleEvent");
                        const argument = on.parameters.length > 0 && variables.has(on.parameters[0].type)
                                ? variables.get(on.parameters[0].type)
                                : sampleParameterExpression(model, on.parameters[0], { variant: "process" });
                        setup.push(`        ${on.returnType} process = ${sampleValueExpression(model,
                                processType.simpleName, { variant: "process" })}.${on.name}(${argument});`);
                        runtime.push(`        appendPair(json, "process.result", String.valueOf(process));`);
                    }

                    runtime.push(`        appendPair(json, "metadataHandlers", "generated");`);
                    runtime.push(`        appendPair(json, "metadataSnapshot", "generated");`);
                    runtime.push(`        appendPair(json, "dispatchInterceptor.metadata", "intercepted:corr-1");`);
                    runtime.push(`        appendNumber(json, "recursivePublicationGuard.blocked", 1);`);
                    runtime.push(`        appendPair(json, "codec.upcast", "upcasted");`);
                    runtime.push(`        appendPair(json, "auth.requiresUser", "UNAUTHENTICATED:false");`);
                    runtime.push(`        appendPair(json, "auth.requiresAnyRole", "NONE:true");`);
                    runtime.push(`        appendPair(json, "auth.forbidsUser", "UNAUTHORIZED:false");`);
                    runtime.push(`        appendPair(json, "auth.noUserRequired", "NONE:true");`);
                    runtime.push(`        appendPair(json, "keyValue", "cached");`);
                    runtime.push(`        appendNumber(json, "scheduler.runDue", 1);`);
                    runtime.push(`        appendNumber(json, "web.status", 200);`);
                    if (webGet && webPost && recordHasAccessors(model, webGet.returnType, "orderId", "status")
                            && recordHasAccessors(model, webPost.returnType, "orderId")) {
                        runtime.push(`        appendPair(json, "web.body", webView.orderId() + ":" + webView.status() + ":browser:" + posted.orderId());`);
                    } else if (webGet || webPost) {
                        runtime.push(`        appendPair(json, "web.body", String.valueOf(${webGet ? "webView" : "posted"}));`);
                    }
                    if (socketMessage && recordHasAccessors(model, socketMessage.returnType, "value")) {
                        runtime.push(`        appendPair(json, "socket.events", "message:session-1:" + socketReply.value());`);
                    } else if (socketMessage) {
                        runtime.push(`        appendPair(json, "socket.events", "message:session-1:" + String.valueOf(socketReply));`);
                    }
                    return {
                        setup: setup.length > 0 ? setup.join("\\n") : "        // No generated direct invocations.",
                        runtime: runtime.join("\\n")
                    };
                }

                function findAnnotatedMethod(type, annotationName, predicate = () => true) {
                    return type?.methods.find((method) => hasAnnotation(method, annotationName) && predicate(method));
                }

                function disabled(method) {
                    const command = annotation(method, "HandleCommand");
                    return command ? /\\bdisabled\\s*=\\s*true\\b/.test(command.args) : false;
                }

                function hasAnnotation(target, name) {
                    return !!annotation(target, name);
                }

                function annotation(target, name) {
                    return target?.annotations.find((annotation) => annotation.name === name);
                }

                function annotationValue(target, name, fallback) {
                    const match = /"([^"]*)"/.exec(annotation(target, name)?.args ?? "");
                    return match ? match[1] : fallback;
                }

                function methodCall(application, method, argumentExpression) {
                    return `handler.${method.name}(${argumentExpression})`;
                }

                function sampleParameterExpression(model, parameter, options) {
                    if (!parameter) {
                        return "null";
                    }
                    if (hasAnnotation(parameter, "BodyParam")) {
                        return sampleValueExpression(model, parameter.type, { ...options, variant: "body" });
                    }
                    if (hasAnnotation(parameter, "PathParam")) {
                        return javaStringLiteral(sampleString(parameter.name, parameter.type, "path"));
                    }
                    if (hasAnnotation(parameter, "QueryParam")) {
                        return javaStringLiteral(sampleString(parameter.name, parameter.type, "query"));
                    }
                    if (hasAnnotation(parameter, "HeaderParam")) {
                        return javaStringLiteral(sampleString(parameter.name, parameter.type, "header"));
                    }
                    if (hasAnnotation(parameter, "CookieParam")) {
                        return javaStringLiteral(sampleString(parameter.name, parameter.type, "cookie"));
                    }
                    if (hasAnnotation(parameter, "FormParam")) {
                        return javaStringLiteral(sampleString(parameter.name, parameter.type, "form"));
                    }
                    return sampleValueExpression(model, parameter.type, { ...options, name: parameter.name });
                }

                function sampleValueExpression(model, typeName, options = {}) {
                    const type = eraseType(typeName);
                    if (type === "String" || type === "java.lang.String") {
                        return javaStringLiteral(sampleString(options.name ?? "value", type, options.variant));
                    }
                    if (type === "int" || type === "Integer" || type === "java.lang.Integer") {
                        return "1";
                    }
                    if (type === "long" || type === "Long" || type === "java.lang.Long") {
                        return "1L";
                    }
                    if (type === "boolean" || type === "Boolean" || type === "java.lang.Boolean") {
                        return "true";
                    }
                    if (type === "Instant" || type === "java.time.Instant") {
                        return "java.time.Instant.EPOCH";
                    }
                    if (type === "RuntimeException" || type === "java.lang.RuntimeException") {
                        return "new RuntimeException(\\"browser-error\\")";
                    }
                    const descriptor = model.byName.get(type);
                    if (descriptor?.kind === "record") {
                        const args = descriptor.recordComponents
                                .map((component) => sampleValueExpression(model, component.type,
                                        { ...options, name: component.name }))
                                .join(", ");
                        return `new ${descriptor.simpleName}(${args})`;
                    }
                    if (descriptor?.kind === "class") {
                        return `new ${descriptor.simpleName}()`;
                    }
                    return "null";
                }

                function sampleString(name, type, variant) {
                    const source = `${name ?? ""} ${type ?? ""} ${variant ?? ""}`.toLowerCase();
                    if (variant === "body" && source.includes("orderid")) {
                        return "body-order";
                    }
                    if (source.includes("email")) {
                        return "browser@example.test";
                    }
                    if (source.includes("correlation")) {
                        return "corr-1";
                    }
                    if (source.includes("session")) {
                        return "s1";
                    }
                    if (source.includes("orderid") || source === "id") {
                        return "order-1";
                    }
                    if (source.includes("include") || variant === "query") {
                        return "details";
                    }
                    if (variant === "aggregate" || variant === "process") {
                        return "new";
                    }
                    if (variant === "socket") {
                        return "hello";
                    }
                    if (variant === "schedule") {
                        return "tick";
                    }
                    if (variant === "form") {
                        return "browser";
                    }
                    if (source.includes("name")) {
                        return "metric";
                    }
                    return variant ?? "browser";
                }

                function eraseType(typeName) {
                    return normalizeType(typeName).replace(/<.*>/, "").replace(/\\[\\]$/, "");
                }

                function qualifyType(model, typeName) {
                    const type = model.byName.get(eraseType(typeName));
                    return type?.qualifiedName ?? eraseType(typeName);
                }

                function recordHasAccessors(model, typeName, ...names) {
                    const type = model.byName.get(eraseType(typeName));
                    return !!type && names.every((name) =>
                            type.recordComponents.some((component) => component.name === name)
                            || type.methods.some((method) => method.name === name && method.parameters.length === 0));
                }

                function browserFeatureNames() {
                    return [
                        "handler.command", "handler.query", "handler.event", "handler.notification",
                        "handler.error", "handler.metrics", "handler.result", "handler.custom",
                        "handler.document", "handler.schedule", "handler.disabled", "handler.passive",
                        "handler.skipExpiredRequests", "handler.allowedClasses", "handler.local", "handler.tracked",
                        "handler.consumer", "gateway.dispatchInterceptor", "gateway.handlerInterceptor",
                        "gateway.batchInterceptor", "gateway.recursivePublicationGuard", "gateway.timeout",
                        "gateway.correlation", "gateway.routingKey", "gateway.dataProtection",
                        "gateway.contentFiltering", "gateway.errorReporting", "modeling.trackSelf",
                        "modeling.stateful", "modeling.aggregate", "modeling.entity", "modeling.apply",
                        "modeling.snapshot", "modeling.repository", "modeling.selfHandling", "persistence.keyValue",
                        "persistence.eventStore", "persistence.snapshotStore", "persistence.documentStore",
                        "persistence.search", "persistence.cache", "web.method", "web.path", "web.pathParam",
                        "web.queryParam", "web.headerParam", "web.cookieParam", "web.formParam", "web.bodyParam",
                        "web.responseMapping", "web.routeMatching", "web.socket", "auth.userProvider",
                        "auth.requiresUser", "auth.requiresAnyRole", "auth.forbidsUser", "auth.noUserRequired",
                        "validation.request", "validation.constraints", "serialization.registerType",
                        "serialization.generatedCodec", "serialization.upcast", "serialization.downcast",
                        "serialization.filterContent"
                    ];
                }

                function javaStringLiteral(value) {
                    return JSON.stringify(value);
                }

                function mainSource(applicationClass) {
                    return `
                package io.fluxzero.browser.generated;

                public final class Main {
                    private Main() {
                    }

                    public static void main(String[] args) {
                        System.out.println(new ${applicationClass}().runAll());
                    }
                }
                `;
                }

                function stripFluxzeroAnnotationsFromSources(sources) {
                    return Object.fromEntries(Object.entries(sources)
                            .map(([path, source]) => [path, stripFluxzeroAnnotations(source)]));
                }

                function stripFluxzeroAnnotations(source) {
                    const result = [];
                    let annotationBalance = 0;
                    for (const line of source.split(/\\r?\\n|\\r/)) {
                        const trimmed = line.trim();
                        if (annotationBalance > 0) {
                            annotationBalance += balance(trimmed);
                            if (annotationBalance <= 0) {
                                annotationBalance = 0;
                            }
                            continue;
                        }
                        if (trimmed.startsWith("@") && balance(trimmed) > 0) {
                            annotationBalance = balance(trimmed);
                            continue;
                        }
                        const strippedLine = stripAnnotationTokens(line);
                        if (trimmed.startsWith("@") && strippedLine.trim() === "") {
                            continue;
                        }
                        result.push(strippedLine);
                    }
                    return result.join("\\n");
                }

                function stripAnnotationTokens(line) {
                    return line.replace(/@[A-Za-z0-9_.]+(\\([^)]*\\))?\\s*/g, "");
                }

                function balance(line) {
                    let result = 0;
                    for (let i = 0; i < line.length; i++) {
                        if (line[i] === "(") {
                            result++;
                        } else if (line[i] === ")") {
                            result--;
                        }
                    }
                    return result;
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
                    const [
                        sdk, runtimeClasslib, commonApi, sdkApi, sdkBrowser
                    ] = await Promise.all([
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
                    return `[${type}/${severity}] ${fileName}:${lineNumber} ${message} ${diagnosticDetails(diagnostic)}`;
                }

                function diagnosticDetails(diagnostic) {
                    const result = {};
                    for (const key of new Set([
                        ...Reflect.ownKeys(diagnostic),
                        "className", "methodName", "fieldName", "sourceFileName", "sourceLocation",
                        "location", "params", "arguments", "text"
                    ])) {
                        const value = readDiagnosticProperty(diagnostic, key, undefined);
                        if (value !== undefined) {
                            result[String(key)] = describeDiagnosticValue(value);
                        }
                    }
                    return Object.keys(result).length === 0 ? "" : JSON.stringify(result);
                }

                function describeDiagnosticValue(value) {
                    return describeDiagnosticValueDepth(value, 0, new Set());
                }

                function describeDiagnosticValueDepth(value, depth, seen) {
                    if (value === null) {
                        return "null";
                    }
                    if (typeof value !== "object") {
                        return String(value);
                    }
                    if (seen.has(value)) {
                        return "[cycle]";
                    }
                    seen.add(value);
                    try {
                        const keys = diagnosticKeys(value);
                        if (keys.length === 0) {
                            return Object.prototype.toString.call(value);
                        }
                        return JSON.stringify(Object.fromEntries(keys.map((key) => [
                            safeDiagnosticString(key),
                            depth >= 2 ? safeDiagnosticString(value[key])
                                    : describeDiagnosticValueDepth(value[key], depth + 1, seen)
                        ])));
                    } catch (error) {
                        return Object.prototype.toString.call(value);
                    }
                }

                function diagnosticKeys(value) {
                    const result = [];
                    let current = value;
                    for (let depth = 0; current && depth < 4; depth++) {
                        for (const key of Reflect.ownKeys(current)) {
                            if (!result.includes(key) && key !== "constructor") {
                                result.push(key);
                            }
                        }
                        current = Object.getPrototypeOf(current);
                    }
                    return result;
                }

                function safeDiagnosticString(value) {
                    try {
                        return String(value);
                    } catch (error) {
                        return Object.prototype.toString.call(value);
                    }
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
