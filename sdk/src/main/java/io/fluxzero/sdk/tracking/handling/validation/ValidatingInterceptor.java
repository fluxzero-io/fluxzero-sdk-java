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

import java.util.Objects;
import java.util.function.Function;

import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.defaultValidator;

/**
 * A {@link HandlerInterceptor} that validates the payload of messages before they are handled.
 */
public class ValidatingInterceptor implements HandlerInterceptor {
    private final Validator validator;

    /**
     * Creates an interceptor backed by the default validator.
     */
    public ValidatingInterceptor() {
        this.validator = defaultValidator;
    }

    /**
     * Creates an interceptor backed by the supplied validator.
     *
     * @param validator the validator to use for payload and return value validation
     */
    public ValidatingInterceptor(Validator validator) {
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * Wraps handler invocation with payload validation before invocation and return value validation after invocation
     * when the handler method declares return constraints.
     *
     * @param function handler invocation function
     * @param invoker  handler metadata and invocation context
     * @return a validating handler invocation function
     */
    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        boolean validateReturnValue = validator.hasReturnValueValidation(invoker.getMethod());
        return m -> {
            ValidationUtils.assertValid(m.getPayload(), validator);
            Object result = function.apply(m);
            if (validateReturnValue) {
                validator.assertValidReturnValue(null, invoker.getMethod(), result);
            }
            return result;
        };
    }
}
