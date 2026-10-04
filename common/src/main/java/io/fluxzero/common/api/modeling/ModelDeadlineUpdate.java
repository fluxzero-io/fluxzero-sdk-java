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

import io.fluxzero.common.api.scheduling.SerializedSchedule;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A changed Model-owned deadline. A null schedule cancels the category; omitted categories remain
 * untouched.
 *
 * @param rescheduleOnly replace only an active intent in this category, checked atomically at commit
 *                       after any claim consumption; never recreate consumed or canceled work
 */
public record ModelDeadlineUpdate(
        String modelId, String category, SerializedSchedule schedule, boolean cancelOnDeletion,
        boolean rescheduleOnly) {
    /** Namespace-local reserved schedule identity, independent of declaration method names. */
    public String slotId() {
        return scheduleId(modelId, category);
    }

    /** Derives the stable schedule identity without exposing application string conventions. */
    public static String scheduleId(String modelId, String category) {
        return "$model-deadline:"
                + UUID.nameUUIDFromBytes(
                        (modelId.length() + ":" + modelId + category)
                                .getBytes(StandardCharsets.UTF_8));
    }
}
