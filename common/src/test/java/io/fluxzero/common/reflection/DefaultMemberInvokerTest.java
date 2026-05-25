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

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultMemberInvokerTest {

    @Test
    void invokesInstanceMethodWithoutParameters() throws Exception {
        var target = new InvokerTarget("initial");
        var invoker = DefaultMemberInvoker.asInvoker(InvokerTarget.class.getDeclaredMethod("name"));

        assertEquals("initial", invoker.invoke(target));
    }

    @Test
    void invokesInstanceMethodWithSingleParameter() throws Exception {
        var target = new InvokerTarget("initial");
        var invoker = DefaultMemberInvoker.asInvoker(InvokerTarget.class.getDeclaredMethod("rename", String.class));

        invoker.invoke(target, "changed");

        assertEquals("changed", target.name());
    }

    @Test
    void invokesStaticMethodWithSingleParameter() throws Exception {
        var invoker = DefaultMemberInvoker.asInvoker(
                InvokerTarget.class.getDeclaredMethod("staticEcho", String.class));

        assertEquals("echo:value", invoker.invoke(null, "value"));
    }

    @Test
    void invokesConstructorWithSingleParameter() throws Exception {
        var invoker = DefaultMemberInvoker.asInvoker(InvokerTarget.class.getDeclaredConstructor(String.class));

        var result = (InvokerTarget) invoker.invoke(null, "created");

        assertEquals("created", result.name());
    }

    @Test
    void invokesPrimitiveParameterViaFallback() throws Exception {
        var target = new InvokerTarget("initial");
        var invoker = DefaultMemberInvoker.asInvoker(InvokerTarget.class.getDeclaredMethod("addOne", int.class));

        assertEquals(42, invoker.invoke(target, 41));
    }

    private static class InvokerTarget {
        private String name;

        private InvokerTarget(String name) {
            this.name = name;
        }

        private String name() {
            return name;
        }

        private void rename(String name) {
            this.name = name;
        }

        private static String staticEcho(String value) {
            return "echo:" + value;
        }

        private int addOne(int value) {
            return value + 1;
        }
    }
}
