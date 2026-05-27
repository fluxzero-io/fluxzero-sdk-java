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

package io.fluxzero.common.handling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerInspectorTest {

    private Handler<Object> subject;
    private Foo foo;

    @BeforeEach
    void setUp() {
        foo = new Foo();
        subject = HandlerInspector.createHandler(foo, Handle.class);
    }

    @Test
    void testFindInvoker() {
        assertTrue(subject.getInvoker(100L).isPresent());
        assertTrue(subject.getInvoker("bla").isPresent());
        assertTrue(subject.getInvoker(50).isPresent());
        assertTrue(subject.getInvoker(4f).isPresent());
        assertFalse(subject.getInvoker('b').isPresent());
        assertFalse(subject.getInvoker(foo).isPresent());
    }

    @Test
    void testHandleInPrivateMethod() {
        assertEquals(42, subject.getInvoker(true).orElseThrow().invoke());
    }

    @Test
    void testInvoke() {
        assertEquals(200L, subject.getInvoker(200L).orElseThrow().invoke());
        assertEquals("a", subject.getInvoker("a").orElseThrow().invoke());
        assertEquals(15, subject.getInvoker(15).orElseThrow().invoke());
    }

    @Test
    void testInvokeExceptionally() {
        assertThrows(UnsupportedOperationException.class, () -> subject.getInvoker(3f).orElseThrow().invoke());
    }

    @Test
    void testInvokeUnknownType() {
        assertThrows(Exception.class, () -> subject.getInvoker('b').orElseThrow().invoke());
    }

    @Test
    void testMetaAnnotationHandler() {
        subject = HandlerInspector.createHandler(new Meta(), Handle.class);
        assertEquals("a", subject.getInvoker("a").orElseThrow().invoke());
    }

    @Test
    void preparesComposedMessageFilterOncePerHandlerMethod() {
        PreparingFilter first = new PreparingFilter(Optional.empty());
        PreparingFilter second = new PreparingFilter(Optional.of(String.class));

        Handler<Object> handler = HandlerInspector.createHandler(
                new Filtered(), List.of((parameter, annotation) -> message -> message),
                HandlerConfiguration.<Object>builder()
                        .methodAnnotation(Handle.class)
                        .messageFilter(first.and(second))
                        .build());

        assertEquals(1, first.prepareCount);
        assertEquals(1, second.prepareCount);
        assertEquals(1, first.preparedLeastSpecificCount);
        assertEquals(1, second.preparedLeastSpecificCount);
        assertEquals(0, first.directLeastSpecificCount);
        assertEquals(0, second.directLeastSpecificCount);

        assertEquals("ok", handler.getInvoker("ok").orElseThrow().invoke());
        assertFalse(handler.getInvoker(42).isPresent());

        assertEquals(0, first.directTestCount);
        assertEquals(0, second.directTestCount);
        assertEquals(2, first.preparedTestCount);
        assertEquals(1, second.preparedTestCount);
    }

    @Test
    void fixedSingleMethodHandlerExposesReusableHandlerMethod() {
        Handler<Object> handler = HandlerInspector.createHandler(
                new SingleMethod(), List.of(new PreparedIdentityResolver()),
                HandlerConfiguration.<Object>builder()
                        .methodAnnotation(Handle.class)
                        .messageFilter((message, executable, annotation, targetClass) -> message instanceof String)
                        .build());

        HandlerMethod<Object> first = handler.getHandlerMethodOrNull("first");
        HandlerMethod<Object> second = handler.getHandlerMethodOrNull("second");

        assertNotNull(first);
        assertSame(first, second);
        assertEquals("first!", first.invoke("first"));
        assertEquals("second!", first.invoke("second"));
        assertNull(handler.getHandlerMethodOrNull(42));
    }

    @Test
    void multiMethodHandlerKeepsUsingPerMessageInvokers() {
        assertNull(subject.getHandlerMethodOrNull(100L));
        assertEquals(100L, subject.getInvoker(100L).orElseThrow().invoke());
    }

    @Test
    void dynamicTargetHandlerDoesNotExposeReusableHandlerMethod() {
        Handler<Object> handler = HandlerInspector.createHandler(
                message -> new SingleMethod(), SingleMethod.class, List.of(new PreparedIdentityResolver()),
                HandlerConfiguration.builder().methodAnnotation(Handle.class).build());

        assertNull(handler.getHandlerMethodOrNull("first"));
        assertEquals("first!", handler.getInvoker("first").orElseThrow().invoke());
    }

    private static class Foo extends Bar implements SomeInterface {
        @Handle
        @Override
        public Object handle(Long o) {
            return o;
        }

        @Handle
        @Override
        public Integer handle(Integer o) {
            return o;
        }

        @Handle
        private Object handle(Boolean o) {
            return 42;
        }

        @Handle
        public void handleAndThrowException(Float f) {
            throw new UnsupportedOperationException("yup");
        }
    }

    private static class Bar {
        @Handle
        public Object handle(String o) {
            return o;
        }

        @Handle
        public Object handle(Long o) {
            return null;
        }

        @Handle
        public void handleAndThrowException(Integer ignored) {
            throw new UnsupportedOperationException("should not happen");
        }
    }

    private static class Filtered {
        @Handle
        String handle(String value) {
            return value;
        }
    }

    private static class SingleMethod {
        @Handle
        String handle(String value) {
            return value + "!";
        }
    }

    private static class PreparedIdentityResolver implements ParameterResolver<Object> {
        @Override
        public Function<Object, Object> resolve(java.lang.reflect.Parameter parameter,
                                                java.lang.annotation.Annotation methodAnnotation) {
            return message -> message;
        }

        @Override
        public Function<Object, Object> prepare(java.lang.reflect.Parameter parameter,
                                                java.lang.annotation.Annotation methodAnnotation) {
            return message -> message;
        }
    }

    private static class PreparingFilter implements MessageFilter<Object> {
        private final Optional<Class<?>> leastSpecificAllowedClass;
        private int prepareCount;
        private int directTestCount;
        private int preparedTestCount;
        private int directLeastSpecificCount;
        private int preparedLeastSpecificCount;

        private PreparingFilter(Optional<Class<?>> leastSpecificAllowedClass) {
            this.leastSpecificAllowedClass = leastSpecificAllowedClass;
        }

        @Override
        public boolean test(Object message, Executable executable,
                            Class<? extends java.lang.annotation.Annotation> handlerAnnotation, Class<?> targetClass) {
            directTestCount++;
            return false;
        }

        @Override
        public MessageFilter<? super Object> prepare(Executable executable,
                                                     Class<? extends java.lang.annotation.Annotation> handlerAnnotation,
                                                     Class<?> targetClass) {
            prepareCount++;
            assertEquals(Handle.class, handlerAnnotation);
            assertEquals(Filtered.class, targetClass);
            assertEquals("handle", executable.getName());
            return new MessageFilter<>() {
                @Override
                public boolean test(Object message, Executable e,
                                    Class<? extends java.lang.annotation.Annotation> annotation, Class<?> type) {
                    preparedTestCount++;
                    assertEquals(executable, e);
                    assertEquals(handlerAnnotation, annotation);
                    assertEquals(targetClass, type);
                    return message instanceof String;
                }

                @Override
                public Optional<Class<?>> getLeastSpecificAllowedClass(
                        Executable e, Class<? extends java.lang.annotation.Annotation> annotation) {
                    preparedLeastSpecificCount++;
                    assertEquals(executable, e);
                    assertEquals(handlerAnnotation, annotation);
                    return leastSpecificAllowedClass;
                }
            };
        }

        @Override
        public Optional<Class<?>> getLeastSpecificAllowedClass(
                Executable executable, Class<? extends java.lang.annotation.Annotation> handlerAnnotation) {
            directLeastSpecificCount++;
            return leastSpecificAllowedClass;
        }
    }

    private static class Meta {
        @MetaHandle
        public Object handle(String o) {
            return o;
        }
    }

    private interface SomeInterface {
        Integer handle(Integer o);
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    public @interface Handle {
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Handle
    public @interface MetaHandle {
    }

}
