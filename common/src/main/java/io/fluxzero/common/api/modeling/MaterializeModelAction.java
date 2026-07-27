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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.List;

/**
 * Applies one exact, runtime-positioned model materialization package to a possibly separate search runtime.
 * <p>
 * Search stores must fence direct-document mutations by {@code stateIndex}; retries and out-of-order delivery may
 * therefore never replace a model document with an older or conflicting equal-index value. Snapshots are immutable
 * and trimmed idempotently.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class MaterializeModelAction extends Command {
    String actionId;
    long lastStateIndex;
    List<ModelDocumentMaterialization> documents;
    List<ModelSnapshotMaterialization> snapshots;
    Guarantee guarantee = Guarantee.STORED;

    @Override
    public String routingKey() {
        return actionId;
    }

    @Override
    public Object toMetric() {
        long bytes = documents.stream()
                .mapToLong(value -> value.getMutation().getBytes())
                .sum();
        bytes += snapshots.stream()
                .mapToLong(value -> value.getMutation().getBytes())
                .sum();
        return new Metric(
                actionId, lastStateIndex,
                documents.size(), snapshots.size(), bytes);
    }

    public record Metric(
            String actionId,
            long lastStateIndex,
            int documentCount,
            int snapshotCount,
            long bytes) {
    }
}
