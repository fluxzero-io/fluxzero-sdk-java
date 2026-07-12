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

import java.lang.reflect.Executable;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Defines the logic to determine whether a given target object can handle a message, and how to invoke it.
 * <p>
 * A {@code HandlerMatcher} is a stateless strategy that inspects a target object and a message to:
 * <ul>
 *   <li>Determine whether the target can handle the message ({@link #canHandle(Object)})</li>
 *   <li>Expose the applicable handler methods ({@link #matchingMethods(Object)})</li>
 *   <li>Return a {@link HandlerInvoker} capable of executing the handler method ({@link #getInvoker(Object, Object)})</li>
 * </ul>
 *
 * <p>
 * Unlike a {@link Handler}, a {@code HandlerMatcher} does not resolve or manage instances.
 * It simply inspects a provided target instance and message to resolve possible invocations.
 * </p>
 *
 * @param <T> the type of the handler instance
 * @param <M> the type of the message
 * @see Handler
 * @see HandlerInvoker
 */
public interface HandlerMatcher<T, M> {

    /**
     * Returns whether the given message can be handled by a handler instance of type {@code T}. This is a lightweight
     * check and may be used for fast filtering or diagnostics.
     *
     * @param message the message to check
     * @return {@code true} if the matcher may be able to produce an invoker for the given message
     */
    boolean canHandle(M message);

    /**
     * Returns a stream of methods from the target class that match the given message. Typically used for diagnostics or
     * documentation tools.
     *
     * @param message the message to match against
     * @return a stream of matching {@link Executable} handler methods
     */
    Stream<Executable> matchingMethods(M message);

    /**
     * Attempts to resolve a {@link HandlerInvoker} for the given target instance and message.
     *
     * @param target  the handler object
     * @param message the message to be handled
     * @return an optional invoker if the message is supported by the target; empty otherwise
     */
    Optional<HandlerInvoker> getInvoker(T target, M message);

    /**
     * Attempts to resolve a {@link HandlerInvoker} for the given target instance and message.
     *
     * <p>This is a lower-allocation counterpart to {@link #getInvoker(Object, Object)} for internal hot paths.</p>
     *
     * @param target  the handler object
     * @param message the message to be handled
     * @return an invoker if the message is supported by the target; {@code null} otherwise
     */
    default HandlerInvoker getInvokerOrNull(T target, M message) {
        return getInvoker(target, message).orElse(null);
    }

    /**
     * Binds this matcher to a stable target instance, if it can expose a reusable method plan.
     *
     * <p>Most matcher implementations may return {@code null}. Returning a method is only appropriate when the method
     * metadata and target are stable for all matching messages.</p>
     *
     * @param target the handler object
     * @return a reusable handler method, or {@code null} when the matcher requires per-message invokers
     */
    default HandlerMethod<M> bindHandlerMethod(T target) {
        return null;
    }

    /**
     * Selects and prepares a handler method on the supplied target for the given message.
     *
     * <p>The default uses {@link #bindHandlerMethodPlanner(Object)} when available. Returning {@code null} does not
     * reject the message; it tells the caller to use regular per-message matching instead.</p>
     *
     * @param target the object that contains the handler method
     * @param message the representative message
     * @return the reusable invocation plan, or {@code null} when no method matches or safe preparation is unsupported
     */
    default HandlerMethodPlan<M> prepareHandlerMethod(T target, M message) {
        HandlerMethodPlanner<M> planner = bindHandlerMethodPlanner(target);
        return planner == null ? null : planner.prepare(message).plan();
    }

    /**
     * Creates a planner whose invocation plans call handler methods on the supplied target instance.
     *
     * <p>The planner may be cached and used concurrently. The default returns {@code null}, so matchers are not
     * required to support prepared invocation.</p>
     *
     * @param target the object that contains the handler methods
     * @return a thread-safe planner bound to the target, or {@code null} when preparation is unsupported
     */
    default HandlerMethodPlanner<M> bindHandlerMethodPlanner(T target) {
        return null;
    }

    /**
     * Creates a planner for handler methods declared on the payload itself.
     *
     * <p>The returned planner may be reused for many payload objects. Its plans must therefore obtain the target from
     * {@link HandlerInput#getPayload()} for every invocation and must never retain the representative payload used to
     * select the method.</p>
     *
     * @return a thread-safe payload-target planner, or {@code null} when this matcher cannot safely prepare one
     */
    default HandlerMethodPlanner<M> bindPayloadHandlerMethodPlanner() {
        return null;
    }

    /**
     * Combines this {@code HandlerMatcher} with another {@code HandlerMatcher} to form a composite matcher.
     * The resulting matcher is capable of delegating matching responsibilities to both the current
     * matcher and the provided next matcher.
     *
     * @param next the next {@code HandlerMatcher} to combine with the current matcher
     * @return a new {@code HandlerMatcher} that combines the current matcher and the provided next matcher
     */
    default HandlerMatcher<T, M> or(HandlerMatcher<T, M> next) {
        var first = this;
        return new HandlerMatcher<T, M>() {
            @Override
            public boolean canHandle(M message) {
                return first.canHandle(message) || next.canHandle(message);
            }

            @Override
            public Stream<Executable> matchingMethods(M message) {
                return Stream.concat(first.matchingMethods(message), next.matchingMethods(message));
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(T target, M message) {
                return Optional.ofNullable(getInvokerOrNull(target, message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(T target, M message) {
                HandlerInvoker result = first.getInvokerOrNull(target, message);
                return result == null ? next.getInvokerOrNull(target, message) : result;
            }
        };
    }
}
