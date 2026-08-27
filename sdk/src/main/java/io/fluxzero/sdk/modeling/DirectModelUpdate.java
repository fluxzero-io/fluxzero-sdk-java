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

package io.fluxzero.sdk.modeling;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fluxzero.common.api.Data;
import lombok.Value;

import java.util.List;
import java.util.Objects;

/**
 * Framework event that durably replays direct {@link Graph#update(java.util.function.UnaryOperator)} and
 * {@link Graph#delete()} changes.
 * <p>
 * A direct graph update has no serializable domain apply of its own. Fluxzero therefore stores the resulting typed
 * model value in this event while publishing the original domain event separately. Multiple models changed by one
 * interceptor share one event payload. A {@code null} state denotes deletion.
 * <p>
 * Applications normally never construct or handle this type directly.
 */
@Value
public class DirectModelUpdate {
    List<Target> targets;

    @JsonCreator
    public DirectModelUpdate(@JsonProperty("targets") List<Target> targets) {
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (this.targets.isEmpty()) {
            throw new IllegalArgumentException("A direct model update requires at least one target");
        }
    }

    /** Returns the one state carried for the requested persisted model identity. */
    public Target target(String modelId) {
        Target result = null;
        for (Target candidate : targets) {
            if (!candidate.modelId.equals(modelId)) {
                continue;
            }
            if (result != null) {
                throw new IllegalStateException(
                        "Direct model update contains duplicate target " + modelId);
            }
            result = candidate;
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Direct model update does not contain target " + modelId);
        }
        return result;
    }

    /** One exact model stream and its resulting serialized value. */
    @Value
    public static class Target {
        String modelId;
        Data<byte[]> state;

        @JsonCreator
        public Target(
                @JsonProperty("modelId") String modelId,
                @JsonProperty("state") Data<byte[]> state) {
            this.modelId = Objects.requireNonNull(modelId, "modelId");
            this.state = state;
        }
    }
}
