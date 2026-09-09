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
 * Requires a command or query payload to be handled exclusively inside the publishing application.
 *
 * <p>Fluxzero normally forwards a command or query to the Runtime when no local handler accepts it. A payload marked
 * with {@code @LocalOnly} instead requires exactly one applicable, result-producing local handler. Dispatch fails
 * before monitoring, serialization, or external publication when no such handler exists, when multiple such handlers
 * exist, or when the selected local configuration would also publish the message.</p>
 *
 * <p>The restriction remains active when a dispatch interceptor replaces a marked payload. It also becomes active
 * when an interceptor replaces an unmarked payload with a marked payload. Suppressing the message in the interceptor
 * still suppresses dispatch normally.</p>
 *
 * <p>This annotation applies to command and query payload types. Mark the corresponding handler with
 * {@link LocalHandler}; self-handling command and query payloads are local by default.</p>
 *
 * @see LocalHandler
 * @see LocalOnlyDispatchException
 */
@Documented
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface LocalOnly {
}
