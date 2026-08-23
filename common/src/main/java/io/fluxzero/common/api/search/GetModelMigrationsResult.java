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

package io.fluxzero.common.api.search;

import io.fluxzero.common.api.AbstractRequestResult;
import io.fluxzero.common.api.modeling.ModelHeadState;
import lombok.Value;

import java.beans.ConstructorProperties;
import java.util.List;

/** A bounded batch of durable heads from the otherwise invisible Model-migration staging area. */
@Value
public class GetModelMigrationsResult extends AbstractRequestResult {
    long requestId;
    List<ModelHeadState> migrations;
    long timestamp = System.currentTimeMillis();

    @ConstructorProperties({"requestId", "migrations"})
    public GetModelMigrationsResult(
            long requestId,
            List<ModelHeadState> migrations) {
        this.requestId = requestId;
        this.migrations = migrations == null
                ? List.of() : List.copyOf(migrations);
    }

    @Override
    public Metric toMetric() {
        return new Metric(timestamp, migrations.size());
    }

    /** Content-free metrics representation. */
    public record Metric(long timestamp, int migrationCount) {
    }
}
