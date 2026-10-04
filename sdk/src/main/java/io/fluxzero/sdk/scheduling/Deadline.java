/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.scheduling;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Declares one desired, one-shot deadline on a Model. A pure instance method returns {@link
 * Schedule}, a payload when annotation timing is configured, or null to cancel. Parameters may be
 * typed Model ancestors or Graph views. Cron selects the next match without enabling periodic
 * execution. Unchanged declarations never renew consumed or externally canceled deadlines.
 *
 * <p>Comparison includes time, payload, application metadata and explicitly supplied schedule IDs.
 * Generated IDs, message timestamps and reserved metadata (keys starting with {@code $}) are
 * ignored. Reads and replay never schedule work. Commands execute as the configured system user.
 * All writers of the Model or its injected context must run the same declarations. Stateful
 * handlers are not supported.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface Deadline {
    /** A property-resolvable cron value that disables the declaration. */
    String DISABLED = Periodic.DISABLED;

    /**
     * Cron expression selecting the next deadline, with the same syntax and property substitution
     * as {@link Periodic}. For example {@code "${reminder.cron}"}. {@link #DISABLED} disables
     * scheduling. Takes precedence over delay. A returned {@link Schedule} supplies its own time.
     */
    String cron() default "";

    /** Time zone for cron evaluation. */
    String timeZone() default "UTC";

    /**
     * Delay from the change that creates or changes the payload. Required for plain payloads
     * without cron.
     */
    long delay() default -1;

    /** Unit of {@link #delay()}. */
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;

    /**
     * Stable category within the Model. Declare distinct names when a Model has multiple deadlines.
     */
    String value() default "default";

    /**
     * Whether the payload is dispatched as a command. False selects ordinary HandleSchedule
     * handling.
     */
    boolean command() default true;

    /** Cancels the last committed schedule on logical Model deletion, including cascades. */
    boolean cancelOnDeletion() default true;
}
