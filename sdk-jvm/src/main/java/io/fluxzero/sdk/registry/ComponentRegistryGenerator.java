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

import lombok.NonNull;

import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Build-time source producer for the incubating Fluxzero component registry.
 * <p>
 * The generator scans Java source text only. It does not invoke javac, load application classes, or run annotation
 * processors. This makes it suitable for {@code src/main/fluxzero} and {@code src/test/fluxzero} sources that should
 * remain outside the normal application compilation path while still producing a registry artifact that runtime and
 * tooling can consume.
 */
public final class ComponentRegistryGenerator {
    /**
     * Default source root for on-demand Fluxzero source units.
     */
    public static final Path DEFAULT_SOURCE_ROOT = Path.of("src/main/fluxzero");

    /**
     * Default test source root for on-demand Fluxzero source units.
     */
    public static final Path DEFAULT_TEST_SOURCE_ROOT = Path.of("src/test/fluxzero");

    /**
     * Default class-output artifact path.
     */
    public static final Path DEFAULT_OUTPUT = Path.of("target/classes")
            .resolve(ComponentRegistryJson.DEFAULT_RESOURCE);

    /**
     * Default test class-output artifact path.
     */
    public static final Path DEFAULT_TEST_OUTPUT = Path.of("target/test-classes")
            .resolve(ComponentRegistryJson.DEFAULT_RESOURCE);

    private static final String DEFAULT_TITLE = "Fluxzero Source Registry";
    private static final Set<String> HANDLER_ANNOTATIONS = Set.of(
            "io.fluxzero.sdk.tracking.handling.HandleCommand",
            "io.fluxzero.sdk.tracking.handling.HandleQuery",
            "io.fluxzero.sdk.tracking.handling.HandleEvent",
            "io.fluxzero.sdk.tracking.handling.HandleNotification",
            "io.fluxzero.sdk.tracking.handling.HandleError",
            "io.fluxzero.sdk.tracking.handling.HandleMetrics",
            "io.fluxzero.sdk.tracking.handling.HandleResult",
            "io.fluxzero.sdk.tracking.handling.HandleCustom",
            "io.fluxzero.sdk.tracking.handling.HandleDocument",
            "io.fluxzero.sdk.tracking.handling.HandleSchedule",
            "io.fluxzero.sdk.web.HandleWebResponse",
            "io.fluxzero.sdk.web.HandleWeb",
            "io.fluxzero.sdk.web.HandleGet",
            "io.fluxzero.sdk.web.HandlePost",
            "io.fluxzero.sdk.web.HandlePut",
            "io.fluxzero.sdk.web.HandlePatch",
            "io.fluxzero.sdk.web.HandleDelete",
            "io.fluxzero.sdk.web.HandleHead",
            "io.fluxzero.sdk.web.HandleOptions",
            "io.fluxzero.sdk.web.HandleTrace",
            "io.fluxzero.sdk.web.HandleSocketHandshake",
            "io.fluxzero.sdk.web.HandleSocketOpen",
            "io.fluxzero.sdk.web.HandleSocketMessage",
            "io.fluxzero.sdk.web.HandleSocketPong",
            "io.fluxzero.sdk.web.HandleSocketClose"
    );
    private static final Set<ComponentCapability> STRUCTURAL_CAPABILITIES = Set.of(
            ComponentCapability.SOURCE_COMPONENT,
            ComponentCapability.CLASSPATH_COMPONENT);
    private static final List<String> RELEVANT_ANNOTATION_PREFIXES = List.of(
            "io.fluxzero.",
            "jakarta.validation.",
            "com.fasterxml.jackson.annotation.");
    private static final Set<String> IGNORED_TYPE_NAMES = Set.of(
            "",
            "void",
            "boolean",
            "byte",
            "char",
            "double",
            "float",
            "int",
            "long",
            "short");
    private static final Pattern GENERIC_TYPE_MARKER = Pattern.compile("[<>,?&]");
    private static final Pattern UNSTABLE_LOCAL_CLASS_NAME = Pattern.compile(".*\\$\\d.*");

    private ComponentRegistryGenerator() {
    }

