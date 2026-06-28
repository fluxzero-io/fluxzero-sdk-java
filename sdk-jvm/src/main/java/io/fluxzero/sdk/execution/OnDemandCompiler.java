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

package io.fluxzero.sdk.execution;

import io.fluxzero.sdk.registry.ComponentDescriptor;
import lombok.SneakyThrows;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.io.File.pathSeparator;
import static java.util.stream.Collectors.joining;

class OnDemandCompiler {
    private static final List<String> ANNOTATION_PROCESSORS = List.of(
            "io.fluxzero.sdk.tracking.handling.RequestAnnotationProcessor",
            "io.fluxzero.sdk.web.WebParameterProcessor");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("\\bimport\\s+(?:static\\s+)?([\\w.]+)(\\.\\*)?\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\b(class|record|interface|enum)\\s+(\\w+)\\b");

    private final int release;
    private final Path sourceRoot;
    private final Path cacheRoot;
    private final List<Path> extraClasspath;
    private final List<Path> extraSourcepath;
    private final ClassLoader parentClassLoader;
    private volatile SourceGraph sourceGraph;

    OnDemandCompiler(int release, Path sourceRoot, Path cacheRoot, List<Path> extraClasspath,
                  List<Path> extraSourcepath, ClassLoader parentClassLoader) {
        this.release = release;
        this.sourceRoot = sourceRoot;
        this.cacheRoot = cacheRoot;
        this.extraClasspath = extraClasspath;
        this.extraSourcepath = extraSourcepath;
        this.parentClassLoader = parentClassLoader;
    }

    CompiledExecutionUnit compile(ComponentDescriptor component, String sourceHash) {
        Path outputDirectory = outputDirectory(sourceHash);
        if (!classFileExists(outputDirectory, component)) {
            compileTo(component, outputDirectory);
        }
        return new CompiledExecutionUnit(outputDirectory, parentClassLoader, Instant.now());
    }

    void compileAll(List<CompilationRequest> requests) {
        List<CompilationRequest> missing = requests.stream()
                .filter(request -> !classFileExists(outputDirectory(request.sourceHash()), request.component()))
                .distinct()
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Path batchOutputDirectory = batchOutputDirectory();
        try {
            SourceGraph graph = sourceGraph();
            compileTo(missing.stream().map(CompilationRequest::component).toList(), batchOutputDirectory, graph);
            for (CompilationRequest request : missing) {
                Path outputDirectory = outputDirectory(request.sourceHash());
                copyCompiledOutput(request.component(), batchOutputDirectory, outputDirectory, graph);
                if (!classFileExists(outputDirectory, request.component())) {
                    throw new OnDemandCompilationException(
                            "Batch compilation did not produce expected on-demand execution class: "
                            + request.component().fullClassName());
                }
            }
        } catch (IOException e) {
            throw new OnDemandCompilationException("Failed to batch compile on-demand execution sources", e);
        } finally {
            deleteDirectory(batchOutputDirectory);
        }
    }

    private void compileTo(ComponentDescriptor component, Path outputDirectory) {
        compileTo(List.of(component), outputDirectory, sourceGraph());
    }

