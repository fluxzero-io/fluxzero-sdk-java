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
 *
 */

package io.fluxzero.common.reflection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PackageAnnotationsTest {
    private static final String ROOT = "io.fluxzero.common.reflection.packagefixture";
    @TempDir Path directory;

    @Test
    void findsParentAnnotationBehindAnEarlierUnannotatedDirectory() throws Exception {
        Path first = directory.resolve("first");
        Files.createDirectories(first.resolve(ROOT.replace('.', '/')));
        Path second = directory.resolve("second");
        for (String type : new String[]{ROOT + ".package-info", ROOT + ".child.Child"}) {
            Path target = second.resolve(type.replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, classBytes(type));
        }
        assertParentAnnotation(first.toUri().toURL(), second.toUri().toURL());
    }

    @Test
    void findsParentAnnotationInAJarWithoutDirectoryEntries() throws Exception {
        Path jar = directory.resolve("fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String type : new String[]{ROOT + ".package-info", ROOT + ".child.Child"}) {
                output.putNextEntry(new JarEntry(type.replace('.', '/') + ".class"));
                output.write(classBytes(type));
                output.closeEntry();
            }
        }
        assertParentAnnotation(jar.toUri().toURL());
    }

    private void assertParentAnnotation(URL... fixtures) throws Exception {
        var urls = new LinkedHashSet<>(Arrays.asList(fixtures));
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            URL url = Path.of(entry).toUri().toURL();
            if (!url.equals(getClass().getProtectionDomain().getCodeSource().getLocation())) urls.add(url);
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> child = loader.loadClass(ROOT + ".child.Child");
            assertNull(loader.getDefinedPackage(ROOT), "The parent package must still be cold");
            Class<?> reflection = loader.loadClass(ReflectionUtils.class.getName());
            var lookup = reflection.getMethod("getPackageAnnotation", Package.class, Class.class);
            for (int i = 0; i < 2; i++) {
                var annotation = (Optional<?>) lookup.invoke(null, child.getPackage(), Deprecated.class);
                assertEquals("parent", ((Deprecated) annotation.orElseThrow()).since());
            }
            assertNotNull(loader.getDefinedPackage(ROOT));
        }
    }

    private byte[] classBytes(String type) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(type.replace('.', '/') + ".class")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
}
