import { load } from "./assets/compiler.wasm-runtime.js";

const ASSETS = {
    compiler: "./assets/compiler.wasm",
    commonApi: "./assets/common-api.jar",
    sdkApi: "./assets/sdk-api.jar",
    sdkBrowser: "./assets/sdk-browser.jar",
    fluxzeroSources: "./assets/fluxzero-browser-sources.json",
    sdk: "./assets/compile-classlib-teavm.bin",
    runtimeClasslib: "./assets/runtime-classlib-teavm.bin"
};

const DEFAULT_SOURCE = `// file: demo/Main.java
package demo;

import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.QueryGateway;

public class Main {
    public static void main(String[] args) throws Exception {
        CommandGateway commands = FluxzeroBrowserApplication.commandGateway();
        QueryGateway queries = FluxzeroBrowserApplication.queryGateway();

        String created = commands.sendAndWait(new CreateOrder("order-100", 3));
        String cancelled = commands.sendAndWait(new CancelOrder("order-200", "duplicate"));
        String status = queries.sendAndWait(new GetOrderStatus("order-100"));

        System.out.println("created: " + created);
        System.out.println("cancelled: " + cancelled);
        System.out.println("status: " + status);
    }
}

// file: demo/CreateOrder.java
package demo;

public record CreateOrder(String orderId, int quantity) {
}

// file: demo/CancelOrder.java
package demo;

public record CancelOrder(String orderId, String reason) {
}

// file: demo/GetOrderStatus.java
package demo;

public record GetOrderStatus(String orderId) {
}

// file: demo/OrderHandlers.java
package demo;

import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;

public class OrderHandlers {
    @HandleCommand(allowedClasses = CreateOrder.class)
    public String create(CreateOrder command) {
        return "created " + command.quantity() + " item(s) for " + command.orderId();
    }

    @HandleCommand(allowedClasses = CancelOrder.class)
    public String cancel(CancelOrder command) {
        return "cancelled " + command.orderId() + " because " + command.reason();
    }

    @HandleQuery(allowedClasses = GetOrderStatus.class)
    public String status(GetOrderStatus query) {
        return query.orderId() + " is locally visible";
    }
}
`;

const elements = {
    source: document.querySelector("#source"),
    compile: document.querySelector("#compile"),
    reset: document.querySelector("#reset"),
    status: document.querySelector("#status"),
    diagnostics: document.querySelector("#diagnostics"),
    output: document.querySelector("#output"),
    timings: document.querySelector("#timings")
};

let compiler;
let compilerReadyPromise;
let fluxzeroBrowserSources = {};

elements.source.value = DEFAULT_SOURCE;
elements.compile.addEventListener("click", () => compileAndRun().catch(reportFailure));
elements.reset.addEventListener("click", () => {
    elements.source.value = DEFAULT_SOURCE;
    clearResults();
});

function clearResults() {
    elements.status.textContent = "Ready";
    elements.diagnostics.textContent = "";
    elements.output.textContent = "";
    elements.timings.replaceChildren();
}

async function getCompiler() {
    if (!compilerReadyPromise) {
        compilerReadyPromise = initializeCompiler();
    }
    return compilerReadyPromise;
}

async function initializeCompiler() {
    elements.status.textContent = "Loading TeaVM compiler";
    const started = performance.now();
    const teavm = await load(ASSETS.compiler);
    compiler = teavm.exports.createCompiler();

    const [sdk, runtimeClasslib, commonApi, sdkApi, sdkBrowser, sourceBundle] = await Promise.all([
        fetchBytes(ASSETS.sdk),
        fetchBytes(ASSETS.runtimeClasslib),
        fetchBytes(ASSETS.commonApi),
        fetchBytes(ASSETS.sdkApi),
        fetchBytes(ASSETS.sdkBrowser),
        fetchJson(ASSETS.fluxzeroSources)
    ]);
    fluxzeroBrowserSources = sourceBundle;
    compiler.setSdk(sdk);
    compiler.setTeaVMClasslib(runtimeClasslib);
    compiler.addJarFile(commonApi);
    compiler.addJarFile(sdkApi);
    compiler.addJarFile(sdkBrowser);
    setTiming("compiler.load", started);
    elements.status.textContent = "Compiler loaded";
    return compiler;
}

