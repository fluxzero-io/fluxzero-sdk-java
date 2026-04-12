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
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Declares a WebSocket endpoint that represents a single active client session.
 * <p>
 * This annotation is used on handler classes with annotations like {@link HandleSocketMessage} that manage WebSocket
 * communication and lifecycle events (e.g. handshake, open, message, close).
 * </p>
 *
 * <p>
 * Fluxzero creates a new endpoint instance per WebSocket session.
 * Non-socket handlers on the same endpoint, such as {@code @HandleEvent}, may also use
 * {@link io.fluxzero.sdk.tracking.handling.Association} to target only endpoint instances whose state matches the
 * incoming message instead of invoking every active session.
 * In Spring applications, endpoint types become active when they fall within the application's component-scan scope,
 * but they are not exposed as regular injectable Spring beans. Type-level conditional annotations are still
 * respected, including {@link io.fluxzero.sdk.configuration.spring.ConditionalOnProperty} and
 * {@link io.fluxzero.sdk.configuration.spring.ConditionalOnMissingProperty}.
 * </p>
 *
 * @see HandleSocketOpen
 * @see HandleSocketMessage
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface SocketEndpoint {

    /**
     * Configures the WebSocket session keep-alive mechanism (ping/pong).
     */
    AliveCheck aliveCheck() default @AliveCheck;

    /**
     * Controls periodic keep-alive pings to detect inactive sessions.
     */
    @interface AliveCheck {

        /**
         * Whether the keep-alive mechanism is enabled. Defaults to {@code true}.
         */
        boolean value() default true;

        /**
         * Unit for ping intervals and timeouts.
         */
        TimeUnit timeUnit() default SECONDS;

        /**
         * Interval between pings in {@link #timeUnit()}.
         */
        long pingDelay() default 50;

        /**
         * Time allowed to receive a pong after a ping. If exceeded, the session is closed.
         */
        long pingTimeout() default 20;
    }
}
