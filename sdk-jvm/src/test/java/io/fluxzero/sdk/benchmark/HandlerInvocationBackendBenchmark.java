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

package io.fluxzero.sdk.benchmark;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.ExecutableInvocation;
import io.fluxzero.common.handling.ExecutableInvocationBackend;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.PayloadFilter;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Benchmark for the invocation layer used by generated handler metadata.
 * <p>
 * Run with:
 * <pre>{@code
 * ./mvnw -q -pl sdk-jvm -DskipTests test-compile exec:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=io.fluxzero.sdk.benchmark.HandlerInvocationBackendBenchmark \
 *   -Diterations=2000000 -Dwarmup=2
 * }</pre>
 */
public class HandlerInvocationBackendBenchmark {
    private static final int ITERATIONS = Integer.getInteger("iterations", 2_000_000);
    private static final int WARM_UP = Integer.getInteger("warmup", 2);
    private static final JacksonSerializer serializer = new JacksonSerializer();
    private static final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers =
            List.of(new PayloadParameterResolver());
    private static final BenchmarkCommand command = new BenchmarkCommand("benchmark");
    private static final DeserializingMessage commandMessage = new DeserializingMessage(
            new Message(command), MessageType.COMMAND, serializer);
    private static final Object[] sink = new Object[1024];
    private static volatile long blackhole;

    public static void main(String[] args) throws Exception {
        Method method = BenchmarkHandler.class.getDeclaredMethod("handle", BenchmarkCommand.class);
        method.setAccessible(true);

        MemberInvoker lambdaInvoker = DefaultMemberInvoker.asInvoker(method);
        ExecutableInvocation lambdaInvocation = ExecutableInvocationBackend.reflection().prepare(method);
        ExecutableInvocation reflectionInvocation = plainReflectionBackend().prepare(method);

        try (var ignored = GeneratedExecutableInvocations.register(
                BenchmarkHandler.class, GeneratedExecutableInvocations.executableId(method), lambdaInvocation)) {
            ExecutableInvocation generatedRegistryInvocation = GeneratedExecutableInvocations.find(method).orElseThrow();
            HandlerMatcher<Object, DeserializingMessage> lambdaMatcher = handlerMatcher(
                    ExecutableInvocationBackend.reflection());
            HandlerMatcher<Object, DeserializingMessage> reflectionMatcher = handlerMatcher(plainReflectionBackend());
            HandlerMatcher<Object, DeserializingMessage> generatedRegistryMatcher = handlerMatcher(
                    executable -> GeneratedExecutableInvocations.find(executable).orElseThrow());
            HandlerMatcher<Object, DeserializingMessage> generatedMetadataMatcher = HandlerInspector.inspectViews(
                    BenchmarkHandler.class,
                    List.of(ExecutableView.of(method)),
                    ignoredView -> generatedRegistryInvocation,
                    parameterResolvers,
                    handlerConfiguration(executable -> generatedRegistryInvocation));

            List<Scenario> scenarios = List.of(
                    new Scenario("direct.method", target -> target.handle(command)),
                    new Scenario("raw.lambdaMemberInvoker", target -> lambdaInvoker.invoke(target, command)),
                    new Scenario("raw.lambdaExecutableInvocation",
                                 target -> lambdaInvocation.invoke(target, command)),
                    new Scenario("raw.generatedRegistryInvocation",
                                 target -> generatedRegistryInvocation.invoke(target, command)),
                    new Scenario("raw.plainReflectionInvocation",
                                 target -> reflectionInvocation.invoke(target, command)),
                    new Scenario("handler.lambda.getInvokerOrNull+invoke",
                                 target -> lambdaMatcher.getInvokerOrNull(target, commandMessage).invoke()),
                    new Scenario("handler.generatedRegistry.getInvokerOrNull+invoke",
                                 target -> generatedRegistryMatcher.getInvokerOrNull(target, commandMessage)
                                         .invoke()),
                    new Scenario("handler.generatedMetadata.getInvokerOrNull+invoke",
                                 target -> generatedMetadataMatcher.getInvokerOrNull(target, commandMessage)
                                         .invoke()),
                    new Scenario("handler.plainReflection.getInvokerOrNull+invoke",
                                 target -> reflectionMatcher.getInvokerOrNull(target, commandMessage).invoke()),
                    cachedScenario("handler.lambda.cachedInvoker.invoke", lambdaMatcher),
                    cachedScenario("handler.generatedRegistry.cachedInvoker.invoke", generatedRegistryMatcher),
                    cachedScenario("handler.generatedMetadata.cachedInvoker.invoke", generatedMetadataMatcher),
                    cachedScenario("handler.plainReflection.cachedInvoker.invoke", reflectionMatcher));

            System.out.printf(
                    Locale.ROOT, "HandlerInvocationBackendBenchmark config: iterations=%d, warmup=%d%n",
                    ITERATIONS, WARM_UP);
            for (int i = 0; i < WARM_UP; i++) {
                for (Scenario scenario : scenarios) {
                    runScenario(scenario);
                }
            }

            List<Measurement> measurements = new ArrayList<>();
            for (Scenario scenario : scenarios) {
                measurements.add(runScenario(scenario));
            }
            print(measurements);
        }
    }

