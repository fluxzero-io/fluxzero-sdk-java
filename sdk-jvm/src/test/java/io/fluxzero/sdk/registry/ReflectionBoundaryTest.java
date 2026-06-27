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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionBoundaryTest {
    private static final Pattern DIRECT_REFLECTION_UTILS = Pattern.compile("\\bReflectionUtils\\b");
    private static final Pattern DIRECT_REFLECTION_ACCESS = Pattern.compile("\\bReflectionAccess\\b");
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

    private static boolean containsForbiddenReflectionReference(Path path) {
        if (path.endsWith(Path.of("io", "fluxzero", "sdk", "registry", "JvmComponentIntrospector.java"))) {
            return false;
        }
        try {
            String source = Files.readString(path);
            return DIRECT_REFLECTION_UTILS.matcher(source).find()
                   || DIRECT_REFLECTION_ACCESS.matcher(source).find();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }

    private static boolean isRuntimeMetadataScanDebtCandidate(Path path) {
        return !path.endsWith(Path.of("io", "fluxzero", "sdk", "registry", "ComponentMetadataLookups.java"))
               && !path.endsWith(Path.of("io", "fluxzero", "sdk", "registry", "JvmComponentMetadataLookup.java"));
    }

    private static long directMetadataScanCount(Path path) {
        try {
            return DIRECT_METADATA_SCAN.matcher(Files.readString(path)).results().count();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
