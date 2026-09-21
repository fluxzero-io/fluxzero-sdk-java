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

package io.fluxzero.proxy;

import io.fluxzero.sdk.tracking.metrics.host.collectors.ContainerCollector;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.BufferPoolMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forked header-buffer study, also available through {@code ProxyServerBenchmark -DheaderBuffers=true}.
 * See {@code proxy/HEADER_BUFFER_BENCHMARK.md}. Only the child contains the proxy and local application.
 * Pool instrumentation is opt-in and must not be used for performance comparisons.
 */
public final class ProxyHeaderBufferBenchmark {
    private static final List<String> BENCHMARK_JVM_ARGUMENTS = List.of(
            "-Xms32m", "-Xmx384m", "-XX:+UseSerialGC", "-XX:NativeMemoryTracking=summary");
    private static final String[] SCENARIOS = {
            "health", "ready", "not-ready", "small", "large-body", "below", "above", "near-max",
            "mixed-1", "mixed-10", "mixed-100"};

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child(Path.of(args[1]));
            System.exit(0); // The standalone benchmark owns this child JVM.
            return;
        }
        requireDriverJvmArguments();
        // Must be set before the driver's first HTTP client is constructed.
        System.setProperty("jdk.http.maxHeaderSize", "2097152");
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
        Path output = Path.of(System.getProperty("output", "target/header-buffer-benchmark")).toAbsolutePath();
        if (Files.exists(output)) {
            try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Output directory must be empty: " + output);
                }
            }
        }
        Files.createDirectories(output);
        try (PrintWriter results = new PrintWriter(Files.newBufferedWriter(output.resolve("runs.csv")))) {
            results.println("buffer,outputDirect,instrumented,protocol,connections,concurrency,scenario,requests,seconds,requestsPerSecond,p50Micros,p95Micros,p99Micros,http1InitialAcquires,http1OverflowAcquires");
            for (String size : csv("buffers", "4096,8192,16384,32768,65536,1048576")) {
                for (String outputDirect : csv("outputDirect", "true")) {
                    for (String instrument : csv("instrument", "false,true")) {
                        Path run = output.resolve(size + "-" + outputDirect + "-" + instrument);
                        if (Files.exists(run)) { throw new IllegalArgumentException("Output already exists: " + run); }
                        Files.createDirectories(run);
                        List<String> command = new ArrayList<>();
                        command.add(javaBinary());
                        command.addAll(BENCHMARK_JVM_ARGUMENTS);
                        command.addAll(List.of("-Xlog:gc:file=" + run.resolve("gc.log"),
                                "-D" + ProxyServer.RESPONSE_HEADER_BUFFER_SIZE_PROPERTY + "=" + size,
                                "-D" + ProxyServer.USE_OUTPUT_DIRECT_BYTE_BUFFERS_PROPERTY + "=" + outputDirect,
                                "-Dinstrument=" + instrument, "-cp", System.getProperty("java.class.path"),
                                ProxyHeaderBufferBenchmark.class.getName(), "child", run.toString()));
                        Files.writeString(run.resolve("command.txt"), String.join("\n", command));
                        Process child = new ProcessBuilder(command).redirectErrorStream(true)
                                .redirectOutput(run.resolve("proxy.log").toFile()).start();
                        try (PrintWriter control = new PrintWriter(child.getOutputStream(), true)) {
                            awaitFile(run.resolve("port"), child);
                            int port = Integer.parseInt(Files.readString(run.resolve("port")));
                            int index = 0;
                            for (String protocol : csv("protocols", "h1,h2")) {
                                for (String connections : csv("connections", "keep-alive,close")) {
                                    for (String concurrency : csv("concurrencies", "1,16")) {
                                        for (String scenario : csv("scenarios", String.join(",", SCENARIOS))) {
                                            if (!Arrays.asList(SCENARIOS).contains(scenario)) {
                                                throw new IllegalArgumentException("Unknown scenario " + scenario);
                                            }
                                            String id = "%04d-%s-%s-%s-%s".formatted(++index, protocol, connections,
                                                                                     concurrency, scenario);
                                            control.println("ready " + !scenario.equals("not-ready"));
                                            control.println("snapshot " + id + "-setup");
                                            awaitFile(run.resolve(id + "-setup.properties"), child);
                                            runScenario(port, Integer.parseInt(size), Boolean.parseBoolean(outputDirect),
                                                        Boolean.parseBoolean(instrument),
                                                        protocol, connections, Integer.parseInt(concurrency), scenario,
                                                        results, run, id, control, child);
                                        }
                                    }
                                }
                            }
                            int probeSeconds = Integer.getInteger("probeSeconds", 60);
                            if (probeSeconds > 0) {
                                control.println("ready true");
                                control.println("snapshot probes-before");
                                awaitFile(run.resolve("probes-before.properties"), child);
                                try (HttpClient client = httpClient("h1")) {
                                    long start = System.nanoTime();
                                    for (int second = 0; second < probeSeconds; second++) {
                                        TimeUnit.NANOSECONDS.sleep(Math.max(0, start + TimeUnit.SECONDS.toNanos(second)
                                                                              - System.nanoTime()));
                                        if (second % 10 == 0) { probe(client, port, "/proxy/health"); }
                                        if (second % 2 == 0) { probe(client, port, "/proxy/ready"); }
                                    }
                                }
                                control.println("snapshot probes-after");
                                awaitFile(run.resolve("probes-after.properties"), child);
                                control.println("ready false");
                                control.println("snapshot not-ready-probes-before");
                                awaitFile(run.resolve("not-ready-probes-before.properties"), child);
                                try (HttpClient client = httpClient("h1")) {
                                    long start = System.nanoTime();
                                    for (int second = 0; second < probeSeconds; second += 2) {
                                        TimeUnit.NANOSECONDS.sleep(Math.max(0, start + TimeUnit.SECONDS.toNanos(second)
                                                                              - System.nanoTime()));
                                        request(client, port, Integer.parseInt(size), "h1", "close", "not-ready", second);
                                    }
                                }
                                control.println("snapshot not-ready-probes-after");
                                awaitFile(run.resolve("not-ready-probes-after.properties"), child);
                            }
                            control.println("stop");
                            if (!child.waitFor(20, TimeUnit.SECONDS) || child.exitValue() != 0) {
                                throw new IllegalStateException("Child failed: " + run);
                            }
                        } finally {
                            child.destroy();
                            if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); }
                        }
                    }
                }
            }
        }
    }

    private static void runScenario(int port, int size, boolean outputDirect, boolean instrument, String protocol,
                                    String connections, int concurrency, String scenario, PrintWriter results,
                                    Path run, String id, PrintWriter control, Process child) throws Exception {
        if (!(protocol.equals("h1") || protocol.equals("h2"))
            || !(connections.equals("close") || connections.equals("keep-alive")) || concurrency < 1) {
            throw new IllegalArgumentException("Invalid protocol/connection/concurrency");
        }
        int count = Integer.getInteger("requests", 1000);
        int warmup = Integer.getInteger("warmup", 100);
        if (count < 1 || warmup < 1) { throw new IllegalArgumentException("requests and warmup must be positive"); }
        try (HttpClient client = httpClient(protocol);
             var executor = (java.util.concurrent.ThreadPoolExecutor) Executors.newFixedThreadPool(concurrency)) {
            executor.prestartAllCoreThreads();
            for (int i = 0; i < warmup; i++) {
                request(client, port, size, protocol, connections, scenario, i);
                if (i % 32 == 31 || i == warmup - 1) {
                    String cleared = id + "-warmup-" + i;
                    control.println("clear " + cleared);
                    awaitFile(run.resolve(cleared), child);
                }
            }
            control.println("snapshot " + id + "-before");
            awaitFile(run.resolve(id + "-before.properties"), child);
            long[] latency = new long[count];
            long elapsed = 0;
            int batchSize = Math.max(32, concurrency);
            for (int batchStart = 0; batchStart < count; batchStart += batchSize) {
                int batchEnd = Math.min(count, batchStart + batchSize);
                AtomicInteger next = new AtomicInteger(batchStart);
                long start = System.nanoTime();
                List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
                for (int worker = 0; worker < concurrency; worker++) {
                    workers.add(executor.submit(() -> {
                        for (int i; (i = next.getAndIncrement()) < batchEnd;) {
                            long before = System.nanoTime();
                            try {
                                request(client, port, size, protocol, connections, scenario, i);
                            } catch (Exception e) { throw new IllegalStateException(e); }
                            latency[i] = System.nanoTime() - before;
                        }
                    }));
                }
                for (var worker : workers) { worker.get(5, TimeUnit.MINUTES); }
                elapsed += System.nanoTime() - start;
                // The local runtime log is not the subject of this experiment. Clear only after
                // every request in the batch completed, outside measured time, without forcing GC.
                String cleared = id + "-cleared-" + batchStart;
                control.println("clear " + cleared);
                awaitFile(run.resolve(cleared), child);
            }
            double seconds = elapsed / 1e9;
            control.println("snapshot " + id + "-after");
            awaitFile(run.resolve(id + "-after.properties"), child);
            Arrays.sort(latency);
            long initialAcquires = -1;
            long overflowAcquires = -1;
            if (instrument && protocol.equals("h1")) {
                Properties before = readProperties(run.resolve(id + "-before.properties"));
                Properties after = readProperties(run.resolve(id + "-after.properties"));
                String memory = outputDirect ? "direct" : "heap";
                initialAcquires = delta(before, after, "acquire.http1-send/" + memory + "/" + size);
                overflowAcquires = size < 1048576
                        ? delta(before, after, "acquire.http1-send/" + memory + "/1048576") : 0;
            }
            results.printf(java.util.Locale.ROOT, "%d,%s,%s,%s,%s,%d,%s,%d,%.6f,%.3f,%.3f,%.3f,%.3f,%d,%d%n",
                           size, outputDirect, instrument, protocol, connections, concurrency, scenario, count, seconds,
                           count / seconds,
                           percentile(latency, .5), percentile(latency, .95), percentile(latency, .99), initialAcquires, overflowAcquires);
            results.flush();
            System.out.printf("%s buffer=%d outputDirect=%s instrument=%s %.1f requests/s%n",
                              id, size, outputDirect, instrument, count / seconds);
        }
    }

    private static void request(HttpClient shared, int port, int size, String protocol, String connections,
                                String scenario, int index) throws Exception {
        // HTTP/2 forbids Connection: close; measure a new, primed HTTP/2 connection instead.
        boolean freshH2 = protocol.equals("h2") && connections.equals("close");
        HttpClient client = freshH2 ? httpClient(protocol) : shared;
        try {
            if (freshH2) { probe(client, port, "/proxy/health"); }
            String path = switch (scenario) {
                case "health" -> "/proxy/health";
                case "ready", "not-ready" -> "/proxy/ready";
                default -> "/buffer";
            };
            int largeHeader = 65536 + 512;
            int bytes = switch (scenario) {
                case "below" -> size - 512;
                case "above" -> Math.min(size + 512, 1048576 - 512);
                case "near-max" -> 1048576 - 512;
                case "mixed-1" -> index % 100 < 1 ? largeHeader : 16;
                case "mixed-10" -> index % 100 < 10 ? largeHeader : 16;
                case "mixed-100" -> largeHeader;
                default -> 16;
            };
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(30)).header("X-Header-Bytes", "" + bytes)
                    .header("X-Body", scenario.equals("large-body") ? "large" : "small");
            if (protocol.equals("h1") && connections.equals("close")) { builder.header("Connection", "close"); }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int expectedStatus = scenario.equals("not-ready") ? 503 : 200;
            if (response.statusCode() != expectedStatus) {
                throw new IllegalStateException("Status " + response.statusCode() + " for " + scenario);
            }
            // HTTP/2 first request may upgrade; warmup primes the shared connection.
            if (protocol.equals("h2") && response.version() != HttpClient.Version.HTTP_2) {
                throw new IllegalStateException("HTTP/2 negotiation failed");
            }
            if (path.equals("/buffer") && (response.headers().firstValue("X-Padding").orElseThrow().length() != bytes
                    || response.body().length != (scenario.equals("large-body") ? 2097152 : 2))) {
                throw new IllegalStateException("Response content mismatch");
            }
        } finally { if (freshH2) { client.close(); } }
    }

    private static void probe(HttpClient client, int port, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                           .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) { throw new IllegalStateException("Probe failed"); }
    }

    private static HttpClient httpClient(String protocol) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(protocol.equals("h2") ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1).build();
    }

    private static Properties readProperties(Path path) throws Exception {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(path)) { result.load(reader); }
        return result;
    }

    private static long delta(Properties before, Properties after, String key) {
        return Long.parseLong(after.getProperty(key, "0")) - Long.parseLong(before.getProperty(key, "0"));
    }

    private static double percentile(long[] values, double percentile) {
        return values[(int) Math.ceil(values.length * percentile) - 1] / 1000.0;
    }

    private static String[] csv(String key, String fallback) {
        return System.getProperty(key, fallback).split(",");
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void requireDriverJvmArguments() {
        List<String> actual = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> missing = BENCHMARK_JVM_ARGUMENTS.stream().filter(argument -> !actual.contains(argument)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Driver JVM must use the same settings as proxy children; missing "
                                               + missing);
        }
    }

    private static void awaitFile(Path file, Process child) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(file)) {
            if (!child.isAlive() || System.nanoTime() > deadline) {
                throw new IllegalStateException("Child not responding: " + file);
            }
            Thread.sleep(20);
        }
    }

    private static void child(Path output) throws Exception {
        try (var support = new HeaderBufferTestSupport(Boolean.getBoolean("instrument"), true);
             var reader = new BufferedReader(new InputStreamReader(System.in));
             var samples = new PrintWriter(Files.newBufferedWriter(output.resolve("memory.csv")));
             var sampler = Executors.newSingleThreadScheduledExecutor()) {
            samples.println("uptimeMillis,directCapacity,directUsed,heapUsed,gcCount,gcMillis,rssBytes,cgroupBytes,cgroupLimit");
            sampler.scheduleAtFixedRate(() -> {
                try {
                    Map<String, Long> values = memory();
                    samples.println(String.join(",", values.values().stream().map(Object::toString).toList()));
                    samples.flush();
                } catch (Exception e) { e.printStackTrace(); }
            }, 0, 1, TimeUnit.SECONDS);
            Files.writeString(output.resolve("port.tmp"), "" + support.proxy.getPort());
            Files.move(output.resolve("port.tmp"), output.resolve("port"));
            for (String line; (line = reader.readLine()) != null && !line.equals("stop");) {
                String[] parts = line.split(" ");
                if (parts[0].equals("clear")) {
                    support.clearCompletedMessages();
                    Files.writeString(output.resolve(parts[1]), "done");
                }
                if (parts[0].equals("ready")) { support.ready(Boolean.parseBoolean(parts[1])); }
                if (parts[0].equals("snapshot")) {
                    Properties snapshot = new Properties();
                    memory().forEach((k, v) -> snapshot.setProperty(k, v.toString()));
                    if (support.pool instanceof HeaderBufferPool pool) {
                        pool.snapshot().forEach((k, v) -> snapshot.setProperty("acquire." + k, v.toString()));
                        snapshot.setProperty("outstanding", "" + pool.outstanding());
                    }
                    snapshot.setProperty("java", Runtime.version().toString());
                    snapshot.setProperty("pid", "" + ProcessHandle.current().pid());
                    snapshot.setProperty("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
                    // NMT is sampled outside timed request phases. Preserve raw output rather than guessing categories.
                    try {
                        var mbs = ManagementFactory.getPlatformMBeanServer();
                        Object nmt = mbs.invoke(new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                                               "vmNativeMemory", new Object[]{new String[]{"summary", "scale=KB"}},
                                               new String[]{String[].class.getName()});
                        Files.writeString(output.resolve(parts[1] + "-nmt.txt"), nmt.toString());
                    } catch (Exception e) { snapshot.setProperty("nmtUnavailable", e.toString()); }
                    Path temporary = output.resolve(parts[1] + ".tmp");
                    try (var writer = Files.newBufferedWriter(temporary)) { snapshot.store(writer, "Server JVM snapshot: proxy and local SDK application"); }
                    Files.move(temporary, output.resolve(parts[1] + ".properties"));
                }
            }
            sampler.shutdownNow();
            sampler.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Map<String, Long> memory() throws Exception {
        Map<String, Long> values = new java.util.LinkedHashMap<>();
        values.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        var direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(p -> p.getName().equals("direct")).findFirst().orElseThrow();
        values.put("directCapacity", direct.getTotalCapacity());
        values.put("directUsed", direct.getMemoryUsed());
        values.put("heapUsed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        values.put("gcCount", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g -> g.getCollectionCount()).sum());
        values.put("gcMillis", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g -> g.getCollectionTime()).sum());
        long rss = -1;
        Path status = Path.of("/proc/self/status");
        if (Files.isReadable(status)) {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) { rss = Long.parseLong(line.trim().split("\\s+")[1]) * 1024; }
            }
        }
        values.put("rssBytes", rss);
        var container = new ContainerCollector().collect();
        values.put("cgroupBytes", container.map(c -> c.getMemoryUsageBytes()).orElse(-1L));
        values.put("cgroupLimit", container.map(c -> c.getMemoryLimitBytes()).orElse(-1L));
        return values;
    }
}
