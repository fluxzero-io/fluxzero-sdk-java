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

package io.fluxzero.sdk.registry.compiled;

import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.PathParam;
import jakarta.validation.constraints.NotBlank;

@Path("logic")
public class CompiledPackageHandler {

    @LocalHandler(false)
    @HandleCommand(allowedClasses = CompiledCommand.class, passive = true, skipExpiredRequests = true)
    public CompiledResult handle(@NotBlank CompiledCommand command, String ignored) {
        return new CompiledResult(command.value());
    }

    @HandleQuery(disabled = true)
    public String disabled(CompiledCommand query) {
        return query.value();
    }

    @HandleGet(value = {"items/{id}", "items"}, autoHead = false, autoOptions = false)
    public String get(@PathParam String id) {
        return id;
    }

    public record CompiledCommand(String value) {
    }

    public record CompiledResult(String value) {
    }
}