    /**
     * Generates the registry JSON artifact and optionally a Markdown blueprint.
     * <p>
     * Missing source roots are treated as an empty registry. Use {@link #generate(Path, Path, Path, String, boolean)}
     * with {@code strict=true} when build tooling should fail if the source root is absent.
     *
     * @param sourceRoot source root to scan
     * @param output registry JSON output path
     * @param blueprintOutput optional Markdown blueprint output path, or {@code null}
     * @param blueprintTitle Markdown title used when a blueprint output path is supplied
     * @return the generated registry, or an empty registry when the source root is absent
     */
    public static ComponentRegistry generate(
            @NonNull Path sourceRoot, @NonNull Path output, Path blueprintOutput, String blueprintTitle) {
        return generate(sourceRoot, output, blueprintOutput, blueprintTitle, false);
    }

    /**
     * Generates the registry JSON artifact and optionally a Markdown blueprint.
     *
     * @param sourceRoot source root to scan
     * @param output registry JSON output path
     * @param blueprintOutput optional Markdown blueprint output path, or {@code null}
     * @param blueprintTitle Markdown title used when a blueprint output path is supplied
     * @param strict whether a missing source root should fail generation
     * @return the generated registry, or an empty registry when the source root is absent and strict mode is disabled
     */
    public static ComponentRegistry generate(
            @NonNull Path sourceRoot, @NonNull Path output, Path blueprintOutput,
            String blueprintTitle, boolean strict) {
        return generate(sourceRoot, output, blueprintOutput, blueprintTitle, strict, false);
    }

    /**
     * Generates the registry JSON artifact and optionally merges it with a previously generated registry at the same
     * output path.
     * <p>
     * Build lifecycles that also run the javac-backed registry annotation processor should use {@code mergeExisting}
     * after compilation. That keeps compiled classpath metadata and {@code src/.../fluxzero} source metadata in one
     * classpath artifact.
     *
     * @param sourceRoot source root to scan
     * @param output registry JSON output path
     * @param blueprintOutput optional Markdown blueprint output path, or {@code null}
     * @param blueprintTitle Markdown title used when a blueprint output path is supplied
     * @param strict whether a missing source root should fail generation
     * @param mergeExisting whether to merge the scanned registry with an existing output file before writing
     * @return the generated or merged registry, or an empty registry when the source root is absent and strict mode is
     * disabled
     */
    public static ComponentRegistry generate(
            @NonNull Path sourceRoot, @NonNull Path output, Path blueprintOutput,
            String blueprintTitle, boolean strict, boolean mergeExisting) {
        return generate(sourceRoot, output, blueprintOutput, blueprintTitle, strict, mergeExisting, false);
    }

    /**
     * Generates the registry JSON artifact and can optionally mark the generated registry as metadata-only.
     * <p>
     * Metadata-only registries are written without a source root. Runtime can then consume them as classpath app-model
     * metadata without treating the scanned source root as an on-demand execution source root.
     *
     * @param sourceRoot source root to scan
     * @param output registry JSON output path
     * @param blueprintOutput optional Markdown blueprint output path, or {@code null}
     * @param blueprintTitle Markdown title used when a blueprint output path is supplied
     * @param strict whether a missing source root should fail generation
     * @param mergeExisting whether to merge the scanned registry with an existing output file before writing
     * @param metadataOnly whether the written registry should omit its source root
     * @return the generated or merged registry, or an empty registry when the source root is absent and strict mode is
     * disabled
     */
    public static ComponentRegistry generate(
            @NonNull Path sourceRoot, @NonNull Path output, Path blueprintOutput,
            String blueprintTitle, boolean strict, boolean mergeExisting, boolean metadataOnly) {
        return generate(sourceRoot, null, output, blueprintOutput, blueprintTitle, strict, mergeExisting, metadataOnly);
    }

