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

package io.fluxzero.common.handling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedExecutableInvocationsTest {

    @Test
    void restoresPreviousInvocationWhenLatestRegistrationCloses() {
        String executableId = "METHOD:handle()";

        try (var first = GeneratedExecutableInvocations.register(
                Handler.class, executableId, (target, parameterCount, parameterProvider) -> "first")) {
            assertEquals("first", GeneratedExecutableInvocations.find(Handler.class, executableId)
                    .orElseThrow().invoke(null));

            try (var second = GeneratedExecutableInvocations.register(
                    Handler.class, executableId, (target, parameterCount, parameterProvider) -> "second")) {
                assertEquals("second", GeneratedExecutableInvocations.find(Handler.class, executableId)
                        .orElseThrow().invoke(null));
            }

            assertEquals("first", GeneratedExecutableInvocations.find(Handler.class, executableId)
                    .orElseThrow().invoke(null));
        }

        assertTrue(GeneratedExecutableInvocations.find(Handler.class, executableId).isEmpty());
    }

    @Test
    void isolatesSameClassNameLoadedByDifferentClassLoaders(@TempDir Path tempDir) throws Exception {
        String executableId = "METHOD:handle()";
        Path classes = compileSameNameClass(tempDir);

        try (URLClassLoader firstLoader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, GeneratedExecutableInvocationsTest.class.getClassLoader());
             URLClassLoader secondLoader = new URLClassLoader(
                     new URL[]{classes.toUri().toURL()}, GeneratedExecutableInvocationsTest.class.getClassLoader())) {
            Class<?> firstType = Class.forName("example.SameName", true, firstLoader);
            Class<?> secondType = Class.forName("example.SameName", true, secondLoader);

            assertNotSame(firstType, secondType);

            try (var first = GeneratedExecutableInvocations.register(
                    firstType, executableId, (target, parameterCount, parameterProvider) -> "first");
                 var second = GeneratedExecutableInvocations.register(
                         secondType, executableId, (target, parameterCount, parameterProvider) -> "second")) {
                assertEquals("first", GeneratedExecutableInvocations.find(firstType, executableId)
                        .orElseThrow().invoke(null));
                assertEquals("second", GeneratedExecutableInvocations.find(secondType, executableId)
                        .orElseThrow().invoke(null));
            }
        }
    }

    private static Path compileSameNameClass(Path tempDir) throws Exception {
        Path source = tempDir.resolve("src/example/SameName.java");
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                package example;

                public class SameName {
                }
                """);
        int exitCode = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString());
        assertEquals(0, exitCode);
        return classes;
    }

    private static class Handler {
    }
}
