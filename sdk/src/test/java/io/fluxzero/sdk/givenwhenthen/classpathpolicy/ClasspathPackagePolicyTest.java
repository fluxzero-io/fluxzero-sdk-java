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

package io.fluxzero.sdk.givenwhenthen.classpathpolicy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClasspathPackagePolicyTest {
    @TempDir
    static Path directory;
    private static Path firstJar;
    private static Path secondJar;
    private static final String classpath = System.getProperty("surefire.test.class.path",
                                                              System.getProperty("java.class.path"));

    @BeforeAll
    static void packageModules() throws Exception {
        firstJar = compileJar("first", "package-info.java", "RootMarker.java", "child/package-info.java",
                              "child/FirstModule.java");
        secondJar = compileJar("second", "package-info.java", "child/package-info.java", "child/SecondModule.java");
    }

    @Test
    void firstJarWinsForBothModulesWithAnUnloadedParentPackage() throws Throwable {
        verify(firstJar, secondJar, "first", "FirstModule", false, false);
    }

    @Test
    void firstJarStillWinsWhenTheOtherModuleAndParentPackageAreLoadedFirst() throws Throwable {
        verify(firstJar, secondJar, "first", "SecondModule", true, false);
    }

    @Test
    void reversingTheClasspathSelectsTheSecondJarForBothModules() throws Throwable {
        verify(secondJar, firstJar, "second", "SecondModule", false, false);
    }

    @Test
    void secondJarStillWinsWhenTheOtherModuleAndParentPackageAreLoadedFirst() throws Throwable {
        verify(secondJar, firstJar, "second", "FirstModule", true, false);
    }

    @Test
    void asynchronousHandlingUsesClasspathPolicyAndRechecksTheCurrentUser() throws Throwable {
        verify(secondJar, firstJar, "second", "FirstModule", true, true);
    }

    private void verify(Path first, Path second, String expected, String firstModule,
                        boolean preloadParent, boolean asynchronous) throws Throwable {
        var urls = new ArrayList<URL>(List.of(first.toUri().toURL(), second.toUri().toURL()));
        for (String entry : classpath.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                loader.loadClass(ClasspathPackagePolicyScenario.class.getName())
                        .getMethod("verify", String.class, String.class, boolean.class, boolean.class)
                        .invoke(null, expected, firstModule, preloadParent, asynchronous);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    private static Path compileJar(String module, String... resources) throws Exception {
        Path sources = directory.resolve(module + "-sources");
        Path classes = Files.createDirectories(directory.resolve(module + "-classes"));
        var arguments = new ArrayList<>(List.of("-proc:none", "-classpath", classpath, "-d", classes.toString()));
        for (String resource : resources) {
            Path source = sources.resolve(resource);
            Files.createDirectories(source.getParent());
            try (var input = ClasspathPackagePolicyTest.class.getResourceAsStream("/classpathpolicy/" + module + "/" + resource)) {
                assertNotNull(input);
                Files.copy(input, source);
            }
            arguments.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        Path jar = directory.resolve(module + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
