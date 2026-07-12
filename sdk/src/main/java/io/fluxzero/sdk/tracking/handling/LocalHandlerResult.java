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

package io.fluxzero.sdk.tracking.handling;

import java.util.concurrent.CompletableFuture;

/**
 * Result of trying to handle a message locally.
 *
 * <p>This type preserves a synchronously returned handler value as a direct value instead of wrapping it immediately
 * in a {@link CompletableFuture}. It also distinguishes a handler that returned {@code null} from a message for which
 * no local handler was found.</p>
 */
public final class LocalHandlerResult {
    private static final LocalHandlerResult notHandled = new LocalHandlerResult(false, null, null);

    private final boolean handled;
    private final Object value;
    private final CompletableFuture<Object> future;

    private LocalHandlerResult(boolean handled, Object value, CompletableFuture<Object> future) {
        this.handled = handled;
        this.value = value;
        this.future = future;
    }

    /**
     * Returns the shared result for a message that had no matching local handler.
     *
     * @return a not-handled result
     */
    public static LocalHandlerResult notHandled() {
        return notHandled;
    }

    /**
     * Creates a successfully completed local result. The value itself may be {@code null}.
     *
     * @param value the value returned by the handler
     * @return a synchronously completed result
     */
    public static LocalHandlerResult completed(Object value) {
        return new LocalHandlerResult(true, value, null);
    }

    @SuppressWarnings("unchecked")
    public static LocalHandlerResult asynchronous(CompletableFuture<?> future) {
        return new LocalHandlerResult(true, null, (CompletableFuture<Object>) future);
    }

    public static LocalHandlerResult failed(Throwable error) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return asynchronous(future);
    }

    public boolean isHandled() {
        return handled;
    }

    public boolean isCompletedSuccessfully() {
        return handled && future == null;
    }

    public Object getValue() {
        return value;
    }

    public CompletableFuture<Object> asFuture() {
        return future == null ? CompletableFuture.completedFuture(value) : future;
    }
}
    /**
     * Creates a local result whose handler completion is represented by a future.
     *
     * @param future the handler result future
     * @return an asynchronous result
     */
    /**
     * Creates an asynchronous result that has already failed with the supplied error.
     *
     * @param error the handling error
     * @return a failed result
     */
    /**
     * Returns whether a local handler accepted the message.
     *
     * @return {@code true} for completed, asynchronous, and failed handler results
     */
    /**
     * Returns whether the local handler completed synchronously without throwing an error.
     *
     * @return {@code true} when {@link #getValue()} contains the handler's result
     */
    /**
     * Returns the synchronously completed handler value.
     *
     * @return the handler value, which may be {@code null}; meaningful only when
     * {@link #isCompletedSuccessfully()} is {@code true}
     */
    /**
     * Returns the handler result as a future.
     *
     * <p>A synchronously completed value is exposed through an already completed future. Call
     * {@link #isHandled()} first when a not-handled result must be distinguished from a handler that returned
     * {@code null}.</p>
     *
     * @return a future for the handler value or failure
     */
