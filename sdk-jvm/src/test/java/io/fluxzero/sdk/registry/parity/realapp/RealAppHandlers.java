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

package io.fluxzero.sdk.registry.parity.realapp;

import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.HandleError;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.HandleResult;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleWebResponse;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.PathParam;
import jakarta.validation.constraints.NotBlank;

@Consumer(name = "realapp-handler", threads = 3)
@RegisterType(rootClass = RealAppHandlers.class, contains = "RealAppHandlers")
@Path("logic")
public class RealAppHandlers {
    @EntityId
    private String id;

    @Association
    private RealAppCommand command;

    @LocalHandler(false)
    @HandleCommand(allowedClasses = RealAppCommand.class, passive = true, skipExpiredRequests = true)
    public RealAppResult handle(@NotBlank RealAppCommand command) {
        return new RealAppResult(command.id());
    }

    @HandleQuery(disabled = true)
    public RealAppResult query(RealAppCommand query) {
        return new RealAppResult(query.id());
    }

    @HandleEvent
    public void event(RealAppCommand event) {
    }

    @HandleNotification
    public void notification(RealAppCommand notification) {
    }

    @HandleResult
    public void result(RealAppResult result) {
    }

    @HandleSchedule
    public void schedule(RealAppCommand command) {
    }

    @HandleError
    public void error(Throwable error) {
    }

    @HandleMetrics
    public void metrics(RealAppCommand metrics) {
    }

    @HandleDocument
    public void document(RealAppCommand document) {
    }

    @HandleCustom("realapp-audit")
    public void custom(RealAppCommand custom) {
    }

    @HandleWebResponse
    public void webResponse(String response) {
    }

    @HandleGet(value = {"orders/{id}", "orders"}, autoHead = false, autoOptions = false)
    public String get(@PathParam String id) {
        return id;
    }
}
