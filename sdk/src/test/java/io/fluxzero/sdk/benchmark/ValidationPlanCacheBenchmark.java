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
import io.fluxzero.sdk.tracking.handling.validation.DefaultValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;

/**
 * Cross-version benchmark for constraintless and constrained validation plans.
 */
public class ValidationPlanCacheBenchmark {
    private static final int keyCount = Integer.getInteger("keys", 128);
    private static final int iterations = Integer.getInteger("iterations", 1_000_000);
    private static final int warmupIterations = Integer.getInteger("warmupIterations", 250_000);
    private static final int warmups = Integer.getInteger("warmups", 3);

    private static final DefaultValidator validator = DefaultValidator.createDefault();
    private static final Unconstrained[] unconstrained = createUnconstrained();
    private static final SimpleConstrained[] constrained = createConstrained();
    private static final ConstrainedContract[] constrainedShapes = createConstrainedShapes();
    private static final Cascaded[] cascaded = createCascaded();
    private static final ThreadMXBean allocationBean = allocationBean();
    private static volatile long blackhole;

    public static void main(String[] args) {
        if (Integer.bitCount(keyCount) != 1 || keyCount > 128) {
            throw new IllegalArgumentException("keys must be a power of two up to 128");
        }
        System.out.printf("config keys=%d iterations=%d warmups=%d%n", keyCount, iterations, warmups);
        for (int i = 0; i < warmups; i++) {
            validate(unconstrained, warmupIterations);
            validate(constrained, warmupIterations);
            validate(constrainedShapes, warmupIterations);
            validate(cascaded, warmupIterations);
        }
        measure("constraintless-one-class", count -> validate(unconstrained, count));
        measure("constrained-one-class", count -> validate(constrained, count));
        measure("constrained-128-classes", count -> validate(constrainedShapes, count));
        measure("cascaded-one-class", count -> validate(cascaded, count));
        System.out.println("blackhole=" + blackhole);
    }

    private static void validate(Object[] values, int count) {
        long result = 0L;
        int mask = keyCount - 1;
        for (int i = 0; i < count; i++) {
            Object value = values[(i * 73) & mask];
            validator.assertValid(value);
            result += value.hashCode();
        }
        blackhole = result;
    }

    private static void measure(String name, java.util.function.IntConsumer scenario) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        scenario.accept(iterations);
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%s: %.2f ns/op, %.2f bytes/op, %.1f M ops/s%n", name,
                          (double) elapsed / iterations, (double) allocated / iterations,
                          iterations * 1_000d / elapsed);
    }

    private static Unconstrained[] createUnconstrained() {
        Unconstrained[] result = new Unconstrained[keyCount];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Unconstrained("value-" + i);
        }
        return result;
    }

    private static SimpleConstrained[] createConstrained() {
        SimpleConstrained[] result = new SimpleConstrained[keyCount];
        for (int i = 0; i < result.length; i++) {
            result[i] = new SimpleConstrained("value-" + i);
        }
        return result;
    }

    private static Cascaded[] createCascaded() {
        Cascaded[] result = new Cascaded[keyCount];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Cascaded(new SimpleConstrained("value-" + i));
        }
        return result;
    }

    private static ConstrainedContract[] createConstrainedShapes() {
        Class<?>[] bits = {Shape0.class, Shape1.class, Shape2.class, Shape3.class,
                           Shape4.class, Shape5.class, Shape6.class};
        ConstrainedContract[] result = new ConstrainedContract[keyCount];
        for (int key = 0; key < keyCount; key++) {
            String value = "value-" + key;
            Class<?>[] interfaces = new Class<?>[1 + Integer.bitCount(key)];
            interfaces[0] = ConstrainedContract.class;
            int next = 1;
            for (int bit = 0; bit < bits.length; bit++) {
                if ((key & 1 << bit) != 0) {
                    interfaces[next++] = bits[bit];
                }
            }
            result[key] = (ConstrainedContract) Proxy.newProxyInstance(
                    ValidationPlanCacheBenchmark.class.getClassLoader(), interfaces,
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getValue", "toString" -> value;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
        return result;
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean threadBean) || !threadBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }
        return threadBean;
    }

    private record Unconstrained(String value) {
    }

    private record SimpleConstrained(@NotNull String value) {
    }

    private record Cascaded(@Valid SimpleConstrained value) {
    }

    public interface ConstrainedContract {
        @NotNull
        String getValue();
    }

    public interface Shape0 {
    }

    public interface Shape1 {
    }

    public interface Shape2 {
    }

    public interface Shape3 {
    }

    public interface Shape4 {
    }

    public interface Shape5 {
    }

    public interface Shape6 {
    }
}