async function compileAndRun() {
    clearResults();
    elements.compile.disabled = true;
    try {
        const activeCompiler = await getCompiler();
        const diagnostics = [];
        const registration = activeCompiler.onDiagnostic(diagnostic => {
            diagnostics.push(diagnostic);
            elements.diagnostics.textContent = formatDiagnostics(diagnostics);
        });

        try {
            activeCompiler.clearSourceFiles();
            activeCompiler.clearOutputFiles();
            const appSources = parseSourceBundle(elements.source.value);
            const registryMetadata = scanRegistryMetadata(appSources);
            const loweredAppSources = lowerFluxzeroAnnotations(appSources);
            const generatedFluxzeroSources = generateFluxzeroSources(registryMetadata, appSources);
            addSourceFiles(activeCompiler, fluxzeroBrowserSources);
            addSourceFiles(activeCompiler, loweredAppSources);
            addSourceFiles(activeCompiler, generatedFluxzeroSources);
            addMetric("source.files", String(
                Object.keys(fluxzeroBrowserSources).length
                + Object.keys(loweredAppSources).length
                + Object.keys(generatedFluxzeroSources).length));
            addMetric("registry.handlers", String(registryMetadata.handlers.length));
            addMetric("registry.commands", String(registryMetadata.handlers.filter(route => route.messageType === "command").length));
            addMetric("registry.queries", String(registryMetadata.handlers.filter(route => route.messageType === "query").length));
            addMetric("registry.allowedClasses", registryMetadata.allowedClasses.join(", ") || "(none)");

            elements.status.textContent = "Compiling Java source";
            const compileStarted = performance.now();
            const compiled = activeCompiler.compile();
            setTiming("javac.wasm.compile", compileStarted);
            elements.diagnostics.textContent = formatDiagnostics(diagnostics);

            if (!compiled) {
                elements.status.textContent = "Compilation failed";
                return;
            }

            elements.status.textContent = "Generating application Wasm";
            const wasmStarted = performance.now();
            const generated = activeCompiler.generateWebAssembly({
                outputName: "app",
                mainClass: "demo.Main"
            });
            setTiming("teavm.generateWasm", wasmStarted);
            elements.diagnostics.textContent = formatDiagnostics(diagnostics);

            if (!generated) {
                elements.status.textContent = "WebAssembly generation failed";
                return;
            }

            const wasmBytes = activeCompiler.getWebAssemblyOutputFile("app.wasm");
            addMetric("teavm.appWasm", formatBytes(wasmBytes.byteLength));
            elements.status.textContent = `Running ${formatBytes(wasmBytes.byteLength)} Wasm module`;

            const runStarted = performance.now();
            const logs = await captureConsole(() => runGeneratedWasm(wasmBytes));
            setTiming("app.run", runStarted);
            elements.output.textContent = logs.join("\n") || "(no output)";
            elements.status.textContent = "Done";
        } finally {
            if (registration && typeof registration.destroy === "function") {
                registration.destroy();
            }
        }
    } finally {
        elements.compile.disabled = false;
    }
}

