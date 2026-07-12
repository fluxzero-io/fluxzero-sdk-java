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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.tracking.handling.LocalExecution;

/**
 * Applies a {@link DispatchInterceptor} to local dispatch without requiring Fluxzero to create a complete message
 * before handler selection.
 *
 * <p>A prepared policy is created for one {@link LocalDispatchDescriptor}, may be cached, and may be invoked
 * concurrently. Implementations must therefore be immutable or otherwise thread-safe. Data belonging to one dispatch
 * is available through the supplied {@link LocalExecution}.</p>
 *
 * @see DispatchInterceptor#prepareLocalDispatch(LocalDispatchDescriptor)
 */
public interface PreparedLocalDispatch {
    /** A policy that leaves every local dispatch unchanged. */
    PreparedLocalDispatch noOp = new PreparedLocalDispatch() {
    };

    /**
     * Performs work that must happen before Fluxzero selects a local handler.
     *
     * <p>This method runs once for each dispatch. Returning {@code false} abandons prepared local handling and makes
     * the caller use the regular message-based dispatch path. It must not suppress a message; suppression remains part
     * of the regular {@link DispatchInterceptor#interceptDispatch(io.fluxzero.sdk.common.Message,
     * io.fluxzero.common.MessageType, String)} contract.</p>
     *
     * @param execution the current local dispatch
     * @return {@code true} to continue prepared local handling, or {@code false} to use the regular dispatch path
     */
    default boolean prepare(LocalExecution execution) {
        return true;
    }

    /**
     * Returns whether {@link #prepare(LocalExecution)} performs per-dispatch work.
     *
     * <p>Return {@code false} only when calling {@code prepare} can always be skipped safely. The default is
     * conservative and returns {@code true}.</p>
     *
     * @return {@code true} if preparation must run before local handler selection
     */
    default boolean requiresPreparation() {
        return true;
    }

    /**
     * Applies this interceptor's metadata changes when metadata is first requested for the local message.
     *
     * <p>This method may run later than {@link #prepare(LocalExecution)}, or not at all when neither the handler nor
     * another interceptor observes metadata. Its result must match the metadata produced by regular dispatch.</p>
     *
     * @param metadata the metadata produced by earlier prepared dispatch policies
     * @param execution the current local dispatch
     * @return the metadata to pass to later policies and the handler
     */
    default Metadata interceptMetadata(Metadata metadata, LocalExecution execution) {
        return metadata;
    }
}
