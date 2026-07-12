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

package io.fluxzero.common.handling;

import lombok.AllArgsConstructor;

import java.util.Optional;

/**
 * Represents a container for a message handler and the mechanism to resolve a {@link HandlerInvoker} for a given
 * message.
 * <p>
 * A {@code Handler} encapsulates a target class and a provider for an instance of that class. It acts as a factory for
 * {@code HandlerInvoker} instances that can be used to invoke the appropriate handler method for a given message.
 * </p>
 *
 * <p>
 * This abstraction allows support for both stateless and stateful handlers:
 * </p>
 * <ul>
 *     <li><strong>Stateless:</strong> A singleton handler instance is reused for every message (e.g., typical application service).</li>
 *     <li><strong>Stateful:</strong> The handler instance is dynamically retrieved, e.g., from a repository, based on message content (e.g., aggregates or projections).</li>
 * </ul>
 *
 * <p>
 * A handler may or may not be able to process a given message. If it can, it returns a non-empty
 * {@link Optional} containing a {@link HandlerInvoker}; otherwise, it returns {@code Optional.empty()}.
 * </p>
 *
 * <h2>Handler Architecture</h2>
 * <pre>
 * ┌────────────────────┐
 * │  HandlerInspector  │
 * └────────┬───────────┘
 *          │ inspects target class
 *          ▼
 * ┌────────────────────┐        creates        ┌──────────────────────┐
 * │  HandlerMatcher    │──────────────────────▶│     HandlerInvoker   │
 * └────────┬───────────┘                       └──────────────────────┘
 *          │ produces invoker if message
 *          │ matches a method
 *          ▼
 * ┌────────────────────┐
 * │      Handler       │◀───────────── target instance
 * └────────────────────┘
 * </pre>
 *
 * @param <M> the type of messages this handler supports (usually {@code DeserializingMessage})
 * @see HandlerInvoker
 * @see HandlerMatcher
 * @see HandlerInspector
 */
public interface Handler<M> {

    /**
     * Returns the class of the handler's target object. This may be used for reflective operations, logging, or
     * framework-level behavior.
     *
     * @return the class of the handler's target
     */
    Class<?> getTargetClass();

    /**
     * Returns a {@link HandlerInvoker} capable of processing the given message, if available.
     *
     * @param message the message to be handled
     * @return an optional {@code HandlerInvoker} if this handler can handle the message; otherwise
     * {@code Optional.empty()}
     */
    Optional<HandlerInvoker> getInvoker(M message);

    /**
     * Returns a {@link HandlerInvoker} capable of processing the given message, or {@code null} when unavailable.
     *
     * <p>This is a lower-allocation counterpart to {@link #getInvoker(Object)} for internal hot paths. Implementations
     * that can resolve an invoker without creating an {@link Optional} should override this method.</p>
     *
     * @param message the message to be handled
     * @return an invoker if this handler can handle the message; {@code null} otherwise
     */
    default HandlerInvoker getInvokerOrNull(M message) {
        return getInvoker(message).orElse(null);
    }

    /**
     * Returns a reusable {@link HandlerMethod} capable of processing the given message, or {@code null} when
     * unavailable.
     *
     * <p>This is an optional lower-allocation path for handlers whose target and method plan can be reused across
     * messages. Implementations that cannot expose a stable method should return {@code null} and rely on
     * {@link #getInvokerOrNull(Object)}.</p>
     *
     * @param message the message to be handled
     * @return a handler method if this handler can handle the message through a reusable method; {@code null} otherwise
     */
    default HandlerMethod<M> getHandlerMethodOrNull(M message) {
        return null;
    }

    /**
     * Returns a reusable plan for the handler method selected for this message.
     *
     * <p>The default asks {@link #getHandlerMethodPlanner()} to prepare the plan. A {@code null} result means that this
     * handler cannot safely prepare the invocation, so the caller should use {@link #getInvokerOrNull(Object)} or
     * {@link #getHandlerMethodOrNull(Object)} instead.</p>
     *
     * @param message the message to match
     * @return the prepared invocation plan, or {@code null} when preparation is unsupported or no method matches
     */
    default HandlerMethodPlan<M> getHandlerMethodPlanOrNull(M message) {
        HandlerMethodPlanner<M> planner = getHandlerMethodPlanner();
        return planner == null ? null : planner.prepare(message).plan();
    }

    /**
     * Returns the planner used to select and prepare this handler's methods.
     *
     * <p>The returned planner may be cached and invoked concurrently. The default returns {@code null}, which keeps
     * implementations that only support regular per-message matching fully compatible.</p>
     *
     * @return a thread-safe handler method planner, or {@code null} when this handler does not support preparation
     */
    default HandlerMethodPlanner<M> getHandlerMethodPlanner() {
        return null;
    }

    /**
     * Creates a composite handler that executes the current handler and then delegates to the specified next handler if
     * the current handler cannot handle the message or does not provide an invoker.
     *
     * @param next the next handler to be invoked if this handler does not handle the message
     * @return a new handler combining the current handler and the specified next handler
     */
    default Handler<M> or(Handler<M> next) {
        var first = this;
        return new Handler<>() {
            @Override
            public Class<?> getTargetClass() {
                return first.getTargetClass();
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(M message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(M message) {
                HandlerInvoker result = first.getInvokerOrNull(message);
                return result == null ? next.getInvokerOrNull(message) : result;
            }

            @Override
            public HandlerMethod<M> getHandlerMethodOrNull(M message) {
                HandlerMethod<M> result = first.getHandlerMethodOrNull(message);
                return result == null ? next.getHandlerMethodOrNull(message) : result;
            }

            @Override
            public HandlerMethodPlan<M> getHandlerMethodPlanOrNull(M message) {
                HandlerMethodPlan<M> result = first.getHandlerMethodPlanOrNull(message);
                return result == null ? next.getHandlerMethodPlanOrNull(message) : result;
            }

            @Override
            public HandlerMethodPlanner<M> getHandlerMethodPlanner() {
                return null;
            }
        };
    }

    /**
     * Abstract base class for {@link Handler} implementations that delegate to another handler.
     * <p>
     * This is useful for decorating or extending handler behavior while preserving its target class and delegation
     * logic.
     * </p>
     *
     * @param <M> the message type
     */
    @AllArgsConstructor
    abstract class DelegatingHandler<M> implements Handler<M> {
        protected final Handler<M> delegate;

        @Override
        public Class<?> getTargetClass() {
            return delegate.getTargetClass();
        }

        @Override
        public HandlerMethod<M> getHandlerMethodOrNull(M message) {
            return null;
        }

        @Override
        public HandlerMethodPlan<M> getHandlerMethodPlanOrNull(M message) {
            return null;
        }

        @Override
        public HandlerMethodPlanner<M> getHandlerMethodPlanner() {
            return null;
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
