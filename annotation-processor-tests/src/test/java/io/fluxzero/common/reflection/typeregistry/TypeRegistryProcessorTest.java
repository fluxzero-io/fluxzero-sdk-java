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

package io.fluxzero.common.reflection.typeregistry;

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.reflection.typeregistry.bar.Bar;
import io.fluxzero.common.reflection.typeregistry.empty.inner.ChildOfEmpty;
import io.fluxzero.common.serialization.TypeRegistryProcessor;
import org.joor.CompileOptions;
import org.joor.Reflect;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.FileObject;
import javax.tools.ForwardingFileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeRegistryProcessorTest {

    @Test
    void classForName() {
        assertThrows(ClassNotFoundException.class, () -> ReflectionUtils.classForName("Foo"));
        Assertions.assertEquals(Foo.class, ReflectionUtils.classForName(Foo.class.getName()));
        Assertions.assertEquals(Bar.class, ReflectionUtils.classForName("Bar"));
        Assertions.assertEquals(FooBar.class, ReflectionUtils.classForName("FooBar"));
        Assertions.assertEquals(FooBar.class, ReflectionUtils.classForName("typeregistry.FooBar"));
        Assertions.assertEquals(ChildOfEmpty.class, ReflectionUtils.classForName("ChildOfEmpty"));
    }

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incrementalCompilationPreservesExistingTypesWithoutReliableTimestamps(boolean zeroTimestamps) throws Exception {
        Path output = temporary.resolve("classes");
        compile(output, "First", """
                package sample;
                @io.fluxzero.common.serialization.RegisterType
                public class First { public static class Nested {} }
                """, zeroTimestamps);
        compile(output, "Second", """
                package sample;
                @io.fluxzero.common.serialization.RegisterType public class Second {}
                """, zeroTimestamps);
        assertEquals(List.of("sample.First", "sample.First$Nested", "sample.Second"),
                Files.readAllLines(output.resolve(TypeRegistryProcessor.TYPES_FILE)));
        Files.delete(output.resolve("sample/First$Nested.class"));
        compile(output, "Third", """
                package sample;
                @io.fluxzero.common.serialization.RegisterType public class Third {}
                """, zeroTimestamps);
        assertEquals(List.of("sample.First", "sample.Second", "sample.Third"),
                Files.readAllLines(output.resolve(TypeRegistryProcessor.TYPES_FILE)));
    }

    private void compile(Path output, String name, String source, boolean zeroTimestamps) throws IOException {
        Files.createDirectories(output);
        Path file = temporary.resolve(name + ".java");
        Files.writeString(file, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var standard = compiler.getStandardFileManager(null, null, null);
             var manager = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                 private FileObject wrap(FileObject file) {
                     return !zeroTimestamps ? file : new ForwardingFileObject<>(file) {
                         @Override public long getLastModified() { return 0L; }
                     };
                 }
                 @Override public FileObject getFileForInput(Location location, String pkg, String relative)
                         throws IOException {
                     return wrap(super.getFileForInput(location, pkg, relative));
                 }
                 @Override public FileObject getFileForOutput(Location location, String pkg, String relative,
                                                               FileObject sibling) throws IOException {
                     return wrap(super.getFileForOutput(location, pkg, relative, sibling));
                 }
                 @Override public FileObject getFileForOutputForOriginatingFiles(
                         Location location, String pkg, String relative, FileObject... origins) throws IOException {
                     return wrap(super.getFileForOutputForOriginatingFiles(location, pkg, relative, origins));
                 }
             }) {
            // Existing output is intentionally absent from the classpath, as in incremental IDE compilation.
            var task = compiler.getTask(null, manager, null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                    null, standard.getJavaFileObjects(file));
            task.setProcessors(List.of(new TypeRegistryProcessor()));
            assertTrue(task.call());
        }
    }

    @Test
    @Disabled
    void testCompilation() {
        TypeRegistryProcessor p = new TypeRegistryProcessor();
        Reflect.compile(
                "io.fluxzero.common.reflection.typeregistry.SomeHandler",
                """
                            package io.fluxzero.common.reflection.test;
                            @io.fluxzero.common.serialization.RegisterType
                            public class SomeHandler {
                            }
                        """, new CompileOptions().processors(p)
        );
    }

}
