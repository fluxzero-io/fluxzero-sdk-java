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


import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerInspectorSpecificityTest {

    private final Foo foo = new Foo();
    private final Handler<Object> subject =
            HandlerInspector.createHandler(foo, Handle.class);

    @Test
    void testFindInvoker() {
        assertTrue(subject.getInvoker(new Message()).isPresent());
        assertTrue(subject.getInvoker(new MessageSuper()).isPresent());
        assertFalse(subject.getInvoker(foo).isPresent());
    }

    @Test
    void testInvoke() {
        Message message = new Message();
        assertEquals(message, subject.getInvoker(message).orElseThrow().invoke());
    }

    @Test
    void preparedApplicabilityPreservesSpecificityWithoutMaterializingMessage() {
        Handler<InputMessage> handler = HandlerInspector.createHandler(
                new PreparedFoo(), Handle.class, List.of(new InputPayloadResolver()));
        HandlerInput<InputMessage> specificInput = new TestInput(new Message());
        HandlerInput<InputMessage> broadInput = new TestInput(new MessageSuper());

        HandlerMethodApplicability<InputMessage> specific = handler.getHandlerMethodPlanner()
                .prepareApplicability(specificInput);
        HandlerMethodApplicability<InputMessage> broad = handler.getHandlerMethodPlanner()
                .prepareApplicability(broadInput);

        assertTrue(specific.payloadClassKey());
        assertEquals("specific", specific.preparation().plan().invoke(specificInput));
        assertEquals("broad", broad.preparation().plan().invoke(broadInput));
    }

    @Test
    void unsupportedContextOnUnrelatedMethodDoesNotDisablePreparedSelection() {
        Handler<InputMessage> handler = HandlerInspector.createHandler(
                new MixedPreparedFoo(), Handle.class,
                List.of(new InputPayloadResolver(), new UnsupportedContextResolver()));
        HandlerInput<InputMessage> input = new TestInput(new Message());

        HandlerMethodApplicability<InputMessage> applicability = handler.getHandlerMethodPlanner()
                .prepareApplicability(input);

        assertTrue(applicability.isCacheable());
        assertTrue(applicability.payloadClassKey());
        assertTrue(applicability.preparation().isPrepared());
        assertEquals("prepared", applicability.preparation().plan().invoke(input));
    }

    private static class Foo {
        @Handle
        public void handle(MessageSuper o) {
            throw new AssertionError("Undesired invocation");
        }

        @Handle
        public Object handle(Message o) {
            return o;
        }

        @Handle
        public void h0(MessageSuper o) {
            throw new AssertionError("Undesired invocation");
        }

    }

    private static class Message extends MessageSuper {

    }

    private static class MessageSuper {

    }

    private static class PreparedFoo {
        @Handle
        String handle(MessageSuper ignored) {
            return "broad";
        }

        @Handle
        String handle(Message ignored) {
            return "specific";
        }
    }

    private static class MixedPreparedFoo {
        @Handle
        String handle(Message ignored) {
            return "prepared";
        }

        @Handle
        String handle(UnsupportedContext ignoredContext, UnrelatedMessage ignoredPayload) {
            return "unsupported";
        }
    }

    private static class UnrelatedMessage {
    }

    private static class UnsupportedContext {
    }

    private record InputMessage(Object payload) {
    }

    private record TestInput(Object payload) implements HandlerInput<InputMessage> {
        @Override
        public Object getPayload() {
            return payload;
        }

        @Override
        public InputMessage getMessage() {
            throw new AssertionError("Prepared selection must not materialize the message");
        }
    }

    private static class InputPayloadResolver implements HandlerInputResolver<InputMessage> {
        @Override
        public Function<InputMessage, Object> resolve(Parameter parameter, Annotation methodAnnotation) {
            return InputMessage::payload;
        }

        @Override
        public boolean determinesSpecificity() {
            return true;
        }

        @Override
        public Object getInputCacheKey(
                Parameter parameter, Annotation methodAnnotation, HandlerInput<InputMessage> representative) {
            return representative.getPayload().getClass();
        }

        @Override
        public boolean isPayloadClassKey(
                Parameter parameter, Annotation methodAnnotation, HandlerInput<InputMessage> representative) {
            return true;
        }

        @Override
        public boolean isNoMatchPayloadClassKey(
                Parameter parameter, Annotation methodAnnotation, HandlerInput<InputMessage> representative) {
            return true;
        }

        @Override
        public Resolution<InputMessage> prepareInput(
                Parameter parameter, Annotation methodAnnotation, HandlerInput<InputMessage> representative) {
            return parameter.getType().isInstance(representative.getPayload())
                    ? Resolution.resolved(HandlerInput::getPayload) : Resolution.unmatched();
        }
    }

    private static class UnsupportedContextResolver implements KeyedParameterResolver<InputMessage> {
        @Override
        public Function<InputMessage, Object> resolve(Parameter parameter, Annotation methodAnnotation) {
            return ignored -> new UnsupportedContext();
        }

        @Override
        public boolean matches(Parameter parameter, Annotation methodAnnotation, InputMessage value) {
            return parameter.getType() == UnsupportedContext.class;
        }

        @Override
        public Object getCacheKey(InputMessage value) {
            return null;
        }

        @Override
        public boolean isCacheKeyRelevant(Parameter parameter, Annotation methodAnnotation) {
            return parameter.getType() == UnsupportedContext.class;
        }

        @Override
        public Resolution<InputMessage> resolveForKey(
                Parameter parameter, Annotation methodAnnotation, InputMessage value, Object cacheKey) {
            throw new AssertionError("Unsupported resolver should not be evaluated for an unrelated payload");
        }

        @Override
        public boolean mayApply(Executable method, Class<?> targetClass) {
            return List.of(method.getParameterTypes()).contains(UnsupportedContext.class);
        }
    }


}
