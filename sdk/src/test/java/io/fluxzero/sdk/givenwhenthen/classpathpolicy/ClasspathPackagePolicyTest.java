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

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthenticatedException;
import io.fluxzero.sdk.tracking.handling.validation.ValidationUtils;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathPackagePolicyTest {
    @TempDir
    static Path directory;
    private static Path firstJar;
    private static Path secondJar;
    private static Path ancestorJar;
    private static final String classpath = System.getProperty("surefire.test.class.path",
                                                              System.getProperty("java.class.path"));

    @BeforeAll
    static void packageModules() throws Exception {
        firstJar = compileJar("first", "package-info.java", "RootMarker.java", "child/package-info.java",
                              "child/FirstModule.java");
        ancestorJar = compileJar("second", "package-info.java", "PrivateMarker.java", "child/SecondModule.java");
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

    @Test
    void applicationLoaderFindsAncestorPolicyWhenSdkIsInParentLoader() throws Exception {
        try (var loader = new URLClassLoader(new URL[]{ancestorJar.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> payload = loader.loadClass("example.classpathpolicy.child.SecondModule$Request");
            Class<?> handler = loader.loadClass("example.classpathpolicy.child.SecondModule$Handler");
            assertNull(loader.getDefinedPackage("example.classpathpolicy"));
            assertThrows(UnauthenticatedException.class, () -> ValidationUtils.assertAuthorized(payload, null));
            assertThrows(UnauthenticatedException.class,
                         () -> ValidationUtils.assertAuthorized(handler, handler.getDeclaredMethod("handle", String.class), null));
            assertTrue(ValidationUtils.assertAuthorized(payload, new MockUser()));
            assertEquals("second", ReflectionUtils.getPackageAndParentPackages(payload.getPackage(), payload.getModule()).stream()
                    .filter(p -> p.getName().equals("example.classpathpolicy")).findFirst().orElseThrow()
                    .getAnnotation(Deprecated.class).since());
        }
    }

    @Test
    void siblingApplicationLoadersDoNotSharePoliciesOrUseTheContextLoader() throws Exception {
        try (var publicLoader = new URLClassLoader(new URL[]{firstJar.toUri().toURL()}, getClass().getClassLoader());
             var privateLoader = new URLClassLoader(new URL[]{ancestorJar.toUri().toURL()}, getClass().getClassLoader())) {
            var publicPayload = publicLoader.loadClass("example.classpathpolicy.child.FirstModule$Request");
            var privatePayload = privateLoader.loadClass("example.classpathpolicy.child.SecondModule$Request");
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(publicLoader);
                assertThrows(UnauthenticatedException.class, () -> ValidationUtils.assertAuthorized(privatePayload, null));
                Thread.currentThread().setContextClassLoader(privateLoader);
                assertTrue(ValidationUtils.assertAuthorized(publicPayload, null));
                assertThrows(UnauthenticatedException.class, () -> ValidationUtils.assertAuthorized(privatePayload, null));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Test
    void ancestorPolicyDoesNotDependOnWarmingAnUnrelatedClass() throws Exception {
        for (boolean privateChild : new boolean[]{true, false}) {
            for (boolean warm : new boolean[]{false, true}) {
                Path parentJar = privateChild ? firstJar : ancestorJar;
                Path childJar = privateChild ? ancestorJar : firstJar;
                try (var parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
                     var child = new URLClassLoader(new URL[]{childJar.toUri().toURL()}, parent)) {
                    Class<?> payload = child.loadClass("example.classpathpolicy.child."
                                                      + (privateChild ? "SecondModule" : "FirstModule") + "$Request");
                    if (warm) {
                        child.loadClass("example.classpathpolicy." + (privateChild ? "PrivateMarker" : "RootMarker"));
                    }
                    if (privateChild) {
                        assertThrows(UnauthenticatedException.class, () -> ValidationUtils.assertAuthorized(payload, null));
                    } else {
                        assertTrue(ValidationUtils.assertAuthorized(payload, null));
                    }
                }
            }
        }
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
        String outputName = module + "-" + Integer.toUnsignedString(java.util.Arrays.hashCode(resources));
        Path sources = directory.resolve(outputName + "-sources");
        Path classes = Files.createDirectories(directory.resolve(outputName + "-classes"));
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
        Path jar = directory.resolve(outputName + ".jar");
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
