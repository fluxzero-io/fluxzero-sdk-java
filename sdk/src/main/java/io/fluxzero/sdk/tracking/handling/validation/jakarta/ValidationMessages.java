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

package io.fluxzero.sdk.tracking.handling.validation.jakarta;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

final class ValidationMessages {
    private static final Map<String, String> defaultMessages = defaultMessages();
    private static final ResourceBundle validationMessages = validationMessages();

    private ValidationMessages() {
    }

    static String interpolate(ConstraintMeta meta, String template, Object validatedValue) {
        return interpolate(meta, template, validatedValue, new DefaultValidationMetadata.DefaultConstraintDescriptor<>(meta),
                           defaultMessageInterpolator());
    }

    static String interpolate(ConstraintMeta meta, String template, Object validatedValue,
                              ConstraintDescriptor<?> descriptor, MessageInterpolator interpolator) {
        if (interpolator != null && !(interpolator instanceof DefaultMessageInterpolator)) {
            return interpolator.interpolate(template, new Context(descriptor, validatedValue));
        }
        return defaultInterpolate(meta, template, validatedValue);
    }

    static MessageInterpolator defaultMessageInterpolator() {
        return new DefaultMessageInterpolator();
    }

    private static String defaultInterpolate(ConstraintMeta meta, String template, Object validatedValue) {
        String result = resolveMessageTemplate(template);
        Map<String, Object> attributes = meta.attributes();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            result = interpolateToken(result, entry.getKey(), entry.getValue());
        }
        result = interpolateToken(result, "validatedValue", validatedValue);
        if (meta.annotation() instanceof DecimalMin min && !min.inclusive()) {
            result = result.replace("or equal to ", "");
        }
        if (meta.annotation() instanceof DecimalMax max && !max.inclusive()) {
            result = result.replace("or equal to ", "");
        }
        return result;
    }

    private static String interpolateToken(String message, String name, Object value) {
        String replacement = String.valueOf(value);
        return message.replace("${" + name + "}", replacement).replace("{" + name + "}", replacement);
    }

    private static String resolveMessageTemplate(String template) {
        if (!template.startsWith("{") || !template.endsWith("}")) {
            return template;
        }
        String key = template.substring(1, template.length() - 1);
        if (validationMessages != null && validationMessages.containsKey(key)) {
            return validationMessages.getString(key);
        }
        return defaultMessages.getOrDefault(key, template);
    }

    private static ResourceBundle validationMessages() {
        try {
            return ResourceBundle.getBundle("ValidationMessages", Locale.getDefault());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> defaultMessages() {
        Map<String, String> result = new HashMap<>();
        result.put("jakarta.validation.constraints.AssertFalse.message", "must be false");
        result.put("jakarta.validation.constraints.AssertTrue.message", "must be true");
        result.put("jakarta.validation.constraints.DecimalMax.message", "must be less than or equal to {value}");
        result.put("jakarta.validation.constraints.DecimalMin.message", "must be greater than or equal to {value}");
        result.put("jakarta.validation.constraints.Digits.message",
                   "numeric value out of bounds (<{integer} digits>.<{fraction} digits> expected)");
        result.put("jakarta.validation.constraints.Email.message", "must be a well-formed email address");
        result.put("jakarta.validation.constraints.Future.message", "must be a future date");
        result.put("jakarta.validation.constraints.FutureOrPresent.message",
                   "must be a date in the present or in the future");
        result.put("jakarta.validation.constraints.Max.message", "must be less than or equal to {value}");
        result.put("jakarta.validation.constraints.Min.message", "must be greater than or equal to {value}");
        result.put("jakarta.validation.constraints.Negative.message", "must be less than 0");
        result.put("jakarta.validation.constraints.NegativeOrZero.message", "must be less than or equal to 0");
        result.put("jakarta.validation.constraints.NotBlank.message", "must not be blank");
        result.put("jakarta.validation.constraints.NotEmpty.message", "must not be empty");
        result.put("jakarta.validation.constraints.NotNull.message", "must not be null");
        result.put("jakarta.validation.constraints.Null.message", "must be null");
        result.put("jakarta.validation.constraints.Past.message", "must be a past date");
        result.put("jakarta.validation.constraints.PastOrPresent.message",
                   "must be a date in the past or in the present");
        result.put("jakarta.validation.constraints.Pattern.message", "must match \"{regexp}\"");
        result.put("jakarta.validation.constraints.Positive.message", "must be greater than 0");
        result.put("jakarta.validation.constraints.PositiveOrZero.message", "must be greater than or equal to 0");
        result.put("jakarta.validation.constraints.Size.message", "size must be between {min} and {max}");
        return Map.copyOf(result);
    }

    private record Context(ConstraintDescriptor<?> descriptor, Object validatedValue)
            implements MessageInterpolator.Context {
        /** {@inheritDoc} */
        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return descriptor;
        }

        /** {@inheritDoc} */
        @Override
        public Object getValidatedValue() {
            return validatedValue;
        }

        /** {@inheritDoc} */
        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new jakarta.validation.ValidationException("Cannot unwrap to " + type.getName());
        }
    }

    private static final class DefaultMessageInterpolator implements MessageInterpolator {
        /** {@inheritDoc} */
        @Override
        public String interpolate(String messageTemplate, MessageInterpolator.Context context) {
            ConstraintDescriptor<?> descriptor = context.getConstraintDescriptor();
            if (descriptor instanceof DefaultValidationMetadata.DefaultConstraintDescriptor<?> simple) {
                return defaultInterpolate(simple.meta(), messageTemplate, context.getValidatedValue());
            }
            String result = resolveMessageTemplate(messageTemplate);
            for (Map.Entry<String, Object> entry : descriptor.getAttributes().entrySet()) {
                result = interpolateToken(result, entry.getKey(), entry.getValue());
            }
            return interpolateToken(result, "validatedValue", context.getValidatedValue());
        }

        /** {@inheritDoc} */
        @Override
        public String interpolate(String messageTemplate, MessageInterpolator.Context context, Locale locale) {
            return interpolate(messageTemplate, context);
        }
    }
}
