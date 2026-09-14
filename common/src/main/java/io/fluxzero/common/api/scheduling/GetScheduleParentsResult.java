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

package io.fluxzero.common.api.scheduling;

import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

import java.util.Map;

/** Immutable namespace-local lifetime bindings for a subsequent ScheduleWithParents operation. */
@Value
public class GetScheduleParentsResult extends AbstractRequestResult {
    /** Correlation ID. */
    long requestId;
    /** Opaque parent tokens and deletion epochs. Clients must forward these unchanged. */
    Map<String, Long> parents;
    /** Response creation time in epoch milliseconds. */
    long timestamp = System.currentTimeMillis();

    @Override
    public Object toMetric() {
        return new GetScheduleParents.Metric(parents.size());
    }
}