async function runGeneratedWasm(wasmBytes) {
    const app = await load(wasmBytes);
    app.exports.main([]);
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

function setTiming(name, started) {
    addMetric(name, `${Math.round(performance.now() - started)} ms`);
}

function addMetric(name, text) {
    const row = document.createElement("tr");
    const label = document.createElement("th");
    const value = document.createElement("td");
    label.textContent = name;
    value.textContent = text;
    row.append(label, value);
    elements.timings.append(row);
}

function formatDiagnostics(diagnostics) {
    if (!diagnostics.length) {
        return "";
    }
    return diagnostics
        .map(formatDiagnostic)
        .join("\n");
}

function formatDiagnostic(diagnostic) {
    const type = readDiagnosticProperty(diagnostic, "type", "diagnostic");
    const severity = readDiagnosticProperty(diagnostic, "severity", "other");
    const fileName = readDiagnosticProperty(diagnostic, "fileName", "");
    const lineNumber = readDiagnosticProperty(diagnostic, "lineNumber", "?");
    const message = readDiagnosticProperty(diagnostic, "message", "(diagnostic message unavailable)");
    const location = fileName ? `${fileName}:${lineNumber}` : "(unknown source)";
    return `[${type}/${severity}] ${location} ${message}`;
}

function readDiagnosticProperty(diagnostic, property, fallback) {
    try {
        return diagnostic[property] ?? fallback;
    } catch (error) {
        return fallback;
    }
}

function addSourceFiles(activeCompiler, sources) {
    for (const [path, content] of Object.entries(sources)) {
        activeCompiler.addSourceFile(path.substring(path.lastIndexOf("/") + 1), content);
    }
}

function parseSourceBundle(text) {
    const marker = /^\/\/\s*file:\s*(\S+)\s*$/;
    const sources = {};
    let currentPath = "demo/Main.java";
    let currentLines = [];
    for (const line of text.split(/\r?\n/)) {
        const match = marker.exec(line);
        if (match) {
            flushSource(sources, currentPath, currentLines);
            currentPath = match[1];
            currentLines = [];
        } else {
            currentLines.push(line);
        }
    }
    flushSource(sources, currentPath, currentLines);
    return sources;
}

function flushSource(sources, path, lines) {
    const content = lines.join("\n").trim();
    if (content) {
        sources[path] = `${content}\n`;
    }
}

function scanRegistryMetadata(sources) {
    const handlers = [];
    const allowedClasses = [];
    for (const [path, content] of Object.entries(sources)) {
        const packageName = readPackageName(content);
        const imports = readImports(content);
        const className = readTopLevelClassName(content);
        if (!className) {
            continue;
        }
        const handlerClass = qualifyName(className, packageName);
        const annotationPattern = /@Handle(Command|Query)\s*(?:\(([^)]*)\))?/g;
        let match;
        while ((match = annotationPattern.exec(content)) !== null) {
            const annotationKind = match[1];
            const attributes = match[2] ?? "";
            const executable = readFollowingMethod(content, match.index + match[0].length, packageName, imports);
            if (!executable) {
                continue;
            }
            const explicitAllowedClasses = readClassLiterals(attributes)
                .map(typeName => resolveTypeName(typeName, packageName, imports));
            const routeAllowedClasses = explicitAllowedClasses.length
                ? explicitAllowedClasses
                : [executable.parameterType].filter(Boolean);
            const route = {
                sourceFile: path,
                packageName,
                handlerClass,
                handlerClassSimpleName: className,
                handlerAnnotation: `io.fluxzero.sdk.tracking.handling.Handle${annotationKind}`,
                messageType: annotationKind.toLowerCase(),
                methodName: executable.methodName,
                returnType: executable.returnType,
                parameterType: executable.parameterType,
                allowedClasses: routeAllowedClasses
            };
            handlers.push(route);
            allowedClasses.push(...route.allowedClasses);
        }
    }
    return {
        handlers,
        allowedClasses: [...new Set(allowedClasses)]
    };
}

function lowerFluxzeroAnnotations(sources) {
    return Object.fromEntries(Object.entries(sources).map(([path, content]) => [
        path,
        content
            .replace(/@HandleCommand\s*\([^)]*\)/g, "@HandleCommand")
            .replace(/@HandleQuery\s*\([^)]*\)/g, "@HandleQuery")
    ]));
}

function generateFluxzeroSources(registryMetadata, appSources) {
    const packageName = readMainPackageName(appSources)
        ?? registryMetadata.handlers[0]?.packageName
        ?? "demo";
    const commandRoutes = registryMetadata.handlers.filter(route => route.messageType === "command");
    const queryRoutes = registryMetadata.handlers.filter(route => route.messageType === "query");
    return {
        [`${packageName.replaceAll(".", "/")}/FluxzeroBrowserApplication.java`]: `package ${packageName};

import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.QueryGateway;
import io.fluxzero.sdk.browser.BrowserCommandGateway;
import io.fluxzero.sdk.browser.BrowserQueryGateway;

public final class FluxzeroBrowserApplication {
    private FluxzeroBrowserApplication() {
    }

    public static CommandGateway commandGateway() {
        return new BrowserCommandGateway(command -> {
${generateDispatchCases(commandRoutes, "command", "HandleCommand")}
            throw new IllegalArgumentException("No @HandleCommand handler registered for command");
        });
    }

    public static QueryGateway queryGateway() {
        return new BrowserQueryGateway(query -> {
${generateDispatchCases(queryRoutes, "query", "HandleQuery")}
            throw new IllegalArgumentException("No @HandleQuery handler registered for query");
        });
    }
}
`
    };
}

