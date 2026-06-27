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

package io.fluxzero.downstream.benchmark;

import io.fluxzero.sdk.execution.OnDemandExecution;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.ComponentRegistryBlueprint;
import io.fluxzero.sdk.registry.ComponentRegistryGenerator;
import io.fluxzero.sdk.registry.ComponentRegistryJson;
import io.fluxzero.sdk.registry.SourceComponentScanner;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.PathParam;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Manual comparison harness for normal Maven/javac execution versus on-demand source execution.
 * <p>
 * This is intentionally a plain {@code main}, not a Surefire test. Run it after installing local SDK artifacts:
 * <pre>{@code
 * ./mvnw -DskipTests install
 * ./mvnw -pl java-downstream-project -DskipTests test-compile
 * java -Dfluxzero.benchmark.handlerCounts=1,10,100,1000 \
 *   -cp java-downstream-project/target/test-classes:... \
 *   io.fluxzero.downstream.benchmark.OnDemandComparisonBenchmark
 * }</pre>
 */
public class OnDemandComparisonBenchmark {
    private static final String GENERATED_BASE_PACKAGE = "io.fluxzero.generated.benchmark";
    private static final String RUNTIME_PACKAGE = "io.fluxzero.downstream.benchmark.generated";

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            run();
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
        System.exit(exitCode);
    }

    private static void run() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        Path repoRoot = repoRoot();
        Path workRoot = repoRoot.resolve("java-downstream-project/target/on-demand-comparison");
        deleteDirectory(workRoot);
        Files.createDirectories(workRoot);

        List<Measurement> measurements = new ArrayList<>();
        for (int handlerCount : config.handlerCounts()) {
            ScaleContext scale = generateScale(workRoot, config, handlerCount);
            measurements.add(runMavenTestCompile(repoRoot, scale.normalApp(), handlerCount,
                                                 "normal.maven.testCompile"));
            measurements.add(runMavenTestCompile(repoRoot, scale.onDemandApp(), handlerCount,
                                                 "ondemand.maven.testCompile"));
            measurements.add(measureScan(scale, config));
            measurements.add(measureRegistryGeneration(scale, config));
            measurements.addAll(measureNormalRuntime(scale, config));
            measurements.addAll(measureOnDemandRuntime(scale, config));
        }

        Path runtimeRoot = workRoot.resolve("runtime");
        Path onDemandSourceRoot = runtimeRoot.resolve("src/main/fluxzero");
        Files.createDirectories(onDemandSourceRoot);
        Path onDemandSource = writeRuntimeOnDemandSource(onDemandSourceRoot, "v1");
        Path blueprintOutput = workRoot.resolve("fluxzero-blueprint.md");
        measurements.add(measureRuntimeScan(onDemandSourceRoot, blueprintOutput));
        measurements.addAll(measureSingleNormalRuntime(config));
        measurements.addAll(measureSingleOnDemandRuntime(onDemandSourceRoot, onDemandSource, config));

        printTable(config, measurements);
        writeReports(config, measurements, workRoot);
    }

    private static ScaleContext generateScale(Path workRoot, BenchmarkConfig config, int handlerCount)
            throws IOException {
        Path scaleRoot = workRoot.resolve("handlers-" + handlerCount);
        Path normalApp = scaleRoot.resolve("normal");
        Path onDemandApp = scaleRoot.resolve("ondemand");
        generateComparisonApp(normalApp, config, handlerCount, true, "normal");
        generateComparisonApp(onDemandApp, config, handlerCount, false, "v1");
        return new ScaleContext(scaleRoot, normalApp, onDemandApp, handlerCount);
    }

    private static Measurement runMavenTestCompile(Path repoRoot, Path appRoot, int handlerCount, String name)
            throws Exception {
        return timed(name, handlerCount, () -> {
            Process process = new ProcessBuilder(
                    repoRoot.resolve("mvnw").toString(), "-q", "-f", appRoot.resolve("pom.xml").toString(),
                    "clean", "test-compile")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("""
                        Generated Maven app failed to compile.
                        App: %s
                        Exit code: %s
                        Output:
                        %s
                        """.formatted(appRoot, exitCode, output));
            }
        }).withDetail("app=" + appRoot.getFileName());
    }

    private static Measurement measureScan(ScaleContext scale, BenchmarkConfig config) {
        Path sourceRoot = onDemandSourceRoot(scale);
        Path blueprintOutput = scale.root().resolve("fluxzero-blueprint.md");
        Timed<ComponentRegistry> timed = timedValue(() -> new SourceComponentScanner().scan(sourceRoot));
        ComponentRegistry registry = timed.value();
        try {
            ComponentRegistryBlueprint.from(registry).writeMarkdown(blueprintOutput);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write blueprint to " + blueprintOutput, e);
        }
        return new Measurement("ondemand.source.scan", scale.handlerCount(), timed.nanos(),
                               "%d component(s), %d route(s), %s".formatted(
                                       registry.components().size(), registry.handlerRoutes().count(),
                                       config.shapeDetail()));
    }

    private static Measurement measureRegistryGeneration(ScaleContext scale, BenchmarkConfig config) {
        Path sourceRoot = onDemandSourceRoot(scale);
        Path output = scale.onDemandApp().resolve("target/classes").resolve(ComponentRegistryJson.DEFAULT_RESOURCE);
        Path blueprint = scale.root().resolve("source-registry-blueprint.md");
        Measurement measurement = timed("ondemand.sourceRegistry.generate", scale.handlerCount(),
                                        () -> ComponentRegistryGenerator.generate(
                                                sourceRoot, output, blueprint, "Fluxzero On-Demand Source Registry"));
        return measurement.withDetail("artifact=" + output + ", " + config.shapeDetail());
    }

    private static Measurement measureRuntimeScan(Path sourceRoot, Path blueprintOutput) {
        Timed<ComponentRegistry> timed = timedValue(() -> new SourceComponentScanner().scan(sourceRoot));
        ComponentRegistry registry = timed.value();
        try {
            ComponentRegistryBlueprint.from(registry).writeMarkdown(blueprintOutput);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write blueprint to " + blueprintOutput, e);
        }
        return new Measurement("ondemand.runtime.source.scan", 1, timed.nanos(),
                               "%d component(s), %d route(s), blueprint=%s".formatted(
                                       registry.components().size(), registry.handlerRoutes().count(),
                                       blueprintOutput));
    }

    private static List<Measurement> measureNormalRuntime(ScaleContext scale, BenchmarkConfig config) throws Exception {
        List<Measurement> measurements = new ArrayList<>();
        try (URLClassLoader classLoader = appClassLoader(scale.normalApp())) {
            List<Object> handlers = instantiateHandlers(classLoader, config, scale.handlerCount());
            Timed<TestFixture> fixture = timedValue(() -> TestFixture.create(handlers.toArray()));
            measurements.add(new Measurement("normal.fluxzero.register", scale.handlerCount(), fixture.nanos(),
                                             "registered %d compiled handler(s)".formatted(handlers.size())));
            try {
                measurements.add(measureGeneratedFirstCommand(
                        "normal.first.command", fixture.value(), classLoader, config, scale.handlerCount(), "normal"));
                measurements.add(measureGeneratedFirstCommand(
                        "normal.warm.command", fixture.value(), classLoader, config, scale.handlerCount(), "normal"));
                measurements.add(measureGeneratedFlow(
                        "normal.warmFlow.throughput", fixture.value(), classLoader, config,
                        scale.handlerCount(), "normal"));
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }
        return measurements;
    }

    private static List<Measurement> measureOnDemandNoHotReloadRuntime(ScaleContext scale, BenchmarkConfig config)
            throws Exception {
        List<Measurement> measurements = new ArrayList<>();
        Path sourceRoot = onDemandSourceRoot(scale);
        Path classes = scale.onDemandApp().resolve("target/classes");
        try (URLClassLoader classLoader = appClassLoader(scale.onDemandApp());
             OnDemandExecution execution = OnDemandExecution.builder()
                     .sourceRoot(sourceRoot)
                     .cacheRoot(scale.root().resolve("cache-no-hot"))
                     .cacheTtl(Duration.ofMinutes(10))
                     .addClasspath(classes)
                     .parentClassLoader(classLoader)
                     .checkSourceChangesOnInvocation(false)
                     .startTracking(false)
                     .build()) {
            Timed<TestFixture> fixture = timedValue(() -> TestFixture.create(fluxzero -> {
                execution.registerWith(fluxzero);
                return List.of();
            }));
            measurements.add(new Measurement("ondemand.noHot.fluxzero.register", scale.handlerCount(), fixture.nanos(),
                                             "registered %d lazy handler source unit(s), source checks disabled"
                                                     .formatted(scale.handlerCount())));
            try {
                measurements.add(measureGeneratedColdSweep(
                        "ondemand.noHot.coldSweep.flow", fixture.value(), classLoader, config,
                        scale.handlerCount(), "v2"));
                measurements.add(measureGeneratedFlow(
                        "ondemand.noHot.warmFlow.throughput", fixture.value(), classLoader, config,
                        scale.handlerCount(), "v2"));
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }
        return measurements;
    }

    private static List<Measurement> measureOnDemandRuntime(ScaleContext scale, BenchmarkConfig config)
            throws Exception {
        List<Measurement> measurements = new ArrayList<>();
        Path sourceRoot = onDemandSourceRoot(scale);
        Path classes = scale.onDemandApp().resolve("target/classes");
        try (URLClassLoader classLoader = appClassLoader(scale.onDemandApp());
             OnDemandExecution execution = OnDemandExecution.builder()
                     .sourceRoot(sourceRoot)
                     .cacheRoot(scale.root().resolve("cache"))
                     .cacheTtl(Duration.ofMinutes(10))
                     .addClasspath(classes)
                     .parentClassLoader(classLoader)
                     .startTracking(false)
                     .build()) {
            Timed<TestFixture> fixture = timedValue(() -> TestFixture.create(fluxzero -> {
                execution.registerWith(fluxzero);
                return List.of();
            }));
            measurements.add(new Measurement("ondemand.fluxzero.register", scale.handlerCount(), fixture.nanos(),
                                             "registered %d lazy handler source unit(s)".formatted(
                                                     scale.handlerCount())));
            try {
                measurements.add(measureGeneratedFirstCommand(
                        "ondemand.first.command.coldCompile", fixture.value(), classLoader, config,
                        scale.handlerCount(), "v1"));
                measurements.add(measureGeneratedFirstCommand(
                        "ondemand.warm.command", fixture.value(), classLoader, config, scale.handlerCount(), "v1"));
                measurements.add(measureGeneratedHotRecompile(scale, fixture.value(), classLoader, config));
                measurements.add(measureGeneratedColdSweep(
                        "ondemand.coldSweep.flow", fixture.value(), classLoader, config, scale.handlerCount(), "v2"));
                measurements.add(measureGeneratedFlow(
                        "ondemand.warmFlow.throughput", fixture.value(), classLoader, config,
                        scale.handlerCount(), "v2"));
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }
        measurements.addAll(measureOnDemandPrewarmRuntime(scale, config));
        measurements.addAll(measureOnDemandNoHotReloadRuntime(scale, config));
        return measurements;
    }

    private static List<Measurement> measureOnDemandPrewarmRuntime(ScaleContext scale, BenchmarkConfig config)
            throws Exception {
        List<Measurement> measurements = new ArrayList<>();
        Path sourceRoot = onDemandSourceRoot(scale);
        Path classes = scale.onDemandApp().resolve("target/classes");
        try (URLClassLoader classLoader = appClassLoader(scale.onDemandApp());
             OnDemandExecution execution = OnDemandExecution.builder()
                     .sourceRoot(sourceRoot)
                     .cacheRoot(scale.root().resolve("cache-prewarm"))
                     .cacheTtl(Duration.ofMinutes(10))
                     .addClasspath(classes)
                     .parentClassLoader(classLoader)
                     .checkSourceChangesOnInvocation(false)
                     .startTracking(false)
                     .build()) {
            Timed<TestFixture> fixture = timedValue(() -> TestFixture.create(fluxzero -> {
                execution.registerWith(fluxzero);
                return List.of();
            }));
            measurements.add(new Measurement("ondemand.prewarm.fluxzero.register", scale.handlerCount(), fixture.nanos(),
                                             "registered %d lazy handler source unit(s), source checks disabled"
                                                     .formatted(scale.handlerCount())));
            try {
                measurements.add(timed("ondemand.prewarm.compileAndLoad", scale.handlerCount(), execution::prewarm)
                                         .withDetail("batch prewarmed %d source unit(s)".formatted(
                                                 scale.handlerCount())));
                measurements.add(measureGeneratedColdSweep(
                        "ondemand.prewarm.coldSweep.flow", fixture.value(), classLoader, config,
                        scale.handlerCount(), "v2"));
                measurements.add(measureGeneratedFlow(
                        "ondemand.prewarm.warmFlow.throughput", fixture.value(), classLoader, config,
                        scale.handlerCount(), "v2"));
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }
        return measurements;
    }

    private static List<Measurement> measureSingleNormalRuntime(BenchmarkConfig config) {
        List<Measurement> measurements = new ArrayList<>();
        Timed<TestFixture> fixture = timedValue(() -> TestFixture.create(new NormalBenchmarkHandler("normal")));
        measurements.add(new Measurement("normal.single.fluxzero.register", 1, fixture.nanos(),
                                         "TestFixture.create"));
        try {
            measurements.add(timed("normal.single.first.command", 1,
                                   () -> fixture.value().whenCommand(new BenchmarkCommand("cold"))
                                           .expectResult(new BenchmarkResult("normal:command:cold"))));
            measurements.add(timed("normal.single.warm.command", 1,
                                   () -> fixture.value().whenCommand(new BenchmarkCommand("warm"))
                                           .expectResult(new BenchmarkResult("normal:command:warm"))));
            measurements.add(measureSingleFlow("normal.single.warmFlow.throughput", fixture.value(), "normal",
                                               config.flowIterations()));
            return measurements;
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static List<Measurement> measureSingleOnDemandRuntime(Path sourceRoot, Path sourceFile,
                                                                  BenchmarkConfig config) throws Exception {
        List<Measurement> measurements = new ArrayList<>();
        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(sourceRoot)
                .cacheRoot(sourceRoot.getParent().resolve("cache"))
                .cacheTtl(Duration.ofMinutes(10))
                .startTracking(false)
                .build()) {
            Timed<TestFixture> fixture = timedValue(() -> TestFixture.create(fluxzero -> {
                execution.registerWith(fluxzero);
                return List.of();
            }));
            measurements.add(new Measurement("ondemand.single.fluxzero.register", 1, fixture.nanos(),
                                             "includes registry scan, no javac"));
            try {
                measurements.add(timed("ondemand.single.first.command.coldCompile", 1,
                                       () -> fixture.value().whenCommand(new BenchmarkCommand("cold"))
                                               .expectResult(new BenchmarkResult("v1:command:cold"))));
                measurements.add(timed("ondemand.single.warm.command", 1,
                                       () -> fixture.value().whenCommand(new BenchmarkCommand("warm"))
                                               .expectResult(new BenchmarkResult("v1:command:warm"))));
                measurements.add(measureSingleHotRecompile(sourceRoot, sourceFile, fixture.value()));
                measurements.add(measureSingleColdSweep("ondemand.single.coldSweep.flow", fixture.value(), "v2"));
                measurements.add(measureSingleFlow("ondemand.single.warmFlow.throughput", fixture.value(), "v2",
                                                   config.flowIterations()));
                return measurements;
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }
    }

    private static Measurement measureGeneratedFirstCommand(String name, TestFixture fixture, ClassLoader classLoader,
                                                           BenchmarkConfig config, int handlerCount, String prefix) {
        return timed(name, handlerCount, () -> {
            Object command = payload(classLoader, config, 0, "Command", "cold");
            Object result = result(classLoader, config, 0, prefix, "command", "cold");
            fixture.whenCommand(command).expectResult(result).expectNoErrors();
        });
    }

    private static Measurement measureGeneratedHotRecompile(ScaleContext scale, TestFixture fixture,
                                                           ClassLoader classLoader, BenchmarkConfig config)
            throws IOException {
        Path handlerSource = handlerSource(onDemandSourceRoot(scale), config, 0);
        Files.writeString(handlerSource, generatedHandlerSource(config, 0, "v2"));
        Measurement measurement = timed("ondemand.hotRecompile.command", scale.handlerCount(), () -> {
            Object command = payload(classLoader, config, 0, "Command", "hot");
            Object result = result(classLoader, config, 0, "v2", "command", "hot");
            fixture.whenCommand(command).expectResult(result).expectNoErrors();
        }).withDetail("changed " + handlerSource.getFileName());
        for (int i = 1; i < scale.handlerCount(); i++) {
            Files.writeString(handlerSource(onDemandSourceRoot(scale), config, i),
                              generatedHandlerSource(config, i, "v2"));
        }
        return measurement;
    }

    private static Measurement measureSingleHotRecompile(Path sourceRoot, Path sourceFile, TestFixture fixture)
            throws IOException {
        writeRuntimeOnDemandSource(sourceRoot, "v2");
        return timed("ondemand.single.hotRecompile.command", 1,
                     () -> fixture.whenCommand(new BenchmarkCommand("hot"))
                             .expectResult(new BenchmarkResult("v2:command:hot")))
                .withDetail("changed " + sourceFile.getFileName());
    }

    private static Measurement measureGeneratedFlow(String name, TestFixture fixture, ClassLoader classLoader,
                                                   BenchmarkConfig config, int handlerCount, String prefix) {
        Measurement measurement = timed(name, handlerCount, () -> {
            for (int i = 0; i < config.flowIterations(); i++) {
                int index = i % handlerCount;
                String value = Integer.toString(i);
                if (config.commandRoutes()) {
                    fixture.whenCommand(payload(classLoader, config, index, "Command", value))
                            .expectResult(result(classLoader, config, index, prefix, "command", value))
                            .expectNoErrors();
                }
                if (config.queryRoutes()) {
                    fixture.whenQuery(payload(classLoader, config, index, "Query", value))
                            .expectResult(result(classLoader, config, index, prefix, "query", value))
                            .expectNoErrors();
                }
                if (config.eventRoutes()) {
                    fixture.whenEvent(payload(classLoader, config, index, "Event", value))
                            .expectNoResult()
                            .expectNoErrors();
                }
                if (config.webRoutesEnabled()) {
                    fixture.whenGet("/generated/" + index + "/" + value)
                            .expectResult(prefix + ":web:" + value)
                            .expectNoErrors();
                }
            }
        });
        return measurement.withDetail(flowDetail(config, measurement));
    }

    private static Measurement measureGeneratedColdSweep(String name, TestFixture fixture, ClassLoader classLoader,
                                                        BenchmarkConfig config, int handlerCount, String prefix) {
        Measurement measurement = timed(name, handlerCount, () -> {
            for (int index = 0; index < handlerCount; index++) {
                String value = "sweep-" + index;
                invokeGeneratedFlowStep(fixture, classLoader, config, handlerCount, prefix, index, value);
            }
        });
        double operations = handlerCount * (double) config.routeCount();
        double seconds = measurement.nanos() / 1_000_000_000.0;
        return measurement.withDetail(String.format(
                Locale.ROOT, "%d handler sweep(s), %.1f op/s, %s",
                handlerCount, operations / seconds, config.shapeDetail()));
    }

    private static Measurement measureSingleFlow(String name, TestFixture fixture, String prefix, int iterations) {
        Measurement measurement = timed(name, 1, () -> {
            for (int i = 0; i < iterations; i++) {
                String value = Integer.toString(i);
                invokeSingleFlowStep(fixture, prefix, value);
            }
        });
        double operations = iterations * 4.0;
        double seconds = measurement.nanos() / 1_000_000_000.0;
        return measurement.withDetail(String.format(Locale.ROOT, "%d flow iteration(s), %.1f op/s",
                                                    iterations, operations / seconds));
    }

    private static Measurement measureSingleColdSweep(String name, TestFixture fixture, String prefix) {
        Measurement measurement = timed(name, 1, () -> invokeSingleFlowStep(fixture, prefix, "sweep"));
        double operations = 4.0;
        double seconds = measurement.nanos() / 1_000_000_000.0;
        return measurement.withDetail(String.format(Locale.ROOT, "1 handler sweep, %.1f op/s",
                                                    operations / seconds));
    }

    private static void invokeGeneratedFlowStep(TestFixture fixture, ClassLoader classLoader, BenchmarkConfig config,
                                                int handlerCount, String prefix, int index, String value)
            throws Exception {
        if (index >= handlerCount) {
            throw new IllegalArgumentException("index must be smaller than handlerCount");
        }
        if (config.commandRoutes()) {
            fixture.whenCommand(payload(classLoader, config, index, "Command", value))
                    .expectResult(result(classLoader, config, index, prefix, "command", value))
                    .expectNoErrors();
        }
        if (config.queryRoutes()) {
            fixture.whenQuery(payload(classLoader, config, index, "Query", value))
                    .expectResult(result(classLoader, config, index, prefix, "query", value))
                    .expectNoErrors();
        }
        if (config.eventRoutes()) {
            fixture.whenEvent(payload(classLoader, config, index, "Event", value))
                    .expectNoResult()
                    .expectNoErrors();
        }
        if (config.webRoutesEnabled()) {
            fixture.whenGet("/generated/" + index + "/" + value)
                    .expectResult(prefix + ":web:" + value)
                    .expectNoErrors();
        }
    }

    private static void invokeSingleFlowStep(TestFixture fixture, String prefix, String value) {
        fixture.whenCommand(new BenchmarkCommand(value))
                .expectResult(new BenchmarkResult(prefix + ":command:" + value))
                .expectNoErrors();
        fixture.whenQuery(new BenchmarkQuery(value))
                .expectResult(new BenchmarkResult(prefix + ":query:" + value))
                .expectNoErrors();
        fixture.whenEvent(new BenchmarkEvent(value))
                .expectNoResult()
                .expectNoErrors();
        fixture.whenGet("/benchmark/" + value)
                .expectResult(prefix + ":web:" + value)
                .expectNoErrors();
    }

    private static String flowDetail(BenchmarkConfig config, Measurement measurement) {
        double operations = config.flowIterations() * config.routeCount();
        double seconds = measurement.nanos() / 1_000_000_000.0;
        return String.format(Locale.ROOT, "%d flow iteration(s), %.1f op/s, %s",
                             config.flowIterations(), operations / seconds, config.shapeDetail());
    }

    private static Measurement timed(String name, int handlerCount, CheckedRunnable runnable) {
        long started = System.nanoTime();
        try {
            runnable.run();
        } catch (Exception e) {
            throw new IllegalStateException("Benchmark step failed: " + name, e);
        }
        return new Measurement(name, handlerCount, System.nanoTime() - started, "");
    }

    private static <T> Timed<T> timedValue(CheckedSupplier<T> supplier) {
        long started = System.nanoTime();
        try {
            return new Timed<>(supplier.get(), System.nanoTime() - started);
        } catch (Exception e) {
            throw new IllegalStateException("Benchmark step failed", e);
        }
    }

    private static void generateComparisonApp(Path appRoot, BenchmarkConfig config, int handlerCount,
                                              boolean compileHandlers, String prefix) throws IOException {
        Path javaRoot = appRoot.resolve("src/main/java");
        Path handlerSourceRoot = compileHandlers ? javaRoot : appRoot.resolve("src/main/fluxzero");
        Files.createDirectories(javaRoot);
        Files.createDirectories(handlerSourceRoot);
        Files.writeString(appRoot.resolve("pom.xml"), generatedPom(appRoot.getFileName().toString()));
        for (int i = 0; i < handlerCount; i++) {
            Path javaPackageRoot = javaRoot.resolve(packageName(config, i).replace('.', '/'));
            Path handlerPackageRoot = handlerSourceRoot.resolve(packageName(config, i).replace('.', '/'));
            Files.createDirectories(javaPackageRoot);
            Files.createDirectories(handlerPackageRoot);
            writeGeneratedPayloads(javaPackageRoot, config, i);
            Files.writeString(handlerPackageRoot.resolve(handlerClassName(i) + ".java"),
                              generatedHandlerSource(config, i, prefix));
        }
    }

    private static void writeGeneratedPayloads(Path javaRoot, BenchmarkConfig config, int index) throws IOException {
        Files.writeString(javaRoot.resolve("GeneratedCommand" + index + ".java"),
                          generatedRecord(config, "GeneratedCommand" + index, index));
        Files.writeString(javaRoot.resolve("GeneratedQuery" + index + ".java"),
                          generatedRecord(config, "GeneratedQuery" + index, index));
        Files.writeString(javaRoot.resolve("GeneratedEvent" + index + ".java"),
                          generatedRecord(config, "GeneratedEvent" + index, index));
        Files.writeString(javaRoot.resolve("GeneratedResult" + index + ".java"),
                          generatedRecord(config, "GeneratedResult" + index, index));
    }

    private static String generatedRecord(BenchmarkConfig config, String name, int index) {
        return """
                package %s;

                public record %s(String value) {
                }
                """.formatted(packageName(config, index), name);
    }

    private static String generatedHandlerSource(BenchmarkConfig config, int index, String prefix) {
        StringBuilder imports = new StringBuilder("""
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                """);
        if (config.consumers() > 0) {
            imports.append("import io.fluxzero.sdk.tracking.Consumer;\n");
        }
        if (config.commandRoutes()) {
            imports.append("import io.fluxzero.sdk.tracking.handling.HandleCommand;\n");
        }
        if (config.queryRoutes()) {
            imports.append("import io.fluxzero.sdk.tracking.handling.HandleQuery;\n");
        }
        if (config.eventRoutes()) {
            imports.append("import io.fluxzero.sdk.tracking.handling.HandleEvent;\n");
        }
        if (config.webRoutesEnabled()) {
            imports.append("""
                    import io.fluxzero.sdk.web.HandleGet;
                    import io.fluxzero.sdk.web.PathParam;
                    """);
        }
        StringBuilder annotations = new StringBuilder();
        if (config.consumers() > 0) {
            annotations.append("@Consumer(name = \"generated-consumer-")
                    .append(index % config.consumers()).append("\", threads = 1)\n");
        }
        StringBuilder methods = new StringBuilder();
        if (config.commandRoutes()) {
            methods.append("""
                        @HandleCommand
                        public GeneratedResult%d handle(GeneratedCommand%d command) {
                            return new GeneratedResult%d("%s:command:" + command.value());
                        }

                    """.formatted(index, index, index, prefix));
        }
        if (config.queryRoutes()) {
            methods.append("""
                        @HandleQuery
                        public GeneratedResult%d query(GeneratedQuery%d query) {
                            return new GeneratedResult%d("%s:query:" + query.value());
                        }

                    """.formatted(index, index, index, prefix));
        }
        if (config.eventRoutes()) {
            methods.append("""
                        @HandleEvent
                        public void on(GeneratedEvent%d event) {
                        }

                    """.formatted(index));
        }
        if (config.webRoutesEnabled()) {
            methods.append("""
                        @HandleGet("/generated/%d/{id}")
                        public String get(@PathParam String id) {
                            return "%s:web:" + id;
                        }

                    """.formatted(index, prefix));
        }
        return """
                package %s;

                %s
                %s@LocalHandler
                public class %s {
                %s}
                """.formatted(packageName(config, index), imports, annotations, handlerClassName(index), methods);
    }

    private static String generatedPom(String artifactId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>io.fluxzero.benchmark</groupId>
                    <artifactId>%s</artifactId>
                    <version>0-SNAPSHOT</version>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <maven.compiler.version>3.15.0</maven.compiler.version>
                        <maven.exec.version>3.6.3</maven.exec.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>io.fluxzero</groupId>
                                <artifactId>fluxzero-bom</artifactId>
                                <version>0-SNAPSHOT</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.fluxzero</groupId>
                            <artifactId>sdk</artifactId>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>${maven.compiler.version}</version>
                                <configuration>
                                    <parameters>true</parameters>
                                    <annotationProcessorPaths>
                                        <path>
                                            <groupId>io.fluxzero</groupId>
                                            <artifactId>common</artifactId>
                                            <version>0-SNAPSHOT</version>
                                        </path>
                                        <path>
                                            <groupId>io.fluxzero</groupId>
                                            <artifactId>sdk</artifactId>
                                            <version>0-SNAPSHOT</version>
                                        </path>
                                    </annotationProcessorPaths>
                                </configuration>
                            </plugin>
                            <plugin>
                                <groupId>org.codehaus.mojo</groupId>
                                <artifactId>exec-maven-plugin</artifactId>
                                <version>${maven.exec.version}</version>
                                <executions>
                                    <execution>
                                        <id>generate-fluxzero-main-registry</id>
                                        <phase>process-classes</phase>
                                        <goals>
                                            <goal>java</goal>
                                        </goals>
                                        <configuration>
                                            <mainClass>io.fluxzero.sdk.registry.ComponentRegistryGenerator</mainClass>
                                            <classpathScope>runtime</classpathScope>
                                            <arguments>
                                                <argument>--source-root</argument>
                                                <argument>${project.basedir}/src/main/fluxzero</argument>
                                                <argument>--output</argument>
                                                <argument>${project.build.outputDirectory}/META-INF/fluxzero/component-registry.json</argument>
                                                <argument>--merge-existing</argument>
                                            </arguments>
                                        </configuration>
                                    </execution>
                                    <execution>
                                        <id>generate-fluxzero-test-registry</id>
                                        <phase>process-test-classes</phase>
                                        <goals>
                                            <goal>java</goal>
                                        </goals>
                                        <configuration>
                                            <mainClass>io.fluxzero.sdk.registry.ComponentRegistryGenerator</mainClass>
                                            <classpathScope>test</classpathScope>
                                            <arguments>
                                                <argument>--test</argument>
                                                <argument>--source-root</argument>
                                                <argument>${project.basedir}/src/test/fluxzero</argument>
                                                <argument>--output</argument>
                                                <argument>${project.build.testOutputDirectory}/META-INF/fluxzero/component-registry.json</argument>
                                                <argument>--merge-existing</argument>
                                            </arguments>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>fluxzero-ide-sources</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.codehaus.mojo</groupId>
                                        <artifactId>build-helper-maven-plugin</artifactId>
                                        <version>3.6.1</version>
                                        <executions>
                                            <execution>
                                                <id>add-fluxzero-source-root</id>
                                                <phase>generate-sources</phase>
                                                <goals>
                                                    <goal>add-source</goal>
                                                </goals>
                                                <configuration>
                                                    <sources>
                                                        <source>${project.basedir}/src/main/fluxzero</source>
                                                    </sources>
                                                </configuration>
                                            </execution>
                                            <execution>
                                                <id>add-fluxzero-test-source-root</id>
                                                <phase>generate-test-sources</phase>
                                                <goals>
                                                    <goal>add-test-source</goal>
                                                </goals>
                                                <configuration>
                                                    <sources>
                                                        <source>${project.basedir}/src/test/fluxzero</source>
                                                    </sources>
                                                </configuration>
                                            </execution>
                                        </executions>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
                </project>
                """.formatted(artifactId);
    }

    private static Path writeRuntimeOnDemandSource(Path sourceRoot, String prefix) throws IOException {
        Path packageRoot = sourceRoot.resolve(RUNTIME_PACKAGE.replace('.', '/'));
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("package-info.java"), """
                @io.fluxzero.sdk.tracking.Consumer(name = "benchmark-package", threads = 2)
                package %s;
                """.formatted(RUNTIME_PACKAGE));
        Path source = packageRoot.resolve("RuntimeOnDemandHandler.java");
        Files.writeString(source, """
                package %s;

                import io.fluxzero.downstream.benchmark.OnDemandComparisonBenchmark.BenchmarkCommand;
                import io.fluxzero.downstream.benchmark.OnDemandComparisonBenchmark.BenchmarkEvent;
                import io.fluxzero.downstream.benchmark.OnDemandComparisonBenchmark.BenchmarkQuery;
                import io.fluxzero.downstream.benchmark.OnDemandComparisonBenchmark.BenchmarkResult;
                import io.fluxzero.sdk.tracking.Consumer;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleEvent;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.PathParam;

                @Consumer(name = "benchmark-handler", threads = 1)
                @LocalHandler
                public class RuntimeOnDemandHandler {
                    @HandleCommand
                    public BenchmarkResult handle(BenchmarkCommand command) {
                        return new BenchmarkResult("%s:command:" + command.value());
                    }

                    @HandleQuery
                    public BenchmarkResult query(BenchmarkQuery query) {
                        return new BenchmarkResult("%s:query:" + query.value());
                    }

                    @HandleEvent
                    public void on(BenchmarkEvent event) {
                    }

                    @HandleGet("/benchmark/{id}")
                    public String get(@PathParam String id) {
                        return "%s:web:" + id;
                    }
                }
                """.formatted(RUNTIME_PACKAGE, prefix, prefix, prefix));
        return source;
    }

    private static URLClassLoader appClassLoader(Path appRoot) throws Exception {
        URL classes = appRoot.resolve("target/classes").toUri().toURL();
        return new URLClassLoader(new URL[]{classes}, OnDemandComparisonBenchmark.class.getClassLoader());
    }

    private static List<Object> instantiateHandlers(ClassLoader classLoader, BenchmarkConfig config, int handlerCount)
            throws Exception {
        List<Object> handlers = new ArrayList<>(handlerCount);
        for (int i = 0; i < handlerCount; i++) {
            Class<?> type = Class.forName(handlerTypeName(config, i), true, classLoader);
            handlers.add(type.getDeclaredConstructor().newInstance());
        }
        return handlers;
    }

    private static Object payload(ClassLoader classLoader, BenchmarkConfig config, int index, String kind, String value)
            throws Exception {
        return record(classLoader, packageName(config, index) + ".Generated" + kind + index, value);
    }

    private static Object result(ClassLoader classLoader, BenchmarkConfig config, int index, String prefix,
                                 String kind, String value) throws Exception {
        return record(classLoader, packageName(config, index) + ".GeneratedResult" + index,
                      prefix + ":" + kind + ":" + value);
    }

    private static Object record(ClassLoader classLoader, String className, String value) throws Exception {
        Class<?> type = Class.forName(className, true, classLoader);
        Constructor<?> constructor = type.getDeclaredConstructor(String.class);
        return constructor.newInstance(value);
    }

    private static Path onDemandSourceRoot(ScaleContext scale) {
        return scale.onDemandApp().resolve("src/main/fluxzero");
    }

    private static Path handlerSource(Path sourceRoot, BenchmarkConfig config, int index) {
        return sourceRoot.resolve(packageName(config, index).replace('.', '/')).resolve(handlerClassName(index) + ".java");
    }

    private static String handlerTypeName(BenchmarkConfig config, int index) {
        return packageName(config, index) + "." + handlerClassName(index);
    }

    private static String handlerClassName(int index) {
        return "GeneratedHandler" + index;
    }

    private static String packageName(BenchmarkConfig config, int index) {
        StringBuilder result = new StringBuilder(GENERATED_BASE_PACKAGE);
        for (int i = 0; i < config.packageDepth(); i++) {
            result.append(".p").append(i);
        }
        return result.toString();
    }

    private static void printTable(BenchmarkConfig config, List<Measurement> measurements) {
        System.out.println();
        System.out.println("Fluxzero on-demand comparison");
        System.out.println(config);
        System.out.println();
        System.out.printf("%-42s %8s %12s  %s%n", "measurement", "handlers", "millis", "detail");
        System.out.printf("%-42s %8s %12s  %s%n", "-----------", "--------", "------", "------");
        for (Measurement measurement : measurements) {
            System.out.printf(Locale.ROOT, "%-42s %8d %12.3f  %s%n",
                              measurement.name(), measurement.handlerCount(), measurement.millis(),
                              measurement.detail());
        }
    }

    private static void writeReports(BenchmarkConfig config, List<Measurement> measurements, Path workRoot)
            throws IOException {
        Path jsonReport = workRoot.resolve("report.json");
        Path markdownReport = workRoot.resolve("report.md");
        Files.writeString(jsonReport, jsonReport(config, measurements, workRoot), StandardCharsets.UTF_8);
        Files.writeString(markdownReport, markdownReport(config, measurements, workRoot, jsonReport),
                          StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Wrote benchmark reports:");
        System.out.println("  " + markdownReport);
        System.out.println("  " + jsonReport);
    }

    private static String jsonReport(BenchmarkConfig config, List<Measurement> measurements, Path workRoot) {
        StringBuilder result = new StringBuilder();
        result.append("{\n");
        result.append("  \"workRoot\": \"").append(json(workRoot.toString())).append("\",\n");
        result.append("  \"handlerCounts\": [").append(joinInts(config.handlerCounts())).append("],\n");
        result.append("  \"flowIterations\": ").append(config.flowIterations()).append(",\n");
        result.append("  \"routesPerHandler\": ").append(config.routesPerHandler()).append(",\n");
        result.append("  \"packageDepth\": ").append(config.packageDepth()).append(",\n");
        result.append("  \"consumers\": ").append(config.consumers()).append(",\n");
        result.append("  \"webRoutes\": ").append(config.webRoutes()).append(",\n");
        result.append("  \"measurements\": [\n");
        for (int i = 0; i < measurements.size(); i++) {
            Measurement measurement = measurements.get(i);
            result.append("    {\"name\":\"").append(json(measurement.name())).append("\",")
                    .append("\"handlers\":").append(measurement.handlerCount()).append(",")
                    .append("\"nanos\":").append(measurement.nanos()).append(",")
                    .append("\"millis\":").append(String.format(Locale.ROOT, "%.3f", measurement.millis())).append(",")
                    .append("\"detail\":\"").append(json(measurement.detail())).append("\"}");
            result.append(i == measurements.size() - 1 ? "\n" : ",\n");
        }
        result.append("  ]\n");
        result.append("}\n");
        return result.toString();
    }

    private static String markdownReport(BenchmarkConfig config, List<Measurement> measurements, Path workRoot,
                                         Path jsonReport) {
        StringBuilder result = new StringBuilder();
        result.append("# Fluxzero On-Demand Comparison\n\n");
        result.append("- Work root: `").append(workRoot).append("`\n");
        result.append("- JSON: `").append(jsonReport).append("`\n");
        result.append("- Handler counts: `").append(joinInts(config.handlerCounts())).append("`\n");
        result.append("- Shape: ").append(config.shapeDetail()).append("\n\n");
        result.append("| Measurement | Handlers | Millis | Detail |\n");
        result.append("| --- | ---: | ---: | --- |\n");
        for (Measurement measurement : measurements) {
            result.append("| ").append(markdown(measurement.name()))
                    .append(" | ").append(measurement.handlerCount())
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", measurement.millis()))
                    .append(" | ").append(markdown(measurement.detail()))
                    .append(" |\n");
        }
        return result.toString();
    }

    private static String joinInts(List<Integer> values) {
        return values.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }

    private static String json(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String markdown(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", "<br/>");
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("mvnw"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from " + Path.of("").toAbsolutePath());
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            List<Path> paths = files.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.delete(path);
            }
        }
    }

    public record BenchmarkCommand(String value) {
    }

    public record BenchmarkQuery(String value) {
    }

    public record BenchmarkEvent(String value) {
    }

    public record BenchmarkResult(String value) {
    }

    public static class NormalBenchmarkHandler {
        private final String prefix;

        public NormalBenchmarkHandler(String prefix) {
            this.prefix = prefix;
        }

        @LocalHandler
        @HandleCommand
        public BenchmarkResult handle(BenchmarkCommand command) {
            return new BenchmarkResult(prefix + ":command:" + command.value());
        }

        @LocalHandler
        @HandleQuery
        public BenchmarkResult query(BenchmarkQuery query) {
            return new BenchmarkResult(prefix + ":query:" + query.value());
        }

        @LocalHandler
        @HandleEvent
        public void on(BenchmarkEvent event) {
        }

        @LocalHandler
        @HandleGet("/benchmark/{id}")
        public String get(@PathParam String id) {
            return prefix + ":web:" + id;
        }
    }

    private record BenchmarkConfig(
            List<Integer> handlerCounts, int flowIterations, int routesPerHandler, int packageDepth,
            int consumers, boolean webRoutes) {

        private static BenchmarkConfig fromSystemProperties() {
            String legacyHandlers = System.getProperty("fluxzero.benchmark.generatedHandlers");
            String handlerCounts = System.getProperty("fluxzero.benchmark.handlerCounts",
                                                      legacyHandlers == null ? "1,10,100,1000" : legacyHandlers);
            int routesPerHandler = intProperty("fluxzero.benchmark.routesPerHandler", 4);
            boolean webRoutes = Boolean.parseBoolean(System.getProperty("fluxzero.benchmark.webRoutes", "true"));
            if (!webRoutes) {
                routesPerHandler = Math.min(routesPerHandler, 3);
            }
            return new BenchmarkConfig(
                    parseCounts(handlerCounts),
                    intProperty("fluxzero.benchmark.flowIterations", 100),
                    Math.max(1, Math.min(4, routesPerHandler)),
                    Math.max(0, intProperty("fluxzero.benchmark.packageDepth", 1)),
                    Math.max(0, intProperty("fluxzero.benchmark.consumers", 4)),
                    webRoutes);
        }

        private static int intProperty(String name, int defaultValue) {
            return Integer.getInteger(name, defaultValue);
        }

        private static List<Integer> parseCounts(String value) {
            List<Integer> result = new ArrayList<>();
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    result.add(Math.max(1, Integer.parseInt(part.trim())));
                }
            }
            return result.isEmpty() ? List.of(1) : List.copyOf(result);
        }

        private boolean commandRoutes() {
            return routesPerHandler >= 1;
        }

        private boolean queryRoutes() {
            return routesPerHandler >= 2;
        }

        private boolean eventRoutes() {
            return routesPerHandler >= 3;
        }

        private boolean webRoutesEnabled() {
            return webRoutes && routesPerHandler >= 4;
        }

        private int routeCount() {
            return (commandRoutes() ? 1 : 0)
                   + (queryRoutes() ? 1 : 0)
                   + (eventRoutes() ? 1 : 0)
                   + (webRoutesEnabled() ? 1 : 0);
        }

        private String shapeDetail() {
            return "routesPerHandler=%d, packageDepth=%d, consumers=%d, webRoutes=%s".formatted(
                    routesPerHandler, packageDepth, consumers, webRoutes);
        }
    }

    private record ScaleContext(Path root, Path normalApp, Path onDemandApp, int handlerCount) {
    }

    private record Measurement(String name, int handlerCount, long nanos, String detail) {
        private double millis() {
            return nanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
        }

        private Measurement withDetail(String detail) {
            return new Measurement(name, handlerCount, nanos, detail);
        }
    }

    private record Timed<T>(T value, long nanos) {
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
