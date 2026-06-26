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

import java.util.Objects;

/**
 * TeaVM-safe {@link CommandGateway} implementation backed by generated dispatch code.
 */
public final class BrowserCommandGateway implements CommandGateway {
    private final CommandDispatcher dispatcher;

    public BrowserCommandGateway(CommandDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void sendAndForget(Object command) {
        dispatcher.dispatchAndForget(command);
    }

    @Override
    public <R> R sendAndWait(Object command) {
        return (R) dispatcher.dispatch(command);
    }
}
