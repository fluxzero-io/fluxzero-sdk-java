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

package io.fluxzero.sdk.configuration.client;

import io.fluxzero.common.ServicePathBuilder;
import io.fluxzero.common.api.GetNamespaces;
import io.fluxzero.common.api.GetNamespacesResult;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.common.websocket.ServiceUrlBuilder;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** On-demand transport for Runtime-wide namespace discovery. */
final class WebsocketNamespaceClient extends AbstractWebsocketClient {
    WebsocketNamespaceClient(WebSocketClient client) {
        super(URI.create(ServiceUrlBuilder.buildUrl(client.getClientConfig(), ServicePathBuilder.namespacesPath())),
              client, false, 1);
    }

    CompletableFuture<List<String>> getNamespaces() {
        return this.<GetNamespacesResult>send(new GetNamespaces()).thenApply(GetNamespacesResult::getNamespaces);
    }
}
