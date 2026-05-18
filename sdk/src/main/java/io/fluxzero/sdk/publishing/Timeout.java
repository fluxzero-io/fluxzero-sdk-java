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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Annotation to be placed on requests (i.e. queries and commands). Configures the time before such request will time
 * out when sent using request/response methods such as {@code send} and {@code sendAndWait}.
 * <p>
 * If no timeout is configured here, blocking {@code sendAndWait} calls use their standard one-minute timeout while
 * non-blocking {@code send} calls use the gateway's default request handling behavior.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Timeout {
    /**
     * Configures the maximum number of time units before a request with this annotation will time out.
     */
    int value();

    /**
     * Returns the time unit for {@link #value()}. Defaults to {@link TimeUnit#MILLISECONDS}.
     */
    TimeUnit timeUnit() default MILLISECONDS;
}
