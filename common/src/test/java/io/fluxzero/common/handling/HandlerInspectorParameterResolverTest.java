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

import lombok.Value;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HandlerInspectorParameterResolverTest {

    private final Foo foo = new Foo();
    private final Handler<Message> subject = HandlerInspector
            .createHandler(foo, Handle.class, Arrays.asList((p, methodAnnotation) -> {
                if (p.getDeclaringExecutable().getParameters()[0] == p) {
                    return Message::getPayload;
                }
                return null;
            }, (p, methodAnnotation) -> {
                if (p.getType().equals(Instant.class)) {
                    return m -> Instant.now();
                }
                return null;
            }));

    @Test
    public void testFindInvoker() {
        assertTrue(subject.getInvoker(new Message("payload")).isPresent());
        assertTrue(subject.getInvoker(new Message(0L)).isPresent());
        assertFalse(subject.getInvoker(new Message(0)).isPresent());
    }

    @Test
    public void testInvoke() {
        Message message = new Message("payload");
        assertEquals("payload", subject.getInvoker(message).orElseThrow().invoke());
        message = new Message(100L);
        assertEquals(100L, subject.getInvoker(message).orElseThrow().invoke());
    }

    @Test
    void lowerSpecificityPriorityWinsOverResolverOrder() {
        Handler<SpecificityMessage> handler = HandlerInspector.createHandler(
                new SpecificityHandler(),
                Handle.class,
                List.of(new EntityResolver(), new PayloadResolver()));

        Object result = handler.getInvoker(new SpecificityMessage(new CreatePayment(), new Payment()))
                .orElseThrow()
                .invoke();

        assertEquals("payload", result);
    }

    @Test
    void preparedParameterResolverIsPreparedOncePerHandlerMethod() {
        PreparedCountingResolver resolver = new PreparedCountingResolver();
        Handler<Message> handler = HandlerInspector.createHandler(
                new PreparedHandler(), Handle.class, List.of(resolver));

        assertEquals(1, resolver.prepareCount);
        assertEquals("first", handler.getInvoker(new Message("first")).orElseThrow().invoke());
        assertEquals("second", handler.getInvoker(new Message("second")).orElseThrow().invoke());

        assertEquals(1, resolver.prepareCount);
        assertEquals(0, resolver.matchesCount);
        assertEquals(0, resolver.resolveCount);
    }

    @Test
    void keyedResolverCachesMoreThanOneHundredRecipesWithoutCachingValues() {
        KeyedCountingResolver resolver = new KeyedCountingResolver();
        Handler<KeyedMessage> handler = HandlerInspector.createHandler(
                new KeyedHandler(), Handle.class, List.of(resolver));

        for (int key = 0; key < 128; key++) {
            assertEquals("first-" + key, handler.getInvokerOrNull(
                    new KeyedMessage(key, "first-" + key)).invoke());
        }
        for (int key = 0; key < 128; key++) {
            assertEquals("second-" + key, handler.getInvokerOrNull(
                    new KeyedMessage(key, "second-" + key)).invoke());
        }

        assertEquals(128, resolver.resolutionCount.get());
    }

    @Test
    void keyedResolverCachesRejectionWithoutFallingThroughToLaterResolver() {
        RejectingKeyedResolver rejectingResolver = new RejectingKeyedResolver();
        FallbackResolver fallbackResolver = new FallbackResolver();
        Handler<KeyedMessage> handler = HandlerInspector.createHandler(
                new KeyedHandler(), Handle.class, List.of(rejectingResolver, fallbackResolver));
        KeyedMessage message = new KeyedMessage(1, "value");

        assertNull(handler.getInvokerOrNull(message));
        assertNull(handler.getInvokerOrNull(message));

        assertEquals(2, rejectingResolver.resolutionCount.get());
        assertEquals(0, fallbackResolver.matchesCount.get());
    }

    @Test
    void dynamicMessageFilterIsNeverBypassedByResolverSelectionCache() {
        Handler<KeyedMessage> handler = HandlerInspector.createHandler(
                new KeyedHandler(), List.of(new KeyedCountingResolver()),
                HandlerConfiguration.<KeyedMessage>builder().methodAnnotation(Handle.class)
                        .messageFilter((message, executable, annotation, targetClass) -> message.allowed())
                        .build());

        assertNotNull(handler.getInvokerOrNull(new KeyedMessage(1, "first", true)));
        assertNull(handler.getInvokerOrNull(new KeyedMessage(1, "blocked", false)));
        assertEquals("second", handler.getInvokerOrNull(new KeyedMessage(1, "second", true)).invoke());
    }

    private static class Foo {
        @Handle
        public Object handle(String o, Instant time) {
            assertNotNull(time);
            return o;
        }

        @Handle
        public Object handle(Long o, Instant time) {
            assertNotNull(time);
            return o;
        }
    }

    @Value
    private static class Message {
        Object payload;
    }

    @Value
    private static class SpecificityMessage {
        Object payload;
        Object entity;
    }

    private static class SpecificityHandler {
        @Handle
        Object handle(Payment payment) {
            return "entity";
        }

        @Handle
        Object handle(CreatePayment payment) {
            return "payload";
        }
    }

    private static class PreparedHandler {
        @Handle
        Object handle(String value) {
            return value;
        }
    }

    private record KeyedMessage(int key, String value, boolean allowed) {
        private KeyedMessage(int key, String value) {
            this(key, value, true);
        }
    }

    private static class KeyedHandler {
        @Handle
        Object handle(String value) {
            return value;
        }

        @Handle
        Object handle(Integer value) {
            throw new AssertionError("Integer handler should not match a String resolver value");
        }
    }

    private static class KeyedCountingResolver implements KeyedParameterResolver<KeyedMessage> {
        protected final AtomicInteger resolutionCount = new AtomicInteger();

        @Override
        public Function<KeyedMessage, Object> resolve(java.lang.reflect.Parameter parameter,
                                                      java.lang.annotation.Annotation methodAnnotation) {
            return KeyedMessage::value;
        }

        @Override
        public Object getCacheKey(KeyedMessage value) {
            return value.key();
        }

        @Override
        public Resolution<KeyedMessage> resolveForKey(
                java.lang.reflect.Parameter parameter, java.lang.annotation.Annotation methodAnnotation,
                KeyedMessage value, Object cacheKey) {
            resolutionCount.incrementAndGet();
            return String.class.equals(parameter.getType())
                    ? Resolution.resolved(KeyedMessage::value) : Resolution.unmatched();
        }
    }

    private static class RejectingKeyedResolver extends KeyedCountingResolver {
        @Override
        public Resolution<KeyedMessage> resolveForKey(
                java.lang.reflect.Parameter parameter, java.lang.annotation.Annotation methodAnnotation,
                KeyedMessage value, Object cacheKey) {
            resolutionCount.incrementAndGet();
            return Resolution.rejected();
        }
    }

    private static class FallbackResolver implements ParameterResolver<KeyedMessage> {
        private final AtomicInteger matchesCount = new AtomicInteger();

        @Override
        public Function<KeyedMessage, Object> resolve(java.lang.reflect.Parameter parameter,
                                                      java.lang.annotation.Annotation methodAnnotation) {
            return KeyedMessage::value;
        }

        @Override
        public boolean matches(java.lang.reflect.Parameter parameter,
                               java.lang.annotation.Annotation methodAnnotation, KeyedMessage value) {
            matchesCount.incrementAndGet();
            return true;
        }
    }

    private static class PreparedCountingResolver implements ParameterResolver<Message> {
        private int prepareCount;
        private int matchesCount;
        private int resolveCount;

        @Override
        public Function<Message, Object> resolve(java.lang.reflect.Parameter parameter,
                                                 java.lang.annotation.Annotation methodAnnotation) {
            resolveCount++;
            return null;
        }

        @Override
        public Function<Message, Object> prepare(java.lang.reflect.Parameter parameter,
                                                 java.lang.annotation.Annotation methodAnnotation) {
            if (String.class.equals(parameter.getType())) {
                prepareCount++;
                return Message::getPayload;
            }
            return null;
        }

        @Override
        public boolean matches(java.lang.reflect.Parameter parameter, java.lang.annotation.Annotation methodAnnotation,
                               Message value) {
            matchesCount++;
            return false;
        }
    }

    private static class PayloadResolver implements ParameterResolver<SpecificityMessage> {
        @Override
        public Function<SpecificityMessage, Object> resolve(java.lang.reflect.Parameter parameter,
                                                            java.lang.annotation.Annotation methodAnnotation) {
            return SpecificityMessage::getPayload;
        }

        @Override
        public boolean matches(java.lang.reflect.Parameter parameter, java.lang.annotation.Annotation methodAnnotation,
                               SpecificityMessage value) {
            return parameter.getType().isAssignableFrom(value.getPayload().getClass());
        }

        @Override
        public boolean determinesSpecificity() {
            return true;
        }

        @Override
        public int specificityPriority() {
            return -100;
        }
    }

    private static class EntityResolver implements ParameterResolver<SpecificityMessage> {
        @Override
        public Function<SpecificityMessage, Object> resolve(java.lang.reflect.Parameter parameter,
                                                            java.lang.annotation.Annotation methodAnnotation) {
            return SpecificityMessage::getEntity;
        }

        @Override
        public boolean matches(java.lang.reflect.Parameter parameter, java.lang.annotation.Annotation methodAnnotation,
                               SpecificityMessage value) {
            return parameter.getType().isAssignableFrom(value.getEntity().getClass());
        }

        @Override
        public boolean determinesSpecificity() {
            return true;
        }
    }

    private static class Payment {
    }

    private static class CreatePayment extends Payment {
    }

}
