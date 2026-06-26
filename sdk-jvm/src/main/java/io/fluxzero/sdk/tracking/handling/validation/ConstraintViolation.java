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

package io.fluxzero.sdk.tracking.handling.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured validation failure exposed by the Fluxzero validation API.
 * <p>
 * The SDK keeps this type independent from Jakarta Validation so custom {@link Validator} implementations do not have
 * to expose provider-specific violation objects. The default validator still records the most useful metadata: message,
 * template, property path, invalid value, root bean, constraint type, and constraint attributes.
 *
 * @param message         interpolated violation message
 * @param messageTemplate original message template
 * @param propertyPath    full path to the invalid property or executable value
 * @param formattedPath   compact path used by Fluxzero's formatted {@link ValidationException} messages
 * @param invalidValue    value that failed validation
 * @param rootBean        root object that was validated
 * @param rootBeanClass   root object type
 * @param constraint      fully qualified annotation type name of the violated constraint, when available
 * @param attributes      constraint annotation attributes, excluding provider-specific descriptor objects
 * @param <T>             root bean type
 */
public record ConstraintViolation<T>(String message, String messageTemplate, String propertyPath,
                                     String formattedPath, Object invalidValue, T rootBean, Class<T> rootBeanClass,
                                     String constraint, Map<String, Object> attributes) {
    /**
     * Creates a structured validation failure.
     */
    public ConstraintViolation {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @return interpolated violation message
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return original message template
     */
    public String getMessageTemplate() {
        return messageTemplate;
    }

    /**
     * @return full path to the invalid property or executable value
     */
    public String getPropertyPath() {
        return propertyPath;
    }

    /**
     * @return compact path used by Fluxzero's formatted validation exception messages
     */
    public String getFormattedPath() {
        return formattedPath;
    }

    /**
     * @return value that failed validation
     */
    public Object getInvalidValue() {
        return invalidValue;
    }

    /**
     * @return root object that was validated
     */
    public T getRootBean() {
        return rootBean;
    }

    /**
     * @return root object type
     */
    public Class<T> getRootBeanClass() {
        return rootBeanClass;
    }

    /**
     * @return fully qualified annotation type name of the violated constraint, when available
     */
    public String getConstraint() {
        return constraint;
    }

    /**
     * @return constraint annotation attributes
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