function generateDispatchCases(routes, variableName, annotationName) {
    if (!routes.length) {
        return `            // No @${annotationName} routes were discovered.`;
    }
    return routes
        .map(route => {
            const payloadType = route.allowedClasses[0] ?? route.parameterType;
            return `            if (${variableName} instanceof ${payloadType} payload) {
                return new ${route.handlerClass}().${route.methodName}(payload);
            }`;
        })
        .join("\n");
}

function readPackageName(content) {
    return /^\s*package\s+([\w.]+)\s*;/m.exec(content)?.[1] ?? "";
}

function readMainPackageName(sources) {
    for (const content of Object.values(sources)) {
        if (/\bclass\s+Main\b/.test(content)) {
            return readPackageName(content);
        }
    }
    return null;
}

function readTopLevelClassName(content) {
    return /\b(?:public\s+)?(?:final\s+)?(?:sealed\s+)?(?:non-sealed\s+)?(?:abstract\s+)?(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)\b/
        .exec(content)?.[1] ?? null;
}

function readFollowingMethod(content, fromIndex, packageName, imports) {
    const afterAnnotation = content.slice(fromIndex);
    const methodPattern = /^\s*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?([A-Za-z_$][\w$<>, ?.\[\]]*)\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)/;
    const match = methodPattern.exec(afterAnnotation);
    if (!match) {
        return null;
    }
    const parameterType = readFirstParameterType(match[3], packageName, imports);
    return {
        returnType: match[1].trim(),
        methodName: match[2],
        parameterType
    };
}

function readFirstParameterType(parameters, packageName, imports) {
    const firstParameter = parameters.split(",")[0]?.trim();
    if (!firstParameter) {
        return "";
    }
    const normalized = firstParameter
        .replace(/@\w+(?:\([^)]*\))?\s*/g, "")
        .replace(/\bfinal\s+/g, "")
        .trim();
    const parts = normalized.split(/\s+/);
    if (parts.length < 2) {
        return "";
    }
    return resolveTypeName(parts.slice(0, -1).join(" "), packageName, imports);
}

function readImports(content) {
    const imports = new Map();
    for (const match of content.matchAll(/^\s*import\s+([\w.]+)\s*;/gm)) {
        const fullyQualifiedName = match[1];
        imports.set(fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf(".") + 1), fullyQualifiedName);
    }
    return imports;
}

function readClassLiterals(attributes) {
    return [...attributes.matchAll(/([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\.class/g)]
        .map(match => match[1]);
}

function resolveTypeName(typeName, packageName, imports) {
    const trimmedTypeName = typeName.trim();
    if (!trimmedTypeName) {
        return trimmedTypeName;
    }
    const arraySuffix = trimmedTypeName.endsWith("[]") ? "[]" : "";
    const baseTypeName = arraySuffix ? trimmedTypeName.slice(0, -2) : trimmedTypeName;
    if (isPrimitiveType(baseTypeName)) {
        return trimmedTypeName;
    }
    if (baseTypeName.startsWith("java.") || baseTypeName.includes("<")) {
        return trimmedTypeName;
    }
    if (baseTypeName.includes(".")) {
        return trimmedTypeName;
    }
    return (imports.get(baseTypeName) ?? (packageName ? `${packageName}.${baseTypeName}` : baseTypeName)) + arraySuffix;
}

function qualifyName(simpleName, packageName) {
    return packageName ? `${packageName}.${simpleName}` : simpleName;
}

function isPrimitiveType(typeName) {
    return ["boolean", "byte", "short", "int", "long", "float", "double", "char", "void"].includes(typeName);
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

function formatBytes(value) {
    if (value < 1024) {
        return `${value} B`;
    }
    if (value < 1024 * 1024) {
        return `${(value / 1024).toFixed(1)} KB`;
    }
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function reportFailure(error) {
    elements.status.textContent = "Failed";
    elements.diagnostics.textContent = error.stack || String(error);
}
