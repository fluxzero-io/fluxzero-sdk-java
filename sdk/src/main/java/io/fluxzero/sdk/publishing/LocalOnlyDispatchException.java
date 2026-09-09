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

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.tracking.handling.LocalHandlerSelection;

import java.util.Locale;

/**
 * Thrown when a {@link LocalOnly} payload cannot be dispatched through exactly one exclusively local request handler.
 */
public class LocalOnlyDispatchException extends GatewayException {

    /**
     * Creates a fail-closed dispatch exception.
     *
     * @param payloadType the intercepted payload type that was about to be dispatched
     * @param messageType the gateway message type
     * @param outcome the reason local handler selection was rejected
     */
    public LocalOnlyDispatchException(Class<?> payloadType, MessageType messageType,
                                      LocalHandlerSelection.Outcome outcome) {
        super(message(payloadType, messageType, outcome), null);
    }

    private static String message(Class<?> payloadType, MessageType messageType,
                                  LocalHandlerSelection.Outcome outcome) {
        String type = payloadType == null ? "null" : payloadType.getName();
        if (messageType != MessageType.COMMAND && messageType != MessageType.QUERY) {
            return "@LocalOnly only supports command and query payloads, but " + type
                   + " was dispatched as " + messageType;
        }
        return "Could not dispatch local-only " + messageType.name().toLowerCase(Locale.ROOT) + " " + type
               + ": " + outcome.description();
    }
}
