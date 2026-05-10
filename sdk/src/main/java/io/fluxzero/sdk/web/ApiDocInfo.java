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
 * Adds top-level metadata to generated API documentation.
 * <p>
 * Place this annotation on a package or handler type to describe the generated API document itself. Compiler options
 * such as {@code -Afluxzero.openapi.title=...} can still override the same values for annotation-processor output.
 * </p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE})
@Documented
public @interface ApiDocInfo {
    /**
     * OpenAPI document version. Defaults to {@code 3.0.1}; set {@code 3.1.0} when a consumer requires OpenAPI 3.1.
     */
    String openApiVersion() default "";

    /**
     * API title.
     */
    String title() default "";

    /**
     * API version.
     */
    String version() default "";

    /**
     * API description. Markdown is allowed by OpenAPI tooling.
     */
    String description() default "";

    /**
     * Terms of service URL.
     */
    String termsOfService() default "";

    /**
     * Contact name.
     */
    String contactName() default "";

    /**
     * Contact URL.
     */
    String contactUrl() default "";

    /**
     * Contact email address.
     */
    String contactEmail() default "";

    /**
     * License name.
     */
    String licenseName() default "";

    /**
     * License URL.
     */
    String licenseUrl() default "";

    /**
     * Optional logo URL, rendered as the common {@code info.x-logo.url} vendor extension.
     */
    String logoUrl() default "";

    /**
     * Optional logo alt text, rendered as {@code info.x-logo.altText}.
     */
    String logoAltText() default "";

    /**
     * API servers.
     */
    ApiDocServer[] servers() default {};

    /**
     * Top-level OpenAPI security requirements.
     * <p>
     * Values are rendered as security requirement objects. Use {@code bearerAuth} for a scheme without scopes, or
     * {@code oauth2=read,write} for scoped schemes. Define the actual schemes through {@link #components()} with paths
     * such as {@code securitySchemes.bearerAuth}.
     */
    String[] security() default {};

    /**
     * If {@code true}, the generated OpenAPI document is served as an automatic web endpoint.
     * <p>
     * The endpoint is only registered for handlers in the annotated package or type. Use {@link #openApiPath()} to
     * avoid route conflicts with application endpoints.
     */
    boolean serveOpenApi() default false;

    /**
     * Path where the generated OpenAPI document is served when {@link #serveOpenApi()} is enabled.
     * <p>
     * Relative paths are resolved against the {@link Path} value at the same package or handler type where this
     * annotation is placed. Absolute paths start at the application root.
     */
    String openApiPath() default "openapi.json";

    /**
     * Extra OpenAPI components such as shared responses or security schemes. Component paths are relative to the
     * OpenAPI {@code components} object, for example {@code responses.error}.
     */
    ApiDocComponent[] components() default {};

    /**
     * Top-level OpenAPI vendor extensions as {@code name=json} entries, for example
     * {@code x-code-samples-enabled=true}. Values are parsed as JSON when possible and otherwise rendered as strings.
     */
    String[] extensions() default {};
}
