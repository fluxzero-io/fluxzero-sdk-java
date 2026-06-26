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

package io.fluxzero.sdk.browser;

import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.QueryGateway;

import java.util.Objects;

/**
 * Factory methods for browser-safe Fluxzero execution facades.
 */
public final class BrowserFluxzero {

    private BrowserFluxzero() {
    }

    /**
     * Creates a browser application from generated command and query dispatchers.
     */
    public static FluxzeroBrowserApplication create(CommandDispatcher commandDispatcher, QueryDispatcher queryDispatcher) {
        CommandGateway commandGateway = new BrowserCommandGateway(commandDispatcher);
        QueryGateway queryGateway = new BrowserQueryGateway(queryDispatcher);
        return create(commandGateway, queryGateway);
    }

    /**
     * Creates a browser application from concrete browser-safe gateways.
     */
    public static FluxzeroBrowserApplication create(CommandGateway commandGateway, QueryGateway queryGateway) {
        Objects.requireNonNull(commandGateway, "commandGateway");
        Objects.requireNonNull(queryGateway, "queryGateway");
        return new DefaultBrowserApplication(commandGateway, queryGateway);
    }

    private record DefaultBrowserApplication(CommandGateway commandGateway, QueryGateway queryGateway)
            implements FluxzeroBrowserApplication {
        private DefaultBrowserApplication {
            Objects.requireNonNull(commandGateway, "commandGateway");
            Objects.requireNonNull(queryGateway, "queryGateway");
        }
    }
}
