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

import io.fluxzero.common.api.Request;
import lombok.Value;

import java.util.List;

/** Binds canonical Model parent IDs to their committed lifetimes in this namespace. */
@Value
public class GetScheduleParents extends Request {
    /** Canonical IDs of existing, non-deleted Models. */
    List<String> parentIds;

    @Override
    public Object toMetric() {
        return new Metric(parentIds.size());
    }

    /** Payload-free request summary. */
    public record Metric(int parentCount) {}
}
