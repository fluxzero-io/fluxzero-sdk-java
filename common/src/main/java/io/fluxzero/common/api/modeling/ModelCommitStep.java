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

import io.fluxzero.common.api.SerializedMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.beans.Transient;
import java.util.List;

/**
 * One ordered original event and all model targets affected by it.
 * <p>
 * {@link #event} may be {@code null} only for a non-stored, non-published transition such as
 * {@code EventPublication.NEVER}. Such a transition still receives a state index when it updates model state.
 */
@Value
@Builder(toBuilder = true)
@AllArgsConstructor
public class ModelCommitStep {

    /**
     * Serialized original event, stored only once in this request even when several models are targeted.
     *
     * <p>An event which already has an index refers to that exact existing event in the global event log and must not
     * be published again.</p>
     */
    SerializedMessage event;

    /**
     * Whether the original event should be appended once to the global event log. This is always {@code false} when
     * {@link #event} already carries its durable global index.
     */
    boolean publishEvent;

    /**
     * Model stream/state targets of this original event.
     */
    List<ModelCommitTarget> targets;

    /**
     * Complete serialized event-message bytes carried by this substep.
     */
    @Transient
    public long getBytes() {
        return event == null ? 0L : event.getBytes();
    }

    /**
     * Number of target streams receiving an event membership.
     */
    @Transient
    public int getStoredTargetCount() {
        int result = 0;
        for (ModelCommitTarget target : targets) {
            if (target.isStoreEvent()) {
                result++;
            }
        }
        return result;
    }

    /**
     * Number of desired current parent relationships carried by all targets.
     */
    @Transient
    public int getRelationCount() {
        int result = 0;
        for (ModelCommitTarget target : targets) {
            result += target.getRelationships().size();
        }
        return result;
    }
}
