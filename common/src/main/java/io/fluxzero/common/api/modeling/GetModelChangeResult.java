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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

import java.util.List;

/** Durable target identities and types for one model-commit substep. */
@Value
public class GetModelChangeResult extends AbstractRequestResult {
    long requestId;
    String commitId;
    int substep;
    long stateIndex;
    Long eventIndex;
    List<ModelChangeTarget> targets;
    long timestamp = System.currentTimeMillis();

    @Override
    public Metric toMetric() {
        return new Metric(substep, stateIndex, eventIndex, targets.size(), timestamp);
    }

    /** Identity-free metric representation. */
    @Value
    public static class Metric {
        int substep;
        long stateIndex;
        Long eventIndex;
        int targetCount;
        long timestamp;
    }
}
