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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.Registration;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import lombok.AllArgsConstructor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for registering and invoking local message handlers.
 * <p>
 * A {@code HandlerRegistry} is responsible for managing one or more message handlers — including discovery,
 * invocation, and filtering logic. It is a central abstraction in scenarios where handlers are registered
 * programmatically (e.g. embedded services, tests, functional configurations).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *     <li>Registering local handler instances (e.g. beans, stateful components)</li>
 *     <li>Dispatching messages to matching handlers</li>
 *     <li>Composing multiple registries to form a combined resolution chain</li>
 *     <li>Delegating filtering behavior via {@link HandlerFilter}</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * Handlers can be registered using {@link #registerHandler(Object)} or {@link #registerHandler(Object, HandlerFilter)}.
 * Message handling can be triggered manually via {@link #handle(DeserializingMessage)}.
 *
 * <pre>{@code
 * HandlerRegistry registry = ...;
 * registry.registerHandler(new MyCommandHandler());
 *
 * registry.handle(myMessage).ifPresent(resultFuture -> {
 *     Object result = resultFuture.join();
 *     ...
 * });
 * }</pre>
 *
 * <h2>Composing Registries</h2>
 * Use {@link #andThen(HandlerRegistry)} or {@link #orThen(HandlerRegistry)} to chain multiple registries:
 * <ul>
 *     <li>{@code andThen}: invokes both registries and merges results (e.g. for broadcasting)</li>
 *     <li>{@code orThen}: invokes the second only if the first produces no result</li>
 * </ul>
 *
 * <pre>{@code
 * HandlerRegistry composite = registry1.orThen(registry2);
 * }</pre>
 *
 * <h2>Built-in Implementations</h2>
 * <ul>
 *     <li>{@link NoOpHandlerRegistry} — a stub that does nothing, always returns empty</li>
 *     <li>{@link MergedHandlerRegistry} — combines two registries into one</li>
 * </ul>
 *
 * @see HasLocalHandlers
 * @see HandlerFilter
 * @see DeserializingMessage
 */
public interface HandlerRegistry extends HasLocalHandlers {

    /**
     * A no-op registry that does not register or invoke any handlers.
     */
    static HandlerRegistry noOp() {
        return NoOpHandlerRegistry.INSTANCE;
    }

    /**
     * Attempts to handle the given message using local handlers.
     *
     * @param message the deserialized message to dispatch
     * @return an optional future containing the result, or empty if no handler was found
     */
    Optional<CompletableFuture<Object>> handle(DeserializingMessage message);

    /**
     * Attempts to handle the message locally while preserving a synchronously returned value as a direct value.
     *
     * <p>The default adapts {@link #handle(DeserializingMessage)} and therefore represents a handled result as a
     * future. Implementations may override this method when they can retain synchronous completion.</p>
     *
     * @param message the message to dispatch
     * @return the local handling result, including an explicit not-handled result when no handler matches
     */
    default LocalHandlerResult handleResult(DeserializingMessage message) {
        return handle(message).map(LocalHandlerResult::asynchronous).orElseGet(LocalHandlerResult::notHandled);
    }

    /**
     * Attempts to handle a lazy local input.
     *
     * <p>The default materializes the message and delegates to {@link #handleResult(DeserializingMessage)}.
     * Implementations that support payload-first handling may override this method.</p>
     *
     * @param input the local handler input
     * @return the local handling result
     */
    default LocalHandlerResult handleResult(LocalHandlerInput input) {
        return handleResult(input.getMessage());
    }

    /**
     * Attempts payload-first local handling and writes the outcome into the reusable execution frame.
     * Implementations that cannot preserve the lazy input return {@code false}, causing the caller to use the
     * canonical message-based path.
     *
     * <p>This method is intended for Fluxzero registry implementations. A {@code false} result means “use the regular
     * path”, not “no handler exists”.</p>
     *
     * @param execution the current local dispatch and destination for its result
     * @return {@code true} if this registry completed handler selection on the payload-first path; {@code false} to
     * use regular message-based handling
     */
    default boolean handleLocal(LocalExecution execution) {
        return false;
    }

    /**
     * Returns whether this registry has a local handler that can process the given message.
     *
     * @param message the message to inspect
     * @return {@code true} if a local handler can handle the message, {@code false} otherwise
     */
    default boolean canHandle(DeserializingMessage message) {
        return false;
    }

    /**
     * Creates a composite registry that invokes both this and the given registry.
     * <p>
     * Results are merged via {@code thenCombine()} if both registries handle the message.
     *
     * @param next the registry to invoke second
     * @return a combined registry
     */
    default HandlerRegistry andThen(HandlerRegistry next) {
        return new MergedHandlerRegistry(this, next);
    }

    /**
     * Creates a fallback registry that only invokes the given registry if this one yields no result.
     *
     * @param next the fallback registry
     * @return a combined registry with short-circuiting behavior
     */
    default HandlerRegistry orThen(HandlerRegistry next) {
        return new MergedHandlerRegistry(this, next) {
            @Override
            public Optional<CompletableFuture<Object>> handle(DeserializingMessage message) {
                return first.handle(message).or(() -> second.handle(message));
            }
        };
    }

    /**
     * Combines two {@link HandlerRegistry} instances into one.
     * <p>
     * Useful for layering or composing registries programmatically.
     */
    @AllArgsConstructor
    class MergedHandlerRegistry implements HandlerRegistry {
        protected final HandlerRegistry first, second;

        @Override
        public Optional<CompletableFuture<Object>> handle(DeserializingMessage message) {
            Optional<CompletableFuture<Object>> firstResult = first.handle(message);
            Optional<CompletableFuture<Object>> secondResult = second.handle(message);
            return firstResult.isPresent() ? secondResult.map(messageCompletableFuture -> firstResult.get()
                    .thenCombine(messageCompletableFuture, (a, b) -> a)).or(() -> firstResult) : secondResult;
        }

        @Override
        public boolean canHandle(DeserializingMessage message) {
            return first.canHandle(message) || second.canHandle(message);
        }

        @Override
        public Registration registerHandler(Object target) {
            return first.registerHandler(target).merge(second.registerHandler(target));
        }

        @Override
        public boolean hasLocalHandlers() {
            return first.hasLocalHandlers() || second.hasLocalHandlers();
        }

        @Override
        public void setSelfHandlerFilter(HandlerFilter selfHandlerFilter) {
            first.setSelfHandlerFilter(selfHandlerFilter);
            second.setSelfHandlerFilter(selfHandlerFilter);
        }

        @Override
        public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
            return first.registerHandler(target, handlerFilter).merge(second.registerHandler(target, handlerFilter));
        }
    }

    /**
     * A no-op handler registry that performs no registration or dispatch.
     */
    enum NoOpHandlerRegistry implements HandlerRegistry {
        INSTANCE;

        @Override
        public Optional<CompletableFuture<Object>> handle(DeserializingMessage message) {
            return Optional.empty();
        }

        @Override
        public boolean hasLocalHandlers() {
            return false;
        }

        @Override
        public void setSelfHandlerFilter(HandlerFilter selfHandlerFilter) {
            // no-op
        }

        @Override
        public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
            return Registration.noOp();
        }
    }
}
