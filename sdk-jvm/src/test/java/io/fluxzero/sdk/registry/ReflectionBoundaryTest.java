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

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionBoundaryTest {
    private static final Pattern DIRECT_REFLECTION_UTILS = Pattern.compile("\\bReflectionUtils\\b");
    private static final Pattern DIRECT_REFLECTION_ACCESS = Pattern.compile("\\bReflectionAccess\\b");
    private static final Pattern DIRECT_DEFAULT_MEMBER_INVOKER = Pattern.compile("\\bDefaultMemberInvoker\\b");
    private static final Pattern DIRECT_JVM_COMPONENT_INTROSPECTOR = Pattern.compile(
            "\\bJvmComponentIntrospector\\s*\\.\\s*getInstance\\s*\\(");
    private static final Pattern DIRECT_METADATA_SCAN = Pattern.compile(
            "\\bJvmComponentMetadataLookup\\s*\\.\\s*(scan|scanIfScannable)\\s*\\("
            + "|new\\s+ClasspathComponentScanner\\s*\\(\\s*\\)\\s*\\.\\s*scan\\s*\\(");
    private static final Map<String, Long> KNOWN_DIRECT_METADATA_SCAN_SITES = new TreeMap<>();

    @Test
    void sdkJvmMainOnlyUsesReflectionBackendInJvmComponentIntrospector() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (var files = Files.walk(sourceRoot)) {
            var offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(ReflectionBoundaryTest::containsForbiddenReflectionReference)
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertTrue(offenders.isEmpty(), () -> "Direct reflection backend references remain: " + offenders);
        }
    }

    @Test
    void directJvmMetadataScannerFallbackSitesAreExplicitDebt() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (var files = Files.walk(sourceRoot)) {
            Map<String, Long> offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(ReflectionBoundaryTest::isRuntimeMetadataScanDebtCandidate)
                    .map(path -> Map.entry(path.toString(), directMetadataScanCount(path)))
                    .filter(entry -> entry.getValue() > 0)
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue,
                                   Long::sum, TreeMap::new));

            assertEquals(KNOWN_DIRECT_METADATA_SCAN_SITES, offenders,
                         "Direct JVM metadata scanner fallbacks changed. New sites must be removed or consciously "
                         + "added to the generated metadata runtime parity backlog. Removed sites should lower the "
                         + "known debt count.");
        }
    }

    @Test
    void directJvmIntrospectionSitesAreGeneratedOnlyBackendCategories() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (var files = Files.walk(sourceRoot)) {
            var offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(ReflectionBoundaryTest::hasDirectJvmIntrospectorAccess)
                    .map(ReflectionBoundaryTest::className)
                    .filter(className -> JvmBackendAccess.category(className).isEmpty())
                    .sorted()
                    .toList();
            assertTrue(offenders.isEmpty(), () -> "Unclassified JVM introspection sites remain: " + offenders);
        }
    }

    @Test
    void generatedOnlyBackendMigrationDebtIsExplicit() {
        Map<String, JvmBackendAccess.BackendCategory> debt = JvmBackendAccess.migrationDebtClasses();

        assertEquals(42, debt.size(),
                     () -> "Generated-only JVM backend migration debt changed. Lower this count when a semantic "
                           + "fallback is replaced by generated metadata/invocation/access plans; add new debt only "
                           + "with an explicit Generated-Only Runtime Closure slice.");
        assertTrue(debt.containsKey("io.fluxzero.common.handling.HandlerInspector"));
        assertTrue(debt.containsKey("io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory"));
        assertTrue(debt.containsKey("io.fluxzero.sdk.modeling.AnnotatedEntityHolder"));
        assertTrue(debt.containsKey("io.fluxzero.sdk.web.WebHandlerMatcher"));
        assertEquals(JvmBackendAccess.BackendStatus.PLATFORM_BACKEND,
                     JvmBackendAccess.classification("io.fluxzero.sdk.registry.GeneratedRegistryBridge")
                             .orElseThrow().status());
        assertEquals(JvmBackendAccess.BackendStatus.PLATFORM_BACKEND,
                     JvmBackendAccess.classification("io.fluxzero.sdk.Fluxzero").orElseThrow().status());
    }

    @Test
    void strictGeneratedOnlyModeRejectsMigrationDebtBackendCategories() {
        assertThrows(ComponentRegistryException.class, () ->
                GeneratedOnlyMetadataMode.runStrict(() -> JvmBackendAccess.assertAllowed(
                        "io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory")));

        GeneratedOnlyMetadataMode.runStrict(() -> JvmBackendAccess.assertAllowed(
                "io.fluxzero.sdk.registry.GeneratedRegistryBridge"));
    }

    @Test
    void jakartaValidationUsesOnlyItsJvmBackendForDirectIntrospection() throws Exception {
        Path sourceRoot = Path.of("src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (var files = Files.walk(sourceRoot)) {
            var offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("JakartaValidationBackend.java"))
                    .filter(ReflectionBoundaryTest::hasDirectJvmIntrospectorAccess)
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertTrue(offenders.isEmpty(), () -> "Jakarta validation must use JakartaValidationBackend: "
                                                 + offenders);
        }
    }

    private static boolean containsForbiddenReflectionReference(Path path) {
        if (path.endsWith(Path.of("io", "fluxzero", "sdk", "registry", "JvmComponentIntrospector.java"))) {
            return false;
        }
        try {
            String source = Files.readString(path);
            return DIRECT_REFLECTION_UTILS.matcher(source).find()
                   || DIRECT_REFLECTION_ACCESS.matcher(source).find()
                   || DIRECT_DEFAULT_MEMBER_INVOKER.matcher(source).find();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }

    private static boolean isRuntimeMetadataScanDebtCandidate(Path path) {
        return !path.endsWith(Path.of("io", "fluxzero", "sdk", "registry", "ComponentMetadataLookups.java"))
               && !path.endsWith(Path.of("io", "fluxzero", "sdk", "registry", "JvmComponentMetadataLookup.java"))
               && !path.endsWith(Path.of("io", "fluxzero", "sdk", "registry", "ComponentRegistryGenerator.java"));
    }

    private static long directMetadataScanCount(Path path) {
        try {
            return DIRECT_METADATA_SCAN.matcher(Files.readString(path)).results().count();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }

    private static boolean hasDirectJvmIntrospectorAccess(Path path) {
        try {
            return DIRECT_JVM_COMPONENT_INTROSPECTOR.matcher(Files.readString(path)).find();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }

    private static String className(Path path) {
        try {
            String source = Files.readString(path);
            String packageName = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;")
                    .matcher(source).results()
                    .map(result -> result.group(1))
                    .findFirst().orElse("");
            String fileName = path.getFileName().toString().replaceFirst("\\.java$", "");
            return packageName.isBlank() ? fileName : packageName + "." + fileName;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
