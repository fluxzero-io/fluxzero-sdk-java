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

import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import jakarta.validation.Valid;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.defaultValidator;

/**
 * A {@link HandlerInterceptor} that validates the payload of messages before they are handled. When the supplied
 * validator supports contextual method constraints, the current message context can be used to resolve constrained
 * method parameters.
 */
public class ValidatingInterceptor implements HandlerInterceptor {
    private final Validator validator;
    private final boolean validatePayload;

    /**
     * Creates an interceptor backed by the default validator.
     */
    public ValidatingInterceptor() {
        this(defaultValidator);
    }

    /**
     * Creates an interceptor backed by the supplied validator.
     *
     * @param validator the validator to use for payload and return value validation
     */
    public ValidatingInterceptor(Validator validator) {
        this(validator, true);
    }

    /**
     * Creates an interceptor backed by the supplied validator.
     *
     * @param validator       the validator to use for validation
     * @param validatePayload whether incoming message payloads should be validated before handler invocation
     */
    public ValidatingInterceptor(Validator validator, boolean validatePayload) {
        this.validator = Objects.requireNonNull(validator);
        this.validatePayload = validatePayload;
    }

    /**
     * Wraps handler invocation with payload validation before invocation. Non-passive request handlers with a method
     * annotation also get return value validation when the handler method declares return constraints.
     *
     * @param function handler invocation function
     * @param invoker  handler metadata and invocation context
     * @return a validating handler invocation function
     */
    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        Optional<ReturnValidation> returnValidation = returnValidation(invoker);
        return m -> {
            if (validatePayload) {
                ValidationUtils.assertValid(m.getPayload(), validator);
            }
            Object result = function.apply(m);
            if (returnValidation.isPresent() && m.getMessageType().isRequest()) {
                returnValidation.orElseThrow().assertValid(validator, result);
            }
            return result;
        };
    }

    private Optional<ReturnValidation> returnValidation(HandlerInvoker invoker) {
        Annotation methodAnnotation = invoker.getMethodAnnotation();
        if (methodAnnotation == null || invoker.isPassive() || !invoker.expectResult()) {
            return Optional.empty();
        }
        Optional<Executable> executable = invoker.getExecutableView().executable()
                .filter(validator::hasReturnValueValidation);
        if (executable.isPresent()) {
            return Optional.of(new ReturnValidation(executable, false));
        }
        if (invoker.getExecutableView().annotation(Valid.class).isPresent()) {
            return Optional.of(new ReturnValidation(Optional.empty(), true));
        }
        return Optional.empty();
    }

    private record ReturnValidation(Optional<Executable> executable, boolean cascaded) {
        private void assertValid(Validator validator, Object result) {
            if (executable.isPresent()) {
                validator.assertValidReturnValue(null, executable.orElseThrow(), result);
            } else if (cascaded) {
                ValidationUtils.assertValid(result, validator);
            }
        }
    }
}
