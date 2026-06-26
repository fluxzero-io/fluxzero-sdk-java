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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a raw OpenAPI component to generated API documentation.
 * <p>
 * The path is relative to the OpenAPI {@code components} object, for example {@code responses.error} or
 * {@code securitySchemes.basicAuth}. The value must be a JSON object or scalar that can be placed at that path.
 * </p>
 *
 * @see ApiDocInfo
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Documented
public @interface ApiDocComponent {
    /**
     * Component path relative to {@code components}, separated by dots.
     */
    String path();

    /**
     * JSON value for the component.
     */
    String json();
}
