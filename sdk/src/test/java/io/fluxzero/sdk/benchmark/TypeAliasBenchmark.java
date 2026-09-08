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

package io.fluxzero.sdk.benchmark;

import com.sun.management.ThreadMXBean;
import io.fluxzero.common.api.Data;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Measures exact and package type-alias resolution, including the no-match deserialization path. */
public class TypeAliasBenchmark {
    private static final int iterations = Integer.getInteger("iterations", 20_000_000);
    private static final int warmupIterations = Integer.getInteger("warmupIterations", 2_000_000);
    private static final int deserializationIterations = Integer.getInteger("deserializationIterations", 1_000_000);
    private static final int deserializationWarmupIterations =
            Integer.getInteger("deserializationWarmupIterations", 100_000);
    private static final int warmups = Integer.getInteger("warmups", 3);
    private static final String[] currentTypes = createTypes("io.fluxzero.current");
    private static final String[] legacyTypes = createTypes("host.example.legacy");
    private static final ThreadMXBean allocationBean = allocationBean();
    private static final Data<byte[]> serializedValue = new Data<>(
            "{\"value\":\"test\"}".getBytes(UTF_8), BenchmarkValue.class.getName(), 0, Data.JSON_FORMAT);
    private static volatile int blackhole;
    private static volatile Object objectBlackhole;

    public static void main(String[] args) {
        JacksonSerializer noAliases = new JacksonSerializer();
        JacksonSerializer exactAliases = new JacksonSerializer();
        JacksonSerializer packageAliases = new JacksonSerializer();
        JacksonSerializer sameInitialPackageAliases = new JacksonSerializer();
        JacksonSerializer matchingPackageAlias = new JacksonSerializer();
        for (int i = 0; i < 8; i++) {
            exactAliases.registerTypeCaster("legacy.Type" + i, "current.Type" + i);
            registerPackageAlias(packageAliases, "legacy.package" + i, "current.package" + i);
            registerPackageAlias(sameInitialPackageAliases, "io.legacy" + i, "io.current" + i);
        }
        registerPackageAlias(matchingPackageAlias, "host.example", "io.fluxzero");

        for (int i = 0; i < warmups; i++) {
            run(noAliases, currentTypes, warmupIterations);
            run(exactAliases, currentTypes, warmupIterations);
            run(packageAliases, currentTypes, warmupIterations);
            run(sameInitialPackageAliases, currentTypes, warmupIterations);
            run(matchingPackageAlias, legacyTypes, warmupIterations);
            runDeserialize(noAliases, deserializationWarmupIterations);
            runDeserialize(matchingPackageAlias, deserializationWarmupIterations);
        }

        System.out.printf("config iterations=%d warmups=%d packageAliasesSupported=%s%n",
                          iterations, warmups, packageAliasesSupported());
        measure("no-aliases-no-match", noAliases, currentTypes);
        measure("eight-exact-aliases-no-match", exactAliases, currentTypes);
        if (packageAliasesSupported()) {
            measure("eight-package-aliases-different-initial-no-match", packageAliases, currentTypes);
            measure("eight-package-aliases-same-initial-no-match", sameInitialPackageAliases, currentTypes);
            measure("package-alias-match", matchingPackageAlias, legacyTypes);
        }
        measureDeserialize("deserialize-no-aliases", noAliases);
        measureDeserialize("deserialize-package-alias-no-match", matchingPackageAlias);
        System.out.println("blackhole=" + blackhole);
        System.out.println("objectBlackhole=" + objectBlackhole);
    }

    private static void measure(String name, JacksonSerializer serializer, String[] types) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        run(serializer, types, iterations);
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%-54s %8.3f ns/op %8.3f bytes/op%n", name,
                          (double) elapsed / iterations, (double) allocated / iterations);
    }

    private static void run(JacksonSerializer serializer, String[] types, int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            result += serializer.upcastType(types[i & (types.length - 1)]).hashCode();
        }
        blackhole = result;
    }

    private static void measureDeserialize(String name, JacksonSerializer serializer) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        runDeserialize(serializer, deserializationIterations);
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%-54s %8.3f ns/op %8.3f bytes/op%n", name,
                          (double) elapsed / deserializationIterations,
                          (double) allocated / deserializationIterations);
    }

    private static void runDeserialize(JacksonSerializer serializer, int count) {
        Object result = null;
        for (int i = 0; i < count; i++) {
            result = serializer.deserialize(serializedValue);
        }
        objectBlackhole = result;
    }

    private static String[] createTypes(String packageName) {
        String[] result = new String[1024];
        for (int i = 0; i < result.length; i++) {
            result[i] = packageName + ".Type" + i;
        }
        return result;
    }

    private static void registerPackageAlias(JacksonSerializer serializer, String source, String target) {
        if (!packageAliasesSupported()) {
            return;
        }
        try {
            packageAliasMethod().invoke(serializer, source, target);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean packageAliasesSupported() {
        return packageAliasMethod() != null;
    }

    private static Method packageAliasMethod() {
        try {
            return JacksonSerializer.class.getMethod("registerPackageAlias", String.class, String.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static ThreadMXBean allocationBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
            || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        return bean;
    }

    private record BenchmarkValue(String value) {
    }
}
