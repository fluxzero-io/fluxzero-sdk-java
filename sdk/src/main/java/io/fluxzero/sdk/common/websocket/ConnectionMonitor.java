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

package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.Registration;

/** A cancellable observation of one WebSocket route on a concrete client. */
public interface ConnectionMonitor extends Registration {
    /**
     * Returns whether every slot used by the route is currently open. Reads local transport state
     * without opening a session or waiting for a listener. Returns false after cancellation or shutdown.
     */
    boolean isConnected();
}
