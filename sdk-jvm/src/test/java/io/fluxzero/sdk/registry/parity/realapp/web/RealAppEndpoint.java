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

package io.fluxzero.sdk.registry.parity.realapp.web;

import io.fluxzero.sdk.registry.parity.realapp.RealAppCommand;
import io.fluxzero.sdk.registry.parity.realapp.RealAppResult;
import io.fluxzero.sdk.web.HandleDelete;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.HandlePut;
import io.fluxzero.sdk.web.HandleSocketClose;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.PathParam;

@Path("orders")
public class RealAppEndpoint {
    @HandleGet("{id}")
    public RealAppResult get(@PathParam String id) {
        return new RealAppResult(id);
    }

    @HandlePost
    public RealAppResult post(RealAppCommand command) {
        return new RealAppResult(command.id());
    }

    @HandlePut("{id}")
    public RealAppResult put(@PathParam String id, RealAppCommand command) {
        return new RealAppResult(id + command.id());
    }

    @HandleDelete("{id}")
    public void delete(@PathParam String id) {
    }

    @Path("socket")
    @HandleSocketOpen
    public String open() {
        return "open";
    }

    @Path("socket")
    @HandleSocketMessage
    public String message(String payload) {
        return payload;
    }

    @Path("socket")
    @HandleSocketClose
    public void close() {
    }
}
