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

import io.fluxzero.common.TimingUtils;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.MemberInvoker;
import lombok.SneakyThrows;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;

import static io.fluxzero.common.handling.HandlerInspector.createHandler;

class MethodInvokerBenchmark {
    private static final long iterations = 100_000_000L;
    private static final int WARM_UP = 2;
    private static final Object[] objectSink = new Object[1024];
    private static volatile long blackhole;

    public static void main(String[] args) throws Throwable {
        Person target = new Person("Ann");
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Method method = Person.class.getDeclaredMethod("name");
        MethodHandle realMethodHandle = lookup.unreflect(method);
        MemberInvoker invoker = DefaultMemberInvoker.asInvoker(method);
        Handler<Object> fluxzeroHandler = createHandler(target, Handle.class);
        HandlerInvoker fluxzeroInvoker = fluxzeroHandler.getInvoker(null).orElseThrow();

        System.out.println("Invocation result of lambda: " + invoker.invoke(target));

        System.out.println("warming up");

        for (int i = 0; i < WARM_UP; i++) {
            testDirect(target);
            testInvoker(invoker, target);
            testMessageHandle(realMethodHandle, target);
            testReflection(method, target);
            testFluxzeroInvoker(fluxzeroInvoker);
            testFluxzeroHandler(fluxzeroHandler);
        }

        System.out.println("starting");

        TimingUtils.time(() -> testDirect(target), ms -> print("direct", ms));
        TimingUtils.time(() -> testReflection(method, target), ms -> print("reflection", ms));
        TimingUtils.time(() -> testMessageHandle(realMethodHandle, target), ms -> print("method handle", ms));
        TimingUtils.time(() -> testInvoker(invoker, target), ms -> print("lambda invoker", ms));
        TimingUtils.time(() -> testFluxzeroInvoker(fluxzeroInvoker),
                         elapsed -> print("fluxzero invoker", elapsed), ChronoUnit.MILLIS);
        TimingUtils.time(() -> testFluxzeroHandler(fluxzeroHandler), ms -> print("fluxzero handler", ms));

    }

    private static void print(String label, long elapsedMillis) {
        System.out.printf("%s: %dms [%d], ", label, elapsedMillis, blackhole);
    }

    private static void testFluxzeroInvoker(HandlerInvoker invoker) {
        long checksum = 0L;
        for (long i = 0; i < iterations; i++) {
            Object result = invoker.invoke();
            consume(i, result);
            checksum += ((String) result).length();
        }
        blackhole = checksum + observeSink();
    }

    private static void testDirect(Person target) {
        long checksum = 0L;
        for (long i = 0; i < iterations; i++) {
            String result = target.name();
            consume(i, result);
            checksum += result.length();
        }
        blackhole = checksum + target.invocations() + observeSink();
    }

    private static void testInvoker(MemberInvoker invoker, Person target) {
        long checksum = 0L;
        for (long i = 0; i < iterations; i++) {
            Object result = invoker.invoke(target);
            consume(i, result);
            checksum += ((String) result).length();
        }
        blackhole = checksum + target.invocations() + observeSink();
    }

    private static void testFluxzeroHandler(Handler<Object> handler) {
        long checksum = 0L;
        for (long i = 0; i < iterations; i++) {
            Object result = handler.getInvoker(null).orElseThrow().invoke();
            consume(i, result);
            checksum += ((String) result).length();
        }
        blackhole = checksum + observeSink();
    }

    @SneakyThrows
    private static void testReflection(Method method, Person target) {
        long checksum = 0L;
        for (long i = 0; i < iterations; i++) {
            Object result = method.invoke(target);
            consume(i, result);
            checksum += ((String) result).length();
        }
        blackhole = checksum + target.invocations() + observeSink();
    }

    @SneakyThrows
    private static void testMessageHandle(MethodHandle mh, Person target) {
        long checksum = 0L;
        for (long i = 0; i < iterations; i++) {
            String result = (String) mh.invokeExact(target);
            consume(i, result);
            checksum += result.length();
        }
        blackhole = checksum + target.invocations() + observeSink();
    }

    private static void consume(long iteration, Object value) {
        objectSink[(int) iteration & (objectSink.length - 1)] = value;
    }

    private static long observeSink() {
        long result = 0L;
        for (Object value : objectSink) {
            result += value == null ? 0 : value.hashCode();
        }
        return result;
    }

    static class Person {
        private long i = 0;
        private long state = 0x9E3779B97F4A7C15L;
        private final String[] names;

        public Person(String name) {
            this.names = new String[]{name, name + "e", name + "a", name + "ette"};
        }

        @Handle
        private String name() {
            long next = state;
            next ^= next << 13;
            next ^= next >>> 7;
            next ^= next << 17;
            state = next;
            i++;
            return names[(int) next & (names.length - 1)];
        }

        private long invocations() {
            return i + state;
        }
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Handle {
    }
}
