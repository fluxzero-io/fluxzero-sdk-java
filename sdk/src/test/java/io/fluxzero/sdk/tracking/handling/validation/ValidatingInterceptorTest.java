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

package io.fluxzero.sdk.tracking.handling.validation;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ValidatingInterceptorTest {
    private final TestFixture testFixture = TestFixture.create(new MockHandler());

    @Test
    void testWithConstraintViolations() {
        testFixture.whenCommand(new BasicCommand(null)).expectExceptionalResult(ValidationException.class);
    }

    @Test
    void testWithoutConstraintViolations() {
        testFixture.whenCommand(new BasicCommand("foo")).expectSuccessfulResult();
    }

    @Test
    void testValidateWith_invalid() {
        testFixture.whenCommand(new CommandWithGroupValidation("foo", null))
                .expectExceptionalResult(ValidationException.class);
    }

    @Test
    void testValidateWith_valid() {
        testFixture.whenCommand(new CommandWithGroupValidation("foo", "bar"))
                .expectSuccessfulResult();
    }

    @Test
    void testValidateWith_valid2() {
        testFixture.whenCommand(new CommandWithGroupValidation(null, "bar"))
                .expectSuccessfulResult();
    }

    @Test
    void validatesReturnValue() {
        TestFixture.create(new ReturnValueHandler())
                .whenCommand(new ReturnValueCommand())
                .expectExceptionalResult(ValidationException.class);
    }

    @Test
    void validatesQueryReturnValue() {
        TestFixture.create(new ReturnValueHandler())
                .whenQuery(new ReturnValueQuery())
                .expectExceptionalResult(ValidationException.class);
    }

    @Test
    void validatesCustomReturnValue() {
        TestFixture.create(new ReturnValueHandler())
                .whenCustom("return-validation", new ReturnValueCustom())
                .expectExceptionalResult(ValidationException.class);
    }

    @Test
    void validatesWebRequestReturnValue() {
        TestFixture.create(new ReturnValueHandler())
                .whenWebRequest(WebRequest.get("/return-validation").build())
                .expectExceptionalResult(ValidationException.class);
    }

    @Test
    void ignoresEventReturnValue() {
        TestFixture.create(new ReturnValueHandler())
                .whenEvent(new ReturnValueEvent())
                .expectSuccessfulResult();
    }

    @Test
    void ignoresPassiveRequestReturnValue() throws Exception {
        Method method = ReturnValueHandler.class.getDeclaredMethod("handle", PassiveReturnValueCommand.class);
        HandlerInvoker invoker = new TestHandlerInvoker(method, true);

        assertDoesNotThrow(() -> new ValidatingInterceptor().interceptHandling(
                message -> new ReturnValue(null), invoker).apply(
                new DeserializingMessage(new Message(new PassiveReturnValueCommand(), Metadata.empty()),
                                         MessageType.COMMAND, null)));
    }

    @Value
    private static class BasicCommand {
        @NotNull String aString;
        @NotNull(groups = GroupA.class) String groupAString = null;
    }

    @Value
    @ValidateWith(GroupA.class)
    private static class CommandWithGroupValidation {
        @NotNull String aString;
        @NotNull(groups = GroupA.class) String groupAString;
    }

    private interface GroupA {
    }

    private record ReturnValueCommand() {
    }

    private record PassiveReturnValueCommand() {
    }

    private record ReturnValueQuery() {
    }

    private record ReturnValueCustom() {
    }

    private record ReturnValueEvent() {
    }

    private record ReturnValue(@NotNull String value) {
    }

    private static class MockHandler {
        @HandleCommand
        void handle(Object command) {
        }
    }

    private static class ReturnValueHandler {
        @HandleCommand
        @jakarta.validation.Valid
        ReturnValue handle(ReturnValueCommand command) {
            return new ReturnValue(null);
        }

        @HandleCommand(passive = true)
        @jakarta.validation.Valid
        ReturnValue handle(PassiveReturnValueCommand command) {
            return new ReturnValue(null);
        }

        @HandleQuery
        @jakarta.validation.Valid
        ReturnValue handle(ReturnValueQuery query) {
            return new ReturnValue(null);
        }

        @HandleCustom("return-validation")
        @jakarta.validation.Valid
        ReturnValue handle(ReturnValueCustom message) {
            return new ReturnValue(null);
        }

        @HandleGet("/return-validation")
        @jakarta.validation.Valid
        ReturnValue handleWeb() {
            return new ReturnValue(null);
        }

        @HandleEvent
        @jakarta.validation.Valid
        ReturnValue handle(ReturnValueEvent event) {
            return new ReturnValue(null);
        }
    }

    private record TestHandlerInvoker(Method method, boolean passive) implements HandlerInvoker {
        @Override
        public Class<?> getTargetClass() {
            return ReturnValueHandler.class;
        }

        @Override
        public Executable getMethod() {
            return method;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A extends Annotation> A getMethodAnnotation() {
            return (A) method.getDeclaredAnnotation(HandleCommand.class);
        }

        @Override
        public boolean expectResult() {
            return true;
        }

        @Override
        public boolean isPassive() {
            return passive;
        }

        @Override
        public Object invoke(BiFunction<Object, Object, Object> resultCombiner) {
            return null;
        }
    }
}
