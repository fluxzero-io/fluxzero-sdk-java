/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTypeProcessorTest {
    @TempDir
    Path temporary;

    @Test
    void indexesModelsWithoutRegisterTypeAndPreservesUnchangedClassesOnIncrementalCompilation() throws Exception {
        Path output = temporary.resolve("classes");
        compile(output, "sample.Contracts", """
                package sample;
                import io.fluxzero.sdk.modeling.*;
                public class Contracts {
                    @Model public record Order(@EntityId String id) {}
                    @Model public interface InterfaceModel { String id(); }
                    public record Implementation(@EntityId String id) implements InterfaceModel {}
                    public record NotAModel(String id) {}
                }
                """);
        assertEquals(List.of("sample.Contracts$Implementation", "sample.Contracts$InterfaceModel", "sample.Contracts$Order"),
                     Files.readAllLines(output.resolve(ModelTypes.INDEX)));
        assertFalse(Files.exists(output.resolve("META-INF/io.fluxzero.common.serialization.TypeRegistry")));
        compile(output, "sample.Additional", """
                package sample;
                import io.fluxzero.sdk.modeling.*;
                @Model public record Additional(@EntityId String id) {}
                """);
        assertEquals(List.of("sample.Additional", "sample.Contracts$Implementation", "sample.Contracts$InterfaceModel",
                             "sample.Contracts$Order"),
                     Files.readAllLines(output.resolve(ModelTypes.INDEX)));
        compile(output, "sample.Additional", "package sample; public record Additional(String id) {}");
        assertEquals(List.of("sample.Contracts$Implementation", "sample.Contracts$InterfaceModel", "sample.Contracts$Order"),
                     Files.readAllLines(output.resolve(ModelTypes.INDEX)));
    }

    @Test
    void indexesInheritedModelsAndAbstractContracts() throws Exception {
        Path base = temporary.resolve("base");
        compile(base, "sample.Base", """
                package sample;
                @io.fluxzero.sdk.modeling.Model
                public abstract class Base { @io.fluxzero.sdk.modeling.EntityId String id; }
                """);
        Path child = temporary.resolve("child");
        compile(child, "sample.Child", "package sample; public final class Child extends Base {}", base);
        assertEquals(List.of("sample.Child"), Files.readAllLines(child.resolve(ModelTypes.INDEX)));
        assertEquals(List.of("sample.Base"), Files.readAllLines(base.resolve(ModelTypes.INDEX)));
    }

    @Test
    void coldReaderCombinesContractJarsWithoutRegisteringTheirHandlers() throws Exception {
        Path first = temporary.resolve("first"), second = temporary.resolve("second"), reader = temporary.resolve("reader");
        compile(first, "sample.Order", """
                package sample;
                import io.fluxzero.sdk.modeling.*;
                @Model(name="purchase") public record Order(@EntityId String id) {
                    static { System.setProperty("model.discovery.initialized", "yes"); }
                }
                """);
        compile(second, "sample.LineItem", """
                package sample;
                import io.fluxzero.sdk.modeling.*;
                @Model public record LineItem(@EntityId String id) {}
                """);
        compile(reader, "sample.Reader", """
                package sample;
                import io.fluxzero.sdk.configuration.DefaultFluxzero;
                import io.fluxzero.sdk.modeling.*;
                import io.fluxzero.sdk.persisting.repository.ModelTypeResolver;
                public class Reader {
                    public static void main(String[] args) {
                        try (var app = DefaultFluxzero.builder().build(
                                io.fluxzero.sdk.configuration.client.LocalClient.newInstance())) {
                            var resolver = (ModelTypeResolver) app.modelRepository();
                            if (resolver.modelType("purchase", "order") != Order.class
                                || resolver.modelType("LineItem", "line") != LineItem.class) {
                                throw new AssertionError("Model catalog failed");
                            }
                            if (System.getProperty("model.discovery.initialized") != null) {
                                throw new AssertionError("Discovery initialized a Model");
                            }
                            System.out.println("independent-model-catalog-ok");
                        }
                    }
                }
                """, first, second);
        runReader(reader, jar(first), jar(second));
        runReader(reader, mergedJar(first, second));
    }

    @Test
    void coldReaderResolvesDeletedAbstractContractsAndSkipsIdentityLessTemplates() throws Exception {
        Path contracts = temporary.resolve("contracts"), reader = temporary.resolve("reader");
        compile(contracts, "sample.Contracts", """
                package sample;
                import io.fluxzero.sdk.modeling.*;
                import io.fluxzero.sdk.persisting.eventsourcing.Apply;
                public class Contracts {
                    @Model public static abstract class Base {
                        @EntityId public final String id;
                        protected Base(String id) { this.id = id; }
                    }
                    public static final class Concrete extends Base {
                        public Concrete(String id) { super(id); }
                    }
                    @Model public interface Contract { @EntityId String id(); }
                    @Model public interface Template {}
                    public record DeleteBase(String id) {
                        @Apply(eventPublication=EventPublication.ALWAYS) Base apply() { return null; }
                    }
                    public record DeleteContract(String id) {
                        @Apply(eventPublication=EventPublication.ALWAYS) Contract apply() { return null; }
                    }
                    public record Create(String id) {
                        @Apply Base apply() { return new Concrete(id); }
                    }
                }
                """);
        compile(reader, "sample.Reader", """
                package sample;
                import io.fluxzero.sdk.common.Message;
                import io.fluxzero.sdk.configuration.DefaultFluxzero;
                import io.fluxzero.sdk.configuration.client.LocalClient;
                import io.fluxzero.sdk.modeling.*;
                import io.fluxzero.sdk.persisting.repository.ModelTypeResolver;
                public class Reader {
                    public static void main(String[] args) {
                        var client = LocalClient.newInstance();
                        try (var writer = DefaultFluxzero.builder().build(client)) {
                            for (Object command : new Object[] {new Contracts.DeleteBase("base"),
                                    new Contracts.DeleteContract("contract"), new Contracts.Create("concrete")}) {
                                writer.executeModelCommit(new Message(command)).join();
                            }
                            try (var reader = DefaultFluxzero.builder().build(client)) {
                                var resolver = (ModelTypeResolver) reader.modelRepository();
                                if (resolver.modelType("Base", "base") != Contracts.Base.class
                                    || resolver.modelType("Contract", "contract") != Contracts.Contract.class
                                    || ModelTypes.discover().contains(Contracts.Template.class)) {
                                    throw new AssertionError("Abstract contract catalog is incorrect");
                                }
                                var base = reader.modelRepository().load("base", Object.class);
                                var contract = reader.modelRepository().load("contract", Object.class);
                                var concrete = reader.modelRepository().load("concrete", Object.class);
                                if (base.isPresent() || !base.type().equals(Contracts.Base.class)
                                    || contract.isPresent() || !contract.type().equals(Contracts.Contract.class)
                                    || !concrete.isPresent() || !concrete.type().equals(Contracts.Concrete.class)) {
                                    throw new AssertionError("Persisted contract names changed");
                                }
                                System.out.println("independent-model-catalog-ok");
                            }
                        }
                    }
                }
                """, contracts);
        runReader(reader, jar(contracts));
    }

    @Test
    void automaticDiscoveryPreservesClaimingProcessorsRegardlessOfJarOrder() throws Exception {
        Path contract = temporary.resolve("contract");
        compile(contract, "sample.Contract", """
                package sample;
                @io.fluxzero.sdk.modeling.Model
                public interface Contract { @io.fluxzero.sdk.modeling.EntityId String id(); }
                """);
        Path common = Path.of(io.fluxzero.common.modeling.ModelTypeProcessor.class.getProtectionDomain()
                                     .getCodeSource().getLocation().toURI());
        Path sdk = Path.of(ModelTypeProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (boolean commonFirst : List.of(false, true)) {
            Path output = temporary.resolve("order-" + commonFirst), reader = temporary.resolve("reader-" + commonFirst);
            Path[] dependencies = {contract, commonFirst ? common : sdk, commonFirst ? sdk : common};
            compileWithProcessors(null,
                    output, "sample.RegisteredChild", """
                    package sample;
                    @io.fluxzero.common.serialization.RegisterType
                    public abstract class RegisteredChild implements Contract {}
                    """, dependencies);
            assertEquals(List.of("sample.RegisteredChild"), Files.readAllLines(output.resolve(ModelTypes.INDEX)));
            assertEquals(List.of("sample.RegisteredChild"), Files.readAllLines(output.resolve(
                    io.fluxzero.common.serialization.TypeRegistryProcessor.TYPES_FILE)));
            compileWithProcessors(null,
                    output, "sample.WebChild", """
                    package sample;
                    public abstract class WebChild implements Contract {
                        public void query(@io.fluxzero.sdk.web.QueryParam String value) {}
                    }
                    """, dependencies);
            assertEquals(List.of("sample.RegisteredChild", "sample.WebChild"),
                         Files.readAllLines(output.resolve(ModelTypes.INDEX)));
            assertTrue(Files.exists(output.resolve("sample/WebChild_params.class")));
            compile(reader, "sample.Reader", """
                    package sample;
                    import io.fluxzero.sdk.configuration.DefaultFluxzero;
                    import io.fluxzero.sdk.configuration.client.LocalClient;
                    import io.fluxzero.sdk.persisting.repository.ModelTypeResolver;
                    public class Reader {
                        public static void main(String[] args) {
                            try (var app = DefaultFluxzero.builder().build(LocalClient.newInstance())) {
                                var resolver = (ModelTypeResolver) app.modelRepository();
                                if (resolver.modelType("RegisteredChild", "id") != RegisteredChild.class
                                    || resolver.modelType("WebChild", "id") != WebChild.class) {
                                    throw new AssertionError("A preceding processor hid inherited Models");
                                }
                                System.out.println("independent-model-catalog-ok");
                            }
                        }
                    }
                    """, contract, output);
            runReader(reader, jar(contract), jar(output));
        }
    }

    private void runReader(Path reader, Path... contracts) throws Exception {
        Path log = temporary.resolve("reader.log");
        List<Path> inputs = new ArrayList<>(List.of(contracts));
        inputs.add(reader);
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                             "-cp", classpath(inputs.toArray(Path[]::new)), "sample.Reader")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Reader did not terminate");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("independent-model-catalog-ok"));
        } finally {
            process.destroyForcibly();
        }
    }

    private void compile(Path output, String name, String source, Path... dependencies) throws Exception {
        compileWithProcessors(ModelTypeProcessor.class.getName(), output, name, source, dependencies);
    }

    private void compileWithProcessors(String processors, Path output, String name, String source,
                                       Path... dependencies) throws Exception {
        Files.createDirectories(output);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            List<Path> inputs = new ArrayList<>(List.of(dependencies));
            inputs.add(output);
            var unit = new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"),
                                                JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
            };
            List<String> options = new ArrayList<>(List.of("-classpath", classpath(inputs.toArray(Path[]::new))));
            options.addAll(processors == null ? List.of("-proc:full", "-Xlint:processing", "-Werror")
                                             : List.of("-processor", processors));
            var task = compiler.getTask(null, files, diagnostics, options,
                                        null, List.of(unit));
            assertTrue(task.call(), () -> diagnostics.getDiagnostics().toString());
        }
    }

    private Path jar(Path directory) throws Exception {
        Path jar = directory.resolveSibling(directory.getFileName() + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(directory.relativize(file).toString()));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private Path mergedJar(Path... directories) throws Exception {
        Path jar = temporary.resolve("merged.jar");
        List<String> modelNames = new ArrayList<>();
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path directory : directories) {
                modelNames.addAll(Files.readAllLines(directory.resolve(ModelTypes.INDEX)));
                try (var files = Files.walk(directory)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(file -> !directory.relativize(file).toString().equals(ModelTypes.INDEX)).toList()) {
                        out.putNextEntry(new JarEntry(directory.relativize(file).toString()));
                        Files.copy(file, out);
                        out.closeEntry();
                    }
                }
            }
            // Equivalent to an AppendingTransformer: ordinary service-file merging does not merge this index.
            out.putNextEntry(new JarEntry(ModelTypes.INDEX));
            out.write((String.join("\n", modelNames) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private String classpath(Path... roots) {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(roots).map(Path::toString),
                                             java.util.stream.Stream.of(System.getProperty("java.class.path")))
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }
}
