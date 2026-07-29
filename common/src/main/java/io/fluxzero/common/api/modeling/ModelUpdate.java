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
 * Resulting target heads of one committed model-commit substep.
 * <p>
 * {@link #eventIndex} links a published domain event to this exact model-state boundary. It is {@code null} for
 * store-only, publication-free, document-only, and relationship-only transitions.
 */
@Value
public class ModelUpdate {

    /**
     * Nature of the durable update.
     */
    ModelUpdateKind kind;

    /**
     * Durable model commit identity.
     */
    String commitId;

    /**
     * Ordered substep within the commit.
     */
    int substep;

    /**
     * Namespace-wide committed model-state position.
     */
    long stateIndex;

    /**
     * Global event-log index when this substep published an event, otherwise {@code null}.
     */
    Long eventIndex;

    /**
     * Resulting positions of every model targeted by the substep. Empty for {@link ModelUpdateKind#HARD_DELETE}.
     */
    List<ModelCommitTargetResult> targets;
}
