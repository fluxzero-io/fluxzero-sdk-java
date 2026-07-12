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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerMethodPlan;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;

/**
 * Gives Fluxzero handling extensions access to a local invocation whose full message is created only when needed.
 *
 * <p>This is an infrastructure interface. Applications normally receive the payload, metadata, user, or message as
 * handler parameters and do not implement or invoke this interface directly.</p>
 */
public interface LocalHandlerInput extends HandlerInput<DeserializingMessage> {

    /**
     * Returns the type of message being handled.
     *
     * @return the current message type
     */
    MessageType getMessageType();

    /**
     * Returns the user selected for this local dispatch.
     *
     * @param provider the configured provider whose user is requested
     * @return the resolved user, or {@code null} if the dispatch has no user
     */
    User getUser(UserProvider provider);

    /**
     * Returns whether user resolution has already completed for this input.
     *
     * @return {@code true} when {@link #getUser(UserProvider)} can be used without materializing the message
     */
    boolean hasResolvedUser();

    /**
     * Returns the local message metadata, computing deferred metadata changes when necessary.
     *
     * @return the current metadata
     */
    Metadata getMetadata();

    /**
     * Tests already available metadata without forcing deferred metadata creation.
     *
     * @param key the metadata key to look up
     * @return {@code true} if already available metadata contains the key
     */
    boolean containsMetadata(String key);

    /**
     * Returns the message index when the local input represents a stored message.
     *
     * @return the message index, or {@code null} for a newly dispatched local message
     */
    Long getIndex();

    /**
     * Invokes a prepared handler plan while installing the required local handling context.
     *
     * @param plan the handler plan to invoke
     * @return the handler result
     */
    Object invoke(HandlerMethodPlan<DeserializingMessage> plan);
}