    /**
     * Generates the registry JSON artifact and optionally merges compiled class metadata from a class-output root.
     * <p>
     * The class root is intentionally scoped to classes compiled by the current project. It fills gaps that source and
     * annotation-processing producers cannot represent, such as anonymous handler classes used by JVM tests.
     *
     * @param sourceRoot source root to scan
     * @param classRoot optional class-output root to scan for compiled project classes, or {@code null}
     * @param output registry JSON output path
     * @param blueprintOutput optional Markdown blueprint output path, or {@code null}
     * @param blueprintTitle Markdown title used when a blueprint output path is supplied
     * @param strict whether a missing source root should fail generation
     * @param mergeExisting whether to merge the scanned registry with an existing output file before writing
     * @param metadataOnly whether the written registry should omit its source root
     * @return the generated or merged registry
     */
    public static ComponentRegistry generate(
            @NonNull Path sourceRoot, Path classRoot, @NonNull Path output, Path blueprintOutput,
            String blueprintTitle, boolean strict, boolean mergeExisting, boolean metadataOnly) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(output, "output");
        List<ComponentRegistry> registries = new ArrayList<>();
        ComponentRegistry existingRegistry = mergeExisting && Files.isRegularFile(output)
                ? ComponentRegistryJson.read(output) : null;
        if (!Files.isDirectory(sourceRoot)) {
            if (strict) {
                throw new ComponentRegistryException(
                        "Component source root does not exist or is not a directory: " + sourceRoot);
            }
        } else {
            ComponentRegistry sourceRegistry = new SourceComponentScanner().scan(sourceRoot).normalized();
            if (metadataOnly) {
                sourceRegistry = new ComponentRegistry(
                        null, sourceRegistry.packages(), sourceRegistry.components()).normalized();
            }
            registries.add(sourceRegistry);
        }
        if (classRoot != null && Files.isDirectory(classRoot)) {
            registries.add(scanClassRoot(classRoot, classRootCandidates(registries, existingRegistry)));
        }
        if (registries.isEmpty() && !(mergeExisting && Files.isRegularFile(output))) {
            return ComponentRegistry.empty();
        }
        ComponentRegistry registry = registries.isEmpty() ? ComponentRegistry.empty() : ComponentRegistry.merge(registries);
        if (existingRegistry != null) {
            registry = ComponentRegistry.merge(List.of(existingRegistry, registry));
        }
        if (metadataOnly) {
            registry = pruneMetadataOnlyRegistry(registry);
        }
        ComponentRegistryJson.write(registry, output);
        if (blueprintOutput != null) {
            try {
                ComponentRegistryBlueprint.from(registry)
                        .withTitle(blueprintTitle == null || blueprintTitle.isBlank() ? DEFAULT_TITLE : blueprintTitle)
                        .writeMarkdown(blueprintOutput);
            } catch (Exception e) {
                throw new ComponentRegistryException(
                        "Failed to write Fluxzero component registry blueprint: " + blueprintOutput, e);
            }
        }
        return registry;
    }

    /**
     * Command-line entrypoint for build tools and local agent workflows.
     */
    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path sourceRoot = null;
        Path output = null;
        Path blueprint = null;
        Path classRoot = null;
        String title = DEFAULT_TITLE;
        boolean strict = false;
        boolean testSources = false;
        boolean mergeExisting = false;
        boolean metadataOnly = false;
        boolean skipIfCurrent = false;

        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--source-root" -> sourceRoot = Path.of(value(args, ++i, arg));
                    case "--class-root" -> classRoot = Path.of(value(args, ++i, arg));
                    case "--output" -> output = Path.of(value(args, ++i, arg));
                    case "--blueprint" -> blueprint = Path.of(value(args, ++i, arg));
                    case "--title" -> title = value(args, ++i, arg);
                    case "--strict" -> strict = true;
                    case "--test" -> testSources = true;
                    case "--merge-existing" -> mergeExisting = true;
                    case "--metadata-only" -> metadataOnly = true;
                    case "--skip-if-current" -> skipIfCurrent = true;
                    case "--help", "-h" -> {
                        out.println(usage());
                        return 0;
                    }
                    default -> {
                        err.println("Unknown argument: " + arg);
                        err.println(usage());
                        return 2;
                    }
                }
            }
            sourceRoot = sourceRoot == null
                    ? testSources ? DEFAULT_TEST_SOURCE_ROOT : DEFAULT_SOURCE_ROOT : sourceRoot;
            output = output == null
                    ? testSources ? DEFAULT_TEST_OUTPUT : DEFAULT_OUTPUT : output;

            if (skipIfCurrent && isCurrent(output, sourceRoot, classRoot)) {
                out.println("Fluxzero component registry up to date: " + output);
                return 0;
            }

            ComponentRegistry registry = generate(
                    sourceRoot, classRoot, output, blueprint, title, strict, mergeExisting, metadataOnly);
            if (registry.isEmpty() && !Files.isDirectory(sourceRoot)) {
                out.println("Fluxzero component registry skipped; source root does not exist: " + sourceRoot);
            } else {
                out.println("Fluxzero component registry written: " + output);
                if (blueprint != null) {
                    out.println("Fluxzero component blueprint written: " + blueprint);
                }
            }
            return 0;
        } catch (Exception e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static String usage() {
        return """
                Usage: java io.fluxzero.sdk.registry.ComponentRegistryGenerator [options]

                Options:
                  --source-root <path>   Source root to scan. Defaults to src/main/fluxzero.
                  --class-root <path>    Optional compiled class-output root to scan for project class metadata.
                  --output <path>        Registry JSON output. Defaults to target/classes/%s.
                  --test                 Use test defaults: src/test/fluxzero and target/test-classes/%s.
                  --blueprint <path>     Optional Markdown blueprint output.
                  --title <title>        Optional blueprint title.
                  --strict               Fail when the source root is absent.
                  --merge-existing       Merge with an existing output file before writing.
                  --metadata-only        Omit sourceRoot so runtime does not enable on-demand execution for this root.
                  --skip-if-current      Skip generation when output is newer than source/class roots.
                  --help                 Show this help.
                """.formatted(ComponentRegistryJson.DEFAULT_RESOURCE, ComponentRegistryJson.DEFAULT_RESOURCE);
    }

    private static boolean isCurrent(Path output, Path sourceRoot, Path classRoot) {
        if (!Files.isRegularFile(output)) {
            return false;
        }
        try {
            FileTime outputTime = Files.getLastModifiedTime(output);
            Optional<FileTime> latestInput = Stream.of(sourceRoot, classRoot)
                    .filter(Objects::nonNull)
                    .filter(Files::exists)
                    .map(ComponentRegistryGenerator::latestModifiedTime)
                    .flatMap(Optional::stream)
                    .max(FileTime::compareTo);
            return latestInput.isPresent() && outputTime.compareTo(latestInput.orElseThrow()) >= 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Optional<FileTime> latestModifiedTime(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.map(path -> {
                        try {
                            return Files.getLastModifiedTime(path);
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .max(FileTime::compareTo);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static ComponentRegistry scanClassRoot(Path classRoot, Set<String> candidateClassNames) {
        List<Class<?>> classes;
        try (Stream<Path> files = Files.walk(classRoot)) {
            classes = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> isCandidateClass(classRoot, path, candidateClassNames))
                    .map(path -> loadClass(classRoot, path))
                    .flatMap(Optional::stream)
                    .filter(JvmComponentMetadataLookup::isScannable)
                    .filter(ComponentRegistryGenerator::isClassRootScannable)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            throw new ComponentRegistryException("Failed to scan compiled Fluxzero component classes in " + classRoot, e);
        }
        return classes.isEmpty() ? ComponentRegistry.empty() : new ClasspathComponentScanner().scan(classes).normalized();
    }

    private static Set<String> classRootCandidates(
            Collection<ComponentRegistry> registries, ComponentRegistry existingRegistry) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        registries.stream().flatMap(registry -> registry.components().stream())
                .map(ComponentDescriptor::fullClassName)
                .forEach(result::add);
        if (existingRegistry != null) {
            existingRegistry.components().stream()
                    .map(ComponentDescriptor::fullClassName)
                    .forEach(result::add);
        }
        return Set.copyOf(result);
    }

    private static boolean isCandidateClass(Path classRoot, Path classFile, Set<String> candidateClassNames) {
        if (candidateClassNames.isEmpty()) {
            return true;
        }
        String relativeName = classRoot.relativize(classFile).toString();
        if (relativeName.equals("module-info.class") || relativeName.endsWith("package-info.class")) {
            return false;
        }
        String className = relativeName.substring(0, relativeName.length() - ".class".length())
                .replace(classFile.getFileSystem().getSeparator(), ".");
        return candidateClassNames.contains(className) || candidateClassNames.contains(className.replace('$', '.'));
    }

    private static Optional<Class<?>> loadClass(Path classRoot, Path classFile) {
        String relativeName = classRoot.relativize(classFile).toString();
        if (relativeName.equals("module-info.class") || relativeName.endsWith("package-info.class")) {
            return Optional.empty();
        }
        String className = relativeName.substring(0, relativeName.length() - ".class".length())
                .replace(classFile.getFileSystem().getSeparator(), ".");
        try {
            return Optional.of(Class.forName(className, false, Thread.currentThread().getContextClassLoader()));
        } catch (ClassNotFoundException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private static boolean isClassRootScannable(Class<?> type) {
        return !type.isAnonymousClass() && !type.isLocalClass();
    }

    private static ComponentRegistry pruneMetadataOnlyRegistry(ComponentRegistry registry) {
        ComponentRegistry normalized = registry.normalized();
        if (normalized.isEmpty()) {
            return normalized;
        }
        LinkedHashMap<String, ComponentDescriptor> componentsByName = new LinkedHashMap<>();
        normalized.components().forEach(component -> {
            componentsByName.putIfAbsent(component.fullClassName(), component);
            componentsByName.putIfAbsent(component.fullClassName().replace('$', '.'), component);
        });
        LinkedHashMap<String, ComponentDescriptor> kept = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        normalized.components().stream()
                .filter(component -> isStableComponentName(component.fullClassName()))
                .filter(ComponentRegistryGenerator::isAppModelRoot)
                .map(ComponentDescriptor::fullClassName)
                .forEach(name -> enqueue(name, componentsByName, kept, queue));
        normalized.packages().stream()
                .filter(ComponentRegistryGenerator::isAppModelRoot)
                .flatMap(p -> p.registeredTypes().stream())
                .flatMap(registeredType -> registeredType.candidateTypeNames().stream())
                .forEach(name -> enqueue(name, componentsByName, kept, queue));
        while (!queue.isEmpty()) {
            ComponentDescriptor component = kept.get(queue.removeFirst());
            if (component == null) {
                continue;
            }
            referencedTypeNames(component).forEach(name -> enqueue(name, componentsByName, kept, queue));
        }
        Set<String> keptNames = allNames(kept.values());
        List<ComponentDescriptor> components = kept.values().stream()
                .map(component -> pruneRegisteredTypes(component, keptNames))
                .toList();
        List<PackageDescriptor> packages = normalized.packages().stream()
                .map(p -> pruneRegisteredTypes(p, keptNames))
                .filter(p -> isPackageRelevant(p, keptNames))
                .toList();
        return new ComponentRegistry(normalized.sourceRoot(), packages, components).normalized();
    }

    private static boolean isAppModelRoot(ComponentDescriptor component) {
        return hasSemanticCapability(component.capabilities())
               || component.consumer() != null
               || !component.handlerRoutes().isEmpty()
               || !component.registeredTypes().isEmpty()
               || hasRelevantAnnotations(component);
    }

    private static boolean isStableComponentName(String fullClassName) {
        return !UNSTABLE_LOCAL_CLASS_NAME.matcher(fullClassName).matches();
    }

    private static boolean isAppModelRoot(PackageDescriptor descriptor) {
        return hasSemanticCapability(descriptor.capabilities())
               || descriptor.consumer() != null
               || !descriptor.registeredTypes().isEmpty()
               || hasRelevantAnnotations(descriptor.annotations());
    }

    private static boolean hasSemanticCapability(Set<ComponentCapability> capabilities) {
        return capabilities.stream().anyMatch(capability -> !STRUCTURAL_CAPABILITIES.contains(capability));
    }

    private static boolean hasRelevantAnnotations(ComponentDescriptor component) {
        if (hasRelevantAnnotations(component.annotations())) {
            return true;
        }
        if (component.properties().stream().anyMatch(property ->
                hasRelevantAnnotations(property.annotations()) || hasRelevantAnnotations(property.typeUse()))) {
            return true;
        }
        return component.executables().stream().anyMatch(executable ->
                hasRelevantAnnotations(executable.annotations())
                || hasRelevantAnnotations(executable.returnTypeUse())
                || executable.parameters().stream().anyMatch(parameter ->
                        hasRelevantAnnotations(parameter.annotations())
                        || hasRelevantAnnotations(parameter.typeUse())));
    }

    private static boolean hasRelevantAnnotations(TypeUseDescriptor typeUse) {
        if (typeUse == null) {
            return false;
        }
        return hasRelevantAnnotations(typeUse.annotations())
               || typeUse.typeArguments().stream().anyMatch(ComponentRegistryGenerator::hasRelevantAnnotations)
               || hasRelevantAnnotations(typeUse.componentType());
    }

    private static boolean hasRelevantAnnotations(Collection<AnnotationDescriptor> annotations) {
        return annotations.stream().anyMatch(ComponentRegistryGenerator::hasRelevantAnnotation);
    }

    private static boolean hasRelevantAnnotation(AnnotationDescriptor annotation) {
        return isRelevantAnnotationName(annotation.qualifiedName())
               || annotation.metaAnnotations().stream().anyMatch(ComponentRegistryGenerator::hasRelevantAnnotation)
               || annotation.nestedAnnotations().values().stream()
                       .flatMap(Collection::stream)
                       .anyMatch(ComponentRegistryGenerator::hasRelevantAnnotation);
    }

    private static boolean isRelevantAnnotationName(String qualifiedName) {
        return RELEVANT_ANNOTATION_PREFIXES.stream().anyMatch(qualifiedName::startsWith);
    }

    private static Stream<String> referencedTypeNames(ComponentDescriptor component) {
        List<String> result = new ArrayList<>();
        result.addAll(component.superTypeNames());
        component.annotations().forEach(annotation -> collectAnnotationTypeNames(annotation, result));
        component.properties().forEach(property -> {
            result.add(property.typeName());
            result.add(property.genericTypeName());
            property.annotations().forEach(annotation -> collectAnnotationTypeNames(annotation, result));
            collectTypeUseNames(property.typeUse(), result);
        });
        component.executables().forEach(executable -> {
            result.add(executable.returnTypeName());
            collectTypeUseNames(executable.returnTypeUse(), result);
            executable.annotations().forEach(annotation -> collectAnnotationTypeNames(annotation, result));
            executable.parameters().forEach(parameter -> {
                result.add(parameter.typeName());
                parameter.annotations().forEach(annotation -> collectAnnotationTypeNames(annotation, result));
                collectTypeUseNames(parameter.typeUse(), result);
            });
        });
        component.handlerRoutes().forEach(route -> {
            result.addAll(route.payloadTypeNames());
            result.addAll(route.allowedClassNames());
            route.executableMetadata().ifPresent(executable -> {
                result.add(executable.returnTypeName());
                executable.parameters().forEach(parameter -> result.add(parameter.typeName()));
            });
        });
        component.registeredTypes().stream()
                .flatMap(registeredType -> registeredType.candidateTypeNames().stream())
                .forEach(result::add);
        return result.stream()
                .flatMap(ComponentRegistryGenerator::candidateTypeNames)
                .filter(name -> !IGNORED_TYPE_NAMES.contains(name))
                .filter(name -> !name.startsWith("java."))
                .distinct();
    }

    private static void collectTypeUseNames(TypeUseDescriptor typeUse, List<String> result) {
        if (typeUse == null) {
            return;
        }
        result.add(typeUse.typeName());
        typeUse.annotations().forEach(annotation -> collectAnnotationTypeNames(annotation, result));
        typeUse.typeArguments().forEach(argument -> collectTypeUseNames(argument, result));
        collectTypeUseNames(typeUse.componentType(), result);
    }

    private static void collectAnnotationTypeNames(AnnotationDescriptor annotation, List<String> result) {
        annotation.attributes().values().stream().flatMap(Collection::stream).forEach(result::add);
        annotation.nestedAnnotations().values().stream()
                .flatMap(Collection::stream)
                .forEach(nested -> collectAnnotationTypeNames(nested, result));
        annotation.metaAnnotations().forEach(meta -> collectAnnotationTypeNames(meta, result));
    }

    private static Stream<String> candidateTypeNames(String rawName) {
        String name = cleanTypeName(rawName);
        if (name.isBlank()) {
            return Stream.empty();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(name);
        result.add(name.replace('$', '.'));
        if (name.contains(".")) {
            result.add(name.replace('.', '$'));
            int index = name.lastIndexOf('.');
            while (index > 0) {
                result.add(name.substring(0, index) + "$" + name.substring(index + 1));
                index = name.lastIndexOf('.', index - 1);
            }
        }
        return result.stream();
    }

    private static String cleanTypeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String name = rawName.strip();
        while (name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2);
        }
        if (GENERIC_TYPE_MARKER.matcher(name).find()) {
            return "";
        }
        return name;
    }

    private static void enqueue(
            String name, Map<String, ComponentDescriptor> componentsByName,
            LinkedHashMap<String, ComponentDescriptor> kept, Deque<String> queue) {
        candidateTypeNames(name)
                .map(componentsByName::get)
                .filter(Objects::nonNull)
                .filter(component -> isStableComponentName(component.fullClassName()))
                .findFirst()
                .ifPresent(component -> {
                    if (!kept.containsKey(component.fullClassName())) {
                        kept.put(component.fullClassName(), component);
                        queue.add(component.fullClassName());
                    }
                });
    }

    private static Set<String> allNames(Collection<ComponentDescriptor> components) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        components.forEach(component -> {
            result.add(component.fullClassName());
            result.add(component.fullClassName().replace('$', '.'));
        });
        return Set.copyOf(result);
    }

    private static ComponentDescriptor pruneRegisteredTypes(ComponentDescriptor component, Set<String> keptNames) {
        List<RegisteredTypeDescriptor> registeredTypes = pruneRegisteredTypes(component.registeredTypes(), keptNames);
        if (registeredTypes.equals(component.registeredTypes())) {
            return component;
        }
        return new ComponentDescriptor(
                component.sourceFile(), component.packageInfoSource(), component.componentKind(),
                component.packageName(), component.className(), component.superTypeNames(), component.annotations(),
                component.properties(), component.executables(), component.handlerRoutes(), registeredTypes,
                component.consumer(), component.capabilities());
    }

    private static PackageDescriptor pruneRegisteredTypes(PackageDescriptor descriptor, Set<String> keptNames) {
        List<RegisteredTypeDescriptor> registeredTypes = pruneRegisteredTypes(descriptor.registeredTypes(), keptNames);
        if (registeredTypes.equals(descriptor.registeredTypes())) {
            return descriptor;
        }
        return new PackageDescriptor(
                descriptor.packageName(), descriptor.sourceFile(), descriptor.annotations(), registeredTypes,
                descriptor.consumer(), descriptor.capabilities());
    }

    private static List<RegisteredTypeDescriptor> pruneRegisteredTypes(
            List<RegisteredTypeDescriptor> registeredTypes, Set<String> keptNames) {
        return registeredTypes.stream()
                .map(registeredType -> {
                    List<String> candidates = registeredType.candidateTypeNames().stream()
                            .filter(candidate -> candidateTypeNames(candidate).anyMatch(keptNames::contains))
                            .toList();
                    return new RegisteredTypeDescriptor(
                            registeredType.root(), registeredType.contains(), candidates, registeredType.annotation());
                })
                .toList();
    }

    private static boolean isPackageRelevant(PackageDescriptor descriptor, Set<String> keptNames) {
        return !descriptor.annotations().isEmpty()
               && (keptNames.stream().anyMatch(name -> name.startsWith(descriptor.packageName() + "."))
                   || !descriptor.registeredTypes().isEmpty()
                   || hasSemanticCapability(descriptor.capabilities()));
    }

    private static boolean hasFluxzeroHandler(Class<?> type) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        return hasFluxzeroHandler(type.getDeclaredAnnotations(), visited)
               || Stream.concat(List.of(type.getDeclaredMethods()).stream(),
                                List.of(type.getDeclaredConstructors()).stream())
                       .anyMatch(executable -> hasFluxzeroHandler(executable, new LinkedHashSet<>()));
    }

    private static boolean hasFluxzeroHandler(Executable executable, Set<Class<?>> visited) {
        return hasFluxzeroHandler(executable.getDeclaredAnnotations(), visited);
    }

    private static boolean hasFluxzeroHandler(Annotation[] annotations, Set<Class<?>> visited) {
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (HANDLER_ANNOTATIONS.contains(annotationType.getName())) {
                return true;
            }
            if (visited.add(annotationType)
                && hasFluxzeroHandler(annotationType.getDeclaredAnnotations(), visited)) {
                return true;
            }
        }
        return false;
    }
}
