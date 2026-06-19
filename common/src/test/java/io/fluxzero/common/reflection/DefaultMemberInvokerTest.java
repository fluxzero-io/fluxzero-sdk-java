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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void readsAnnotationAttributeFromDifferentClassLoaderWithoutLambdaWarning() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultMemberInvoker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        URL testClasses = DefaultMemberInvokerTest.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolatedLoader = new URLClassLoader(new URL[] { testClasses }, null)) {
            Class<?> annotationType = Class.forName(IsolatedAnnotation.class.getName(), true, isolatedLoader);
            Annotation annotation = (Annotation) Proxy.newProxyInstance(isolatedLoader, new Class<?>[] {
                    annotationType }, (proxy, method, args) -> switch (method.getName()) {
                case "annotationType" -> annotationType;
                case "value" -> "isolated";
                case "toString" -> "@IsolatedAnnotation(\"isolated\")";
                case "hashCode" -> 0;
                case "equals" -> proxy == args[0];
                default -> method.getDefaultValue();
            });

            assertEquals("isolated", ReflectionUtils.getAnnotationAttribute(annotation, "value", String.class)
                    .orElseThrow());
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage()
                .contains("Failed to create lambda type method invoke")));
    }

    private @interface IsolatedAnnotation {
        String value();
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
