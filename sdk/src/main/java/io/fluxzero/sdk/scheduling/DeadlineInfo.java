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

package io.fluxzero.sdk.scheduling;

import java.time.Instant;
import java.util.Objects;

/**
 * The last deadline declared by one Model category, as recorded with that Model revision.
 * This describes the Model's plan, not execution or external cancellation in the scheduler.
 *
 * @param scheduleId public identity used by the ordinary scheduler
 * @param deadline original resolved execution time, preserved across unrelated Model changes
 * @param command whether the payload is dispatched as a scheduled command
 * @param cancelOnDeletion whether deleting the Model cancels this schedule
 */
public record DeadlineInfo(String scheduleId, Instant deadline, boolean command, boolean cancelOnDeletion) {
    public DeadlineInfo {
        Objects.requireNonNull(scheduleId, "scheduleId");
        Objects.requireNonNull(deadline, "deadline");
    }
}
