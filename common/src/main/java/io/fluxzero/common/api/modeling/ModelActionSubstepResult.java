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

import lombok.Value;

import java.util.List;

/**
 * Runtime-assigned positions for one committed model-action substep.
 */
@Value
public class ModelActionSubstepResult {

    /**
     * Namespace-wide state transition position.
     */
    long stateIndex;

    /**
     * Existing global event-log index, or {@code null} when the event was not published.
     */
    Long eventIndex;

    /**
     * Resulting per-model stream/head positions.
     */
    List<ModelActionTargetResult> targets;
}
