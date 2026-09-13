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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Schedules messages owned by existing, committed Models in the same namespace.
 * Deletion of any parent asynchronously cancels the accepted schedules. The server binds
 * the ownership to the parent's current lifetime; recreation does not revive old schedules.
 * This separate operation deliberately fails on servers that do not support ownership.
 */
@Value
public class ScheduleWithParents extends Command {
    /** Messages to schedule, with the usual replacement and ifAbsent semantics. */
    List<SerializedSchedule> messages;
    /** Non-empty lifetime bindings from GetScheduleParents, retained across transport retries. */
    Map<String, Long> parents;
    /** Requested acknowledgement guarantee. */
    Guarantee guarantee;

    @Override
    public Object toMetric() {
        return new Metric(messages.size(), parents.size());
    }

    /** Payload-free operation summary. */
    public record Metric(int size, int parentCount) {}
}
