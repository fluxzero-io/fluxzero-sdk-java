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
 *
 */

package io.fluxzero.proxy;

import java.io.IOException;

class WebsocketSendBacklogExceededException extends IOException {
    private final String sessionId;
    private final String clientId;
    private final String trackerId;
    private final String namespace;
    private final int pendingSends;
    private final int maxPendingSends;

    WebsocketSendBacklogExceededException(String sessionId, String clientId, String trackerId, String namespace,
                                          int pendingSends, int maxPendingSends) {
        super("Websocket send backlog exceeded " + maxPendingSends + " pending sends");
        this.sessionId = sessionId;
        this.clientId = valueOrDefault(clientId, "<unknown>");
        this.trackerId = valueOrDefault(trackerId, "<unknown>");
        this.namespace = valueOrDefault(namespace, "<default>");
        this.pendingSends = pendingSends;
        this.maxPendingSends = maxPendingSends;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    String sessionId() {
        return sessionId;
    }

    String clientId() {
        return clientId;
    }

    String trackerId() {
        return trackerId;
    }

    String namespace() {
        return namespace;
    }

    int pendingSends() {
        return pendingSends;
    }

    int maxPendingSends() {
        return maxPendingSends;
    }
}