    private void compileTo(List<ComponentDescriptor> components, Path outputDirectory, SourceGraph graph) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new OnDemandCompilationException(
                    "No Java compiler is available. Run OnDemandExecution on a JDK with the `jdk.compiler` module, not a JRE-only runtime.");
        }
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new OnDemandCompilationException("Failed to create on-demand execution output directory: " + outputDirectory, e);
        }

        DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles(components, graph));
            List<String> options = compilerOptions(outputDirectory);
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            if (!Boolean.TRUE.equals(success)) {
                throw new OnDemandCompilationException(
                        "Failed to compile on-demand execution source `%s`:%n%s".formatted(
                                components.stream().map(ComponentDescriptor::sourceFile).map(Objects::toString)
                                        .collect(joining(", ")),
                                formatDiagnostics(diagnostics)));
            }
        } catch (IOException e) {
            throw new OnDemandCompilationException(
                    "Failed to compile on-demand execution source: "
                    + components.stream().map(ComponentDescriptor::sourceFile).map(Objects::toString)
                            .collect(joining(", ")),
                    e);
        }
    }

    private List<Path> sourceFiles(ComponentDescriptor component, SourceGraph graph) {
        return graph.sourceFiles(component);
    }

    private List<Path> sourceFiles(List<ComponentDescriptor> components, SourceGraph graph) {
        Map<Path, Path> result = new LinkedHashMap<>();
        for (ComponentDescriptor component : components) {
            for (Path sourceFile : sourceFiles(component, graph)) {
                result.putIfAbsent(sourceFile.toAbsolutePath().normalize(), sourceFile);
            }
        }
        return List.copyOf(result.values());
    }

    private List<String> compilerOptions(Path outputDirectory) {
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add(String.valueOf(release));
        options.add("-parameters");
        options.add("-processor");
        options.add(String.join(",", ANNOTATION_PROCESSORS));
        options.add("-d");
        options.add(outputDirectory.toString());
        String classpath = classpath();
        if (!classpath.isBlank()) {
            options.add("-classpath");
            options.add(classpath);
        }
        String sourcepath = sourcepath();
        if (!sourcepath.isBlank()) {
            options.add("-sourcepath");
            options.add(sourcepath);
        }
        return options;
    }

    private String classpath() {
        List<String> entries = new ArrayList<>();
        String systemClasspath = System.getProperty("java.class.path", "");
        if (!systemClasspath.isBlank()) {
            entries.add(systemClasspath);
        }
        extraClasspath.stream().map(Path::toString).forEach(entries::add);
        return String.join(pathSeparator, entries);
    }

    private String sourcepath() {
        List<String> entries = new ArrayList<>();
        entries.add(sourceRoot.toString());
        extraSourcepath.stream().map(Path::toString).forEach(entries::add);
        return String.join(pathSeparator, entries);
    }

    private Path outputDirectory(String sourceHash) {
        return cacheRoot.resolve("release-" + release).resolve(sourceHash);
    }

    private Path batchOutputDirectory() {
        Path releaseRoot = cacheRoot.resolve("release-" + release);
        try {
            Files.createDirectories(releaseRoot);
            return Files.createTempDirectory(releaseRoot, ".batch-");
        } catch (IOException e) {
            throw new OnDemandCompilationException("Failed to create on-demand batch output directory", e);
        }
    }

    private boolean classFileExists(Path outputDirectory, ComponentDescriptor component) {
        return Files.isRegularFile(outputDirectory.resolve(component.fullClassName().replace('.', '/') + ".class"));
    }

    private void copyCompiledOutput(ComponentDescriptor component, Path fromRoot, Path toRoot, SourceGraph graph)
            throws IOException {
        List<String> classFilePrefixes = graph.classFilePrefixes(component);
        Set<Path> exactFiles = new LinkedHashSet<>();
        exactFiles.add(Path.of(parameterRegistryClassFileName(component)));
        graph.packageInfoClassFiles(component).forEach(exactFiles::add);
        if (!Files.isDirectory(fromRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(fromRoot)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path relative = fromRoot.relativize(source);
                if (shouldCopyClassFile(relative, classFilePrefixes, exactFiles)) {
                    Path target = toRoot.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean shouldCopyClassFile(Path relative, List<String> classFilePrefixes, Set<Path> exactFiles) {
        String path = relative.toString().replace('\\', '/');
        if (exactFiles.stream().map(Path::toString).map(value -> value.replace('\\', '/')).anyMatch(path::equals)) {
            return true;
        }
        return classFilePrefixes.stream().anyMatch(prefix ->
                path.equals(prefix + ".class") || path.startsWith(prefix + "$") && path.endsWith(".class"));
    }

    private static String parameterRegistryClassFileName(ComponentDescriptor component) {
        String relativeClassName = component.packageName().isBlank()
                ? component.fullClassName()
                : component.fullClassName().substring(component.packageName().length() + 1);
        return relativeClassName.replace(".", "_") + "_params.class";
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(OnDemandCompiler::deleteIfExists);
        } catch (IOException ignored) {
            // Best effort cleanup only. The cache remains valid if a temporary batch directory lingers.
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }

    private static String formatDiagnostics(DiagnosticCollector<?> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .map(OnDemandCompiler::formatDiagnostic)
                .collect(joining(System.lineSeparator()));
    }

    private static String formatDiagnostic(Diagnostic<?> diagnostic) {
        String source = diagnostic.getSource() == null ? "<unknown>" : diagnostic.getSource().toString();
        return "%s:%s:%s: %s".formatted(
                source, diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getMessage(null));
    }

    @SneakyThrows
    String sourceHash(ComponentDescriptor component) {
        return sourceHash(component, sourceGraph());
    }

    @SneakyThrows
    String refreshSourceHash(ComponentDescriptor component) {
        return sourceHash(component, refreshSourceGraph());
    }

    private String sourceHash(ComponentDescriptor component, SourceGraph graph)
            throws java.security.NoSuchAlgorithmException, IOException {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        for (Path sourceFile : sourceFiles(component, graph)) {
            if (Files.isRegularFile(sourceFile)) {
                digest.update(sourceFile.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(sourceFile));
                digest.update((byte) 0);
            }
        }
        digest.update((byte) 0);
        digest.update(Integer.toString(release).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(ANNOTATION_PROCESSORS.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(extraClasspath.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(extraSourcepath.toString().getBytes(StandardCharsets.UTF_8));
        byte[] bytes = digest.digest();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append("%02x".formatted(b));
        }
        return result.toString();
    }

    private SourceGraph sourceGraph() {
        SourceGraph graph = sourceGraph;
        if (graph != null) {
            return graph;
        }
        synchronized (this) {
            if (sourceGraph == null) {
                sourceGraph = scanSourceGraph();
            }
            return sourceGraph;
        }
    }

    private synchronized SourceGraph refreshSourceGraph() {
        sourceGraph = scanSourceGraph();
        return sourceGraph;
    }

    private SourceGraph scanSourceGraph() {
        List<Path> roots = new ArrayList<>();
        roots.add(sourceRoot);
        extraSourcepath.forEach(roots::add);
        return SourceGraph.scan(roots);
    }

    record CompilationRequest(ComponentDescriptor component, String sourceHash) {
        CompilationRequest {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(sourceHash, "sourceHash");
        }
    }

    private record SourceInfo(
            Path sourceFile, String packageName, Set<String> classNames, Map<String, String> imports,
            Set<String> wildcardImports, String source, Set<String> tokens) {

        Set<String> fullClassNames() {
            Set<String> result = new LinkedHashSet<>();
            for (String className : classNames) {
                result.add(packageName.isBlank() ? className : packageName + "." + className);
            }
            return result;
        }
    }

    private static final class SourceGraph {
        private final Map<String, SourceInfo> sourcesByClassName;
        private final Map<Path, SourceInfo> sourcesByPath;
        private final Map<String, Path> packageInfoByPackage;

        private SourceGraph(Map<String, SourceInfo> sourcesByClassName, Map<Path, SourceInfo> sourcesByPath,
                            Map<String, Path> packageInfoByPackage) {
            this.sourcesByClassName = sourcesByClassName;
            this.sourcesByPath = sourcesByPath;
            this.packageInfoByPackage = packageInfoByPackage;
        }

        static SourceGraph scan(List<Path> sourceRoots) {
            Map<String, SourceInfo> byClassName = new LinkedHashMap<>();
            Map<Path, SourceInfo> byPath = new LinkedHashMap<>();
            Map<String, Path> packageInfos = new LinkedHashMap<>();
            for (Path sourceRoot : sourceRoots.stream().filter(Files::isDirectory).distinct().toList()) {
                try (Stream<Path> files = Files.walk(sourceRoot)) {
                    for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                        String source = Files.readString(sourceFile);
                        String stripped = stripComments(source);
                        String packageName = first(PACKAGE_PATTERN, stripped).orElse("");
                        Path normalized = sourceFile.toAbsolutePath().normalize();
                        if ("package-info.java".equals(sourceFile.getFileName().toString())) {
                            packageInfos.putIfAbsent(packageName, sourceFile);
                            continue;
                        }
                        Set<String> classNames = classNames(stripped);
                        if (classNames.isEmpty()) {
                            continue;
                        }
                        SourceInfo info = new SourceInfo(
                                sourceFile, packageName, classNames, imports(stripped),
                                wildcardImports(stripped), stripped, tokens(stripped));
                        byPath.putIfAbsent(normalized, info);
                        info.fullClassNames().forEach(className -> byClassName.putIfAbsent(className, info));
                    }
                } catch (IOException e) {
                    throw new OnDemandCompilationException(
                            "Failed to inspect on-demand execution source graph: " + sourceRoot, e);
                }
            }
            return new SourceGraph(byClassName, byPath, packageInfos);
        }

        List<Path> sourceFiles(ComponentDescriptor component) {
            Map<Path, Path> result = new LinkedHashMap<>();
            SourceInfo root = sourcesByClassName.get(component.fullClassName());
            if (root == null && component.sourceFile() != null) {
                root = sourcesByPath.get(component.sourceFile().toAbsolutePath().normalize());
            }
            if (root == null) {
                addIfRegular(result, component.packageInfoSource());
                addIfRegular(result, component.sourceFile());
                return List.copyOf(result.values());
            }
            addWithDependencies(root, result, new HashSet<>());
            addIfRegular(result, component.packageInfoSource());
            addIfRegular(result, component.sourceFile());
            return List.copyOf(result.values());
        }

        List<String> classFilePrefixes(ComponentDescriptor component) {
            return sourceFiles(component).stream()
                    .map(path -> sourcesByPath.get(path.toAbsolutePath().normalize()))
                    .filter(Objects::nonNull)
                    .flatMap(source -> source.fullClassNames().stream())
                    .map(className -> className.replace('.', '/'))
                    .distinct()
                    .toList();
        }

        List<Path> packageInfoClassFiles(ComponentDescriptor component) {
            return sourceFiles(component).stream()
                    .filter(path -> "package-info.java".equals(path.getFileName().toString()))
                    .map(this::packageInfoClassFile)
                    .flatMap(Optional::stream)
                    .distinct()
                    .toList();
        }

        private Optional<Path> packageInfoClassFile(Path sourceFile) {
            try {
                String packageName = first(PACKAGE_PATTERN, Files.readString(sourceFile)).orElse("");
                return Optional.of(packageName.isBlank()
                        ? Path.of("package-info.class")
                        : Path.of(packageName.replace('.', '/')).resolve("package-info.class"));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        private void addWithDependencies(SourceInfo source, Map<Path, Path> result, Set<Path> visiting) {
            Path normalized = source.sourceFile().toAbsolutePath().normalize();
            if (!visiting.add(normalized)) {
                return;
            }
            addPackageInfoChain(source.packageName(), result);
            addIfRegular(result, source.sourceFile());
            for (SourceInfo dependency : dependencies(source)) {
                if (!Objects.equals(dependency.sourceFile().toAbsolutePath().normalize(), normalized)) {
                    addWithDependencies(dependency, result, visiting);
                }
            }
        }

        private Set<SourceInfo> dependencies(SourceInfo source) {
            Set<SourceInfo> result = new LinkedHashSet<>();
            for (SourceInfo candidate : sourcesByClassName.values()) {
                if (candidate == source) {
                    continue;
                }
                if (source.imports().values().stream().anyMatch(candidate.fullClassNames()::contains)) {
                    result.add(candidate);
                    continue;
                }
                if (source.wildcardImports().contains(candidate.packageName())
                    && candidate.classNames().stream().anyMatch(source.tokens()::contains)) {
                    result.add(candidate);
                    continue;
                }
                if (Objects.equals(source.packageName(), candidate.packageName())
                    && candidate.classNames().stream().anyMatch(source.tokens()::contains)) {
                    result.add(candidate);
                    continue;
                }
                if (candidate.fullClassNames().stream().anyMatch(source.source()::contains)) {
                    result.add(candidate);
                }
            }
            return result;
        }

        private void addPackageInfoChain(String packageName, Map<Path, Path> result) {
            for (String current = packageName; current != null; current = parentPackage(current)) {
                addIfRegular(result, packageInfoByPackage.get(current));
            }
        }

        private static void addIfRegular(Map<Path, Path> result, Path sourceFile) {
            if (sourceFile != null && Files.isRegularFile(sourceFile)) {
                result.putIfAbsent(sourceFile.toAbsolutePath().normalize(), sourceFile);
            }
        }
    }

    private static Set<String> classNames(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TYPE_PATTERN.matcher(source);
        while (matcher.find()) {
            result.add(matcher.group(2));
        }
        return result;
    }

    private static Map<String, String> imports(String source) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                String name = matcher.group(1);
                result.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
        return result;
    }

    private static Set<String> wildcardImports(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            if (matcher.group(2) != null) {
                result.add(matcher.group(1));
            }
        }
        return result;
    }

    private static Set<String> tokens(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\b[A-Za-z_$][A-Za-z\\d_$]*\\b").matcher(source);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static Optional<String> first(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String parentPackage(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? null : packageName.substring(0, lastDot);
    }

    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean string = false;
        boolean character = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (!string && !character && c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                if (i < source.length()) {
                    result.append('\n');
                }
                continue;
            }
            if (!string && !character && c == '/' && next == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    result.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                i++;
                continue;
            }
            result.append(c);
            if (c == '\\') {
                if (i + 1 < source.length()) {
                    result.append(source.charAt(++i));
                }
                continue;
            }
            if (!character && c == '"') {
                string = !string;
            } else if (!string && c == '\'') {
                character = !character;
            }
        }
        return result.toString();
    }
}
