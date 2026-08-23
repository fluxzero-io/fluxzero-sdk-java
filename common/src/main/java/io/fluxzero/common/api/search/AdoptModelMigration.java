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

package io.fluxzero.common.api.search;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

import java.beans.ConstructorProperties;

/**
 * Atomically adopts an application-verified staged Model document.
 * <p>
 * A {@code null} expected document index means that the production document was observed to be absent. Otherwise the
 * Runtime only adds the direct Model head while that exact production document version still exists. The staged state
 * is removed in the same transaction.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class AdoptModelMigration extends Command {
    @NonNull String modelId;
    @NonNull String collection;
    Long expectedDocumentIndex;
    long expectedStateIndex;
    @NonNull Guarantee guarantee;

    @ConstructorProperties({"modelId", "collection", "expectedDocumentIndex",
            "expectedStateIndex", "guarantee"})
    public AdoptModelMigration(
            String modelId,
            String collection,
            Long expectedDocumentIndex,
            long expectedStateIndex,
            Guarantee guarantee) {
        if (expectedStateIndex < 0L) {
            throw new IllegalArgumentException(
                    "Expected migration state index must not be negative");
        }
        this.modelId = java.util.Objects.requireNonNull(modelId, "modelId");
        this.collection = java.util.Objects.requireNonNull(collection, "collection");
        this.expectedDocumentIndex = expectedDocumentIndex;
        this.expectedStateIndex = expectedStateIndex;
        this.guarantee = java.util.Objects.requireNonNull(guarantee, "guarantee");
    }

    @Override
    public String routingKey() {
        return modelId;
    }
}
