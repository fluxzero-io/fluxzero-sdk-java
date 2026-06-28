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
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
        prepareGeneratedMetadataForIdeTestRuns();
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

    private static void prepareGeneratedMetadataForIdeTestRuns() {
        if (!generatedMetadataPrepared.compareAndSet(false, true)) {
            return;
        }
        try {
            Path mainClassRoot = classRoot(ComponentRegistryGenerator.class);
            Path testClassRoot = classRoot(TestFixtureExecutionListener.class);
            if (mainClassRoot == null || testClassRoot == null
                || !Files.isDirectory(mainClassRoot) || !Files.isDirectory(testClassRoot)) {
                return;
            }
            Path moduleRoot = moduleRoot(mainClassRoot);
            if (moduleRoot == null || !Files.isDirectory(moduleRoot.resolve("src/main/java"))) {
                return;
            }
            generateIfMissingOrStale(
                    moduleRoot.resolve("src/main/java"),
                    mainClassRoot,
                    mainClassRoot.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));
            generateIfMissingOrStale(
                    moduleRoot.resolve("src/test/java"),
                    testClassRoot,
                    testClassRoot.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));
        } catch (Exception e) {
            log.warn("Failed to prepare Fluxzero generated metadata for IDE test run", e);
        }
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
            if (Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("src/main/java"))) {
            return workingDirectory;
        }
        Path sdkJvmModule = workingDirectory.resolve("sdk-jvm");
        return Files.isDirectory(sdkJvmModule.resolve("src/main/java")) ? sdkJvmModule : null;
    }
}
