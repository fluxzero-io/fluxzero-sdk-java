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

import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

import java.util.List;

/**
 * Exact retained materialization for one committed model action.
 * <p>
 * {@link #complete} means the runtime materialization fence is already closed. In that case the mutation lists are
 * empty. Otherwise callers must apply every returned mutation idempotently and acknowledge {@link #lastStateIndex}.
 */
@Value
public class GetModelActionMaterializationResult extends AbstractRequestResult {
    long requestId;
    String actionId;
    long lastStateIndex;
    boolean complete;
    List<ModelDocumentMaterialization> documents;
    List<ModelSnapshotMaterialization> snapshots;
    long timestamp = System.currentTimeMillis();

    @Override
    public Object toMetric() {
        long bytes = documents.stream()
                .mapToLong(value -> value.getMutation().getBytes())
                .sum();
        bytes += snapshots.stream()
                .mapToLong(value -> value.getMutation().getBytes())
                .sum();
        return new Metric(
                actionId, lastStateIndex, complete,
                documents.size(), snapshots.size(), bytes);
    }

    public record Metric(
            String actionId,
            long lastStateIndex,
            boolean complete,
            int documentCount,
            int snapshotCount,
            long bytes) {
    }
}
