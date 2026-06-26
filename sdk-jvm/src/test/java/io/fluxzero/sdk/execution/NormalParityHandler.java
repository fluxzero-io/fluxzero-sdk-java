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

package io.fluxzero.sdk.execution;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityCommand;
import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityEvent;
import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityFailingCommand;
import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityFollowUpEvent;
import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityQuery;
import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityResult;
import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityValidatedCommand;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.PathParam;

@LocalHandler
public class NormalParityHandler {
    @HandleCommand
    public ParityResult handle(ParityCommand command) {
        return new ParityResult("command:" + command.value());
    }

    @HandleCommand
    public ParityResult handle(ParityValidatedCommand command) {
        return new ParityResult("validated:" + command.value());
    }

    @HandleCommand
    public String handle(ParityFailingCommand command) {
        throw new IllegalStateException(command.value());
    }

    @HandleQuery
    public ParityResult handle(ParityQuery query) {
        return new ParityResult("query:" + query.value());
    }

    @HandleEvent
    public void on(ParityEvent event) {
        Fluxzero.publishEvent(new ParityFollowUpEvent("event:" + event.value()));
    }

    @HandleGet("/parity/{id}")
    public String get(@PathParam("id") String id) {
        return "web:" + id;
    }
}
