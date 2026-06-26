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

package io.fluxzero.sdk.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds optional human-readable API documentation metadata to generated API docs.
 * <p>
 * Generated endpoint documentation is opt-in. Place {@code @ApiDoc} on a package, handler type, or handler method to
 * include the matching web endpoints; an empty annotation is enough when route, parameter, request, and response
 * information can be inferred from the existing web handler annotations. Use populated {@code @ApiDoc} values only for
 * metadata that cannot be inferred reliably, such as descriptions, tags, or a stable operation id. The annotation can
 * also be used on fields, parameters, record components, and type uses; for example
 * {@code List<@ApiDoc(description = "Connection item") Connection>} documents array items without requiring
 * OpenAPI-specific array annotations. Schema hints such as {@link #type()}, {@link #format()}, {@link #example()},
 * {@link #defaultValue()}, and {@link #allowableValues()} provide an SDK-native alternative for the common
 * documentation metadata that projects often express with OpenAPI-specific annotations.
 * <p>
 * When using the annotation processor, source Javadoc on schema types and properties in the same javac compilation is
 * used as a final description fallback after explicit {@code @ApiDoc} metadata and OpenAPI {@code @Schema} metadata.
 * Javadoc from already compiled dependency classes is not available to javac processors; use {@code @ApiDoc} when schema
 * documentation must cross module boundaries.
 * </p>
 *
 * @see ApiDocExtractor
 * @see OpenApiProcessor
 * @see ApiDocInfo
 * @see ApiDocResponse
 * @see ApiDocExclude
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.METHOD,
        ElementType.CONSTRUCTOR,
        ElementType.TYPE,
        ElementType.PACKAGE,
        ElementType.FIELD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.TYPE_USE
})
@Documented
public @interface ApiDoc {
    /**
     * Short endpoint summary.
     */
    String summary() default "";

    /**
     * Longer description for the domain or endpoint.
     */
    String description() default "";

    /**
     * Stable operation identifier for generated specs.
     */
    String operationId() default "";

    /**
     * Tags to associate with generated operations.
     */
    String[] tags() default {};

    /**
     * Optional schema type override, such as {@code string}, {@code integer}, {@code number}, {@code boolean},
     * {@code object}, or {@code array}. Values {@code date}, {@code date-time}, {@code uuid}, {@code uri},
     * {@code email}, and {@code binary} are treated as string formats.
     */
    String type() default "";

    /**
     * Optional schema format override.
     */
    String format() default "";

    /**
     * Optional schema example. JSON object/array examples are parsed when possible; scalar examples are coerced to the
     * inferred schema type when possible.
     */
    String example() default "";

    /**
     * Optional schema default value. Values are coerced to the inferred schema type when possible.
     */
    String defaultValue() default "";

    /**
     * Optional inclusive numeric minimum.
     */
    String minimum() default "";

    /**
     * Optional inclusive numeric maximum.
     */
    String maximum() default "";

    /**
     * Optional allowed string values for enum-like schemas.
     */
    String[] allowableValues() default {};

    /**
     * Marks a schema property or parameter as required in generated docs.
     */
    boolean required() default false;

    /**
     * Optional schema implementation override for abstract, interface, or otherwise ambiguous fields.
     */
    Class<?> implementation() default Void.class;

    /**
     * Marks generated operations as deprecated.
     */
    boolean deprecated() default false;

    /**
     * OpenAPI security requirements for generated operations.
     * <p>
     * Values are rendered as security requirement objects. Use {@code bearerAuth} for a scheme without scopes, or
     * {@code oauth2=read,write} for scoped schemes.
     */
    String[] security() default {};
}
