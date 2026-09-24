/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;

/** Adopts a staged source separately while fencing the observed legacy/public projection in the same transaction. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class AdoptModelMigrationWithSource extends AdoptModelMigration {
    private final String sourceCollection;

    @ConstructorProperties({"modelId", "collection", "expectedDocumentIndex", "expectedStateIndex", "guarantee", "sourceCollection"})
    public AdoptModelMigrationWithSource(String modelId, String collection, Long expectedDocumentIndex,
                                         long expectedStateIndex, Guarantee guarantee, String sourceCollection) {
        super(modelId, collection, expectedDocumentIndex, expectedStateIndex, guarantee);
        if (sourceCollection == null || !sourceCollection.startsWith(ModelDocumentMutation.PRIVATE_MODEL_DOCUMENT_COLLECTION_PREFIX)
            || sourceCollection.equals(collection)) {
            throw new IllegalArgumentException("Adoption requires a distinct internal Model source collection");
        }
        this.sourceCollection = sourceCollection;
    }

    @Override
    public String sourceCollection() { return sourceCollection; }
}
