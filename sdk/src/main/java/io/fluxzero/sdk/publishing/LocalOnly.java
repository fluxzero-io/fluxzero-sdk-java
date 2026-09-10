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

package io.fluxzero.sdk.publishing;

import io.fluxzero.sdk.tracking.handling.LocalHandler;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts messages with the annotated payload type to local handlers.
 *
 * <p>The annotation may be placed on a payload type, a meta-annotation, or a package. Package declarations apply to
 * child packages; use {@code @LocalOnly(false)} on a more specific package or payload type to opt out. A marked
 * original remains local-only when a dispatch interceptor replaces its payload, while a marked replacement also
 * enables the restriction.</p>
 *
 * <p>Fluxzero never serializes or publishes a local-only message externally. A request without a matching local
 * handler completes exceptionally with {@link LocalOnlyDispatchException}; a non-request message without a matching
 * handler simply completes. Handler code can still perform its own side effects.</p>
 *
 * @see LocalHandler
 * @see LocalOnlyDispatchException
 */
@Documented
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE, ElementType.PACKAGE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface LocalOnly {
    /**
     * Whether messages in this scope are restricted to local handlers.
     *
     * @return {@code true} to require local-only dispatch
     */
    boolean value() default true;
}
