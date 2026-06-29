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

package io.fluxzero.sdk.test;

import io.fluxzero.sdk.registry.ComponentRegistryGenerator;
import io.fluxzero.sdk.registry.ComponentRegistryJson;
import lombok.extern.slf4j.Slf4j;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A JUnit 5 {@link TestExecutionListener} that shuts down all active {@link TestFixture} instances after each test.
 * <p>
 * This ensures that Fluxzero resources such as schedulers, threads, or stateful components are properly
 * released between test executions.
 * <p>
 * This listener is useful in test environments that rely on side-effectful or stateful behaviors (e.g. schedules,
 * in-memory gateways, spies), especially when running multiple tests within the same JVM.
 *
 * <p><strong>Usage:</strong> Register this listener in your {@code junit-platform.properties} file:
 * <pre>{@code
 * junit.platform.listeners.default = io.fluxzero.sdk.test.TestFixtureExecutionListener
 * }</pre>
 *
 * @see TestFixture#shutDownActiveFixtures()
 * @see TestFixture
 */
@Slf4j
public class TestFixtureExecutionListener implements TestExecutionListener {
    private static final AtomicBoolean generatedMetadataPrepared = new AtomicBoolean();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        prepareGeneratedMetadataForIdeTestRuns(testPlan);
    }

    /**
     * Invoked automatically by the JUnit 5 test engine after each test case completes.
     * <p>
     * This will invoke {@link TestFixture#shutDownActiveFixtures()}, which closes all Fluxzero components
     * registered to the current thread.
     */
    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        TestFixture.shutDownActiveFixtures();
    }

    private static void prepareGeneratedMetadataForIdeTestRuns(TestPlan testPlan) {
        if (!generatedMetadataPrepared.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Path classRoot : classRoots(testPlan)) {
                prepareGeneratedMetadata(classRoot);
            }
        } catch (Exception | LinkageError e) {
            log.warn("Failed to prepare Fluxzero generated metadata for IDE test run", e);
        }
    }

    private static void prepareGeneratedMetadata(Path classRoot) {
        if (!Files.isDirectory(classRoot)) {
            return;
        }
        Path moduleRoot = moduleRoot(classRoot);
        if (moduleRoot == null) {
            return;
        }
        Path sourceRoot = sourceRoot(moduleRoot, classRoot);
        if (sourceRoot == null) {
            return;
        }
        generateIfMissingOrStale(sourceRoot, classRoot, classRoot.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));
    }

    private static void generateIfMissingOrStale(Path sourceRoot, Path classRoot, Path output) {
        if (!Files.isDirectory(sourceRoot)
            || !Files.isDirectory(classRoot)
            || isCurrent(output, sourceRoot, classRoot)) {
            return;
        }
        ComponentRegistryGenerator.generate(
                sourceRoot, classRoot, output, null, null, false, true, true);
    }

    private static boolean isCurrent(Path output, Path sourceRoot, Path classRoot) {
        if (!Files.isRegularFile(output)) {
            return false;
        }
        return newerThan(output, sourceRoot) && newerThan(output, classRoot);
    }

    private static boolean newerThan(Path output, Path inputRoot) {
        try {
            long outputTime = Files.getLastModifiedTime(output).toMillis();
            try (var files = Files.walk(inputRoot)) {
                return files.filter(Files::isRegularFile)
                        .mapToLong(path -> lastModified(path, outputTime))
                        .max()
                        .orElse(outputTime) <= outputTime;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long lastModified(Path path, long fallback) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Set<Path> classRoots(TestPlan testPlan) {
        Set<Path> result = new LinkedHashSet<>();
        addClassRoot(result, classRoot(ComponentRegistryGenerator.class));
        addClassRoot(result, classRoot(TestFixtureExecutionListener.class));
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                addClassRoot(result, Path.of(entry));
            }
        }
        testPlan.getRoots().forEach(root -> addTestPlanClassRoots(testPlan, root, result));
        return result;
    }

    private static void addTestPlanClassRoots(TestPlan testPlan, TestIdentifier identifier, Set<Path> roots) {
        identifier.getSource().ifPresent(source -> addClassRoot(roots, classRoot(source)));
        testPlan.getChildren(identifier).forEach(child -> addTestPlanClassRoots(testPlan, child, roots));
    }

    private static Path classRoot(TestSource source) {
        try {
            return switch (source) {
                case ClassSource classSource -> classRoot(classSource.getJavaClass());
                case MethodSource methodSource -> classRoot(Class.forName(
                        methodSource.getClassName(), false, Thread.currentThread().getContextClassLoader()));
                default -> null;
            };
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static void addClassRoot(Set<Path> roots, Path path) {
        if (path == null) {
            return;
        }
        Path root = path.toAbsolutePath().normalize();
        if (Files.isDirectory(root) && moduleRoot(root) != null) {
            roots.add(root);
        }
    }

    private static Path classRoot(Class<?> type) {
        try {
            URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path moduleRoot(Path classRoot) {
        for (Path current = classRoot; current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                || Files.isDirectory(current.resolve("src/main/java"))
                || Files.isDirectory(current.resolve("src/test/java"))) {
                return current;
            }
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(workingDirectory.resolve("pom.xml"))) {
            return workingDirectory;
        }
        return null;
    }

    private static Path sourceRoot(Path moduleRoot, Path classRoot) {
        String normalizedRoot = classRoot.toString().replace(File.separatorChar, '/');
        if (normalizedRoot.contains("/test-classes") || normalizedRoot.contains("/out/test/")) {
            Path sourceRoot = moduleRoot.resolve("src/test/java");
            return Files.isDirectory(sourceRoot) ? sourceRoot : null;
        }
        if (normalizedRoot.contains("/classes") || normalizedRoot.contains("/out/production/")) {
            Path sourceRoot = moduleRoot.resolve("src/main/java");
            return Files.isDirectory(sourceRoot) ? sourceRoot : null;
        }
        return null;
    }
}