    private static HandlerMatcher<Object, DeserializingMessage> handlerMatcher(ExecutableInvocationBackend backend) {
        return HandlerInspector.inspect(BenchmarkHandler.class, parameterResolvers, handlerConfiguration(backend));
    }

    private static HandlerConfiguration<DeserializingMessage> handlerConfiguration(ExecutableInvocationBackend backend) {
        return HandlerConfiguration.<DeserializingMessage>builder()
                .methodAnnotation(HandleCommand.class)
                .messageFilter(new PayloadFilter())
                .executableInvocationBackend(backend)
                .build();
    }

    private static Scenario cachedScenario(
            String name, HandlerMatcher<Object, DeserializingMessage> matcher) {
        BenchmarkHandler target = new BenchmarkHandler();
        HandlerInvoker invoker = matcher.getInvokerOrNull(target, commandMessage);
        return new Scenario(name, ignored -> invoker.invoke(), target);
    }

    private static Measurement runScenario(Scenario scenario) {
        BenchmarkHandler target = scenario.target();
        long started = System.nanoTime();
        long checksum = 0L;
        for (int i = 0; i < ITERATIONS; i++) {
            Object result = scenario.invocation().invoke(target);
            sink[i & (sink.length - 1)] = result;
            checksum += result.hashCode();
        }
        long nanos = System.nanoTime() - started;
        blackhole = checksum + target.state() + observeSink();
        return new Measurement(scenario.name(), nanos);
    }

    private static void print(List<Measurement> measurements) {
        System.out.printf("%-50s %10s %12s%n", "measurement", "millis", "ops/sec");
        System.out.printf("%-50s %10s %12s%n", "-----------", "------", "-------");
        for (Measurement measurement : measurements) {
            double millis = measurement.nanos() / 1_000_000.0;
            double opsPerSecond = ITERATIONS / (measurement.nanos() / 1_000_000_000.0);
            System.out.printf(Locale.ROOT, "%-50s %10.3f %12.1f%n",
                              measurement.name(), millis, opsPerSecond);
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static long observeSink() {
        long result = 0L;
        for (Object value : sink) {
            result += value == null ? 0 : value.hashCode();
        }
        return result;
    }

    private static ExecutableInvocationBackend plainReflectionBackend() {
        return executable -> {
            if (executable instanceof AccessibleObject accessibleObject) {
                accessibleObject.setAccessible(true);
            }
            if (executable instanceof Method method) {
                return (target, parameterCount, parameterProvider) -> invokeMethod(
                        method, target, parameterCount, parameterProvider);
            }
            if (executable instanceof Constructor<?> constructor) {
                return (target, parameterCount, parameterProvider) -> invokeConstructor(
                        constructor, parameterCount, parameterProvider);
            }
            throw new UnsupportedOperationException("Unsupported executable: " + executable);
        };
    }

    private static Object invokeMethod(
            Method method, Object target, int parameterCount, IntFunction<?> parameterProvider) {
        try {
            return method.invoke(target, arguments(parameterCount, parameterProvider));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke " + method, e);
        }
    }

    private static Object invokeConstructor(
            Constructor<?> constructor, int parameterCount, IntFunction<?> parameterProvider) {
        try {
            return constructor.newInstance(arguments(parameterCount, parameterProvider));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke " + constructor, e);
        }
    }

    private static Object[] arguments(int parameterCount, IntFunction<?> parameterProvider) {
        Object[] result = new Object[parameterCount];
        for (int i = 0; i < parameterCount; i++) {
            result[i] = parameterProvider.apply(i);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <E extends RuntimeException> E rethrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record Scenario(String name, BenchmarkInvocation invocation, BenchmarkHandler target) {
        private Scenario(String name, BenchmarkInvocation invocation) {
            this(name, invocation, new BenchmarkHandler());
        }
    }

    @FunctionalInterface
    private interface BenchmarkInvocation {
        Object invoke(BenchmarkHandler target);
    }

    private record Measurement(String name, long nanos) {
    }

    record BenchmarkCommand(String value) {
    }

    public static class BenchmarkHandler {
        private long state = 0x9E3779B97F4A7C15L;

        @HandleCommand
        String handle(BenchmarkCommand command) {
            long next = state;
            next ^= next << 13;
            next ^= next >>> 7;
            next ^= next << 17;
            state = next;
            return command.value();
        }

        long state() {
            return state;
        }
    }
}
