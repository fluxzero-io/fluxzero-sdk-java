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

package io.fluxzero.sdk.tracking.handling.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URI;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates that a character sequence can be parsed as a URL and optionally matches URL components.
 */
@Documented
@Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Repeatable(URL.List.class)
@Constraint(validatedBy = URLValidator.class)
public @interface URL {
    /**
     * @return violation message template
     */
    String message() default "must be a valid URL";

    /**
     * @return validation groups for which this constraint applies
     */
    Class<?>[] groups() default {};

    /**
     * @return payload metadata associated with this constraint
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * @return required URL protocol, or an empty string to allow any protocol
     */
    String protocol() default "";

    /**
     * @return required URL host, or an empty string to allow any host
     */
    String host() default "";

    /**
     * @return required URL port, or {@code -1} to allow any port
     */
    int port() default -1;

    /**
     * @return regular expression that the original URL text must match
     */
    String regexp() default ".*";

    /**
     * @return pattern flags used for {@link #regexp()}
     */
    Pattern.Flag[] flags() default {};

    /**
     * Container annotation for repeatable {@link URL} constraints.
     */
    @Documented
    @Target({FIELD, METHOD, PARAMETER, CONSTRUCTOR, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface List {
        /**
         * @return repeated URL constraints
         */
        URL[] value();
    }
}

final class URLValidator implements ConstraintValidator<URL, Object> {
    private String protocol;
    private String host;
    private int port;
    private String regexp;
    private Pattern.Flag[] flags;

    /** {@inheritDoc} */
    @Override
    public void initialize(URL annotation) {
        protocol = annotation.protocol();
        host = annotation.host();
        port = annotation.port();
        regexp = annotation.regexp();
        flags = annotation.flags();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof CharSequence sequence)) {
            return false;
        }
        String text = sequence.toString();
        if (!ConstraintSupport.matches(text, regexp, flags)) {
            return false;
        }
        try {
            java.net.URL url = new URI(text).toURL();
            return (protocol.isBlank() || protocol.equalsIgnoreCase(url.getProtocol()))
                   && (host.isBlank() || host.equalsIgnoreCase(url.getHost()))
                   && (port < 0 || url.getPort() == port || url.getPort() < 0 && url.getDefaultPort() == port);
        } catch (Exception e) {
            return false;
        }
    }
}
