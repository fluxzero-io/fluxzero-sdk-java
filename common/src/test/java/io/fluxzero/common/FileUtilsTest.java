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

package io.fluxzero.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileUtilsTest {
    @TempDir
    Path tempDir;

    @Test
    void mergesApplicationPropertiesFromApplicationAndDependencyClasspathRoots() throws Exception {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("application"));
        Path dependencyRoot = Files.createDirectory(tempDir.resolve("dependency"));
        Files.writeString(applicationRoot.resolve("application.properties"), """
                application.only=application
                shared=application
                """);
        Files.writeString(dependencyRoot.resolve("application.properties"), """
                dependency.only=dependency
                shared=dependency
                """);

        try (var classLoader = new URLClassLoader(
                new URL[]{applicationRoot.toUri().toURL(), dependencyRoot.toUri().toURL()}, null)) {
            var properties = FileUtils.loadProperties("application.properties", classLoader);

            assertEquals("application", properties.getProperty("application.only"));
            assertEquals("dependency", properties.getProperty("dependency.only"));
            assertEquals("application", properties.getProperty("shared"));
        }
    }
}
