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

package io.fluxzero.sdk.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkBrowserDependencyGuardTest {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "java.io.",
            "java.lang.reflect.",
            "java.net.",
            "java.nio.file.",
            "java.util.ServiceLoader",
            "javax.tools.",
            "org.springframework.",
            "com.fasterxml.jackson.",
            "org.slf4j.",
            "ch.qos.logback.");

    @Test
    void sdkBrowserDoesNotImportJvmOnlyApis() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        try (var files = Files.walk(sourceRoot)) {
            List<String> violations = files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> imports(path).stream().map(line -> path + ": " + line))
                    .filter(SdkBrowserDependencyGuardTest::forbidden)
                    .toList();
            assertTrue(violations.isEmpty(), () -> "Forbidden sdk-browser imports:\n" + String.join("\n", violations));
        }
    }

    private static List<String> imports(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("import "))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static boolean forbidden(String importLine) {
        return FORBIDDEN_IMPORTS.stream().anyMatch(importLine::contains);
    }
}
