/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.downstream;

import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

import java.util.List;

@Model(eventSourced = false, searchable = true, collection = "downstream-models")
public record DownstreamModel(@EntityId String id, String value, @Member List<Part> parts) {

    @Apply
    DownstreamModel update(ChangeValue command) {
        return new DownstreamModel(id, command.value(), parts);
    }

    public record Part(@EntityId String id) {
    }

    public record ChangeValue(String value) {
    }
}
