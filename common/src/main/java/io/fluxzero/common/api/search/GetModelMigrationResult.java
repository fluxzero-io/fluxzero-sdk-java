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

import io.fluxzero.common.api.AbstractRequestResult;
import io.fluxzero.common.api.modeling.ModelHeadState;
import lombok.Value;

import java.beans.ConstructorProperties;

/** Production and staged state for an application-verified direct Model migration. */
@Value
public class GetModelMigrationResult extends AbstractRequestResult {
    long requestId;
    SerializedDocument productionDocument;
    Long productionDocumentIndex;
    SerializedDocument migratedDocument;
    ModelHeadState migratedHead;
    long timestamp = System.currentTimeMillis();

    @ConstructorProperties({"requestId", "productionDocument", "productionDocumentIndex",
            "migratedDocument", "migratedHead"})
    public GetModelMigrationResult(
            long requestId,
            SerializedDocument productionDocument,
            Long productionDocumentIndex,
            SerializedDocument migratedDocument,
            ModelHeadState migratedHead) {
        if ((productionDocument == null) != (productionDocumentIndex == null)) {
            throw new IllegalArgumentException(
                    "A production document and its conditional index must be present together");
        }
        if (migratedHead != null
            && migratedHead.isDeleted() != (migratedDocument == null)) {
            throw new IllegalArgumentException(
                    "A staged Model document must match its durable head");
        }
        this.requestId = requestId;
        this.productionDocument = productionDocument;
        this.productionDocumentIndex = productionDocumentIndex;
        this.migratedDocument = migratedDocument;
        this.migratedHead = migratedHead;
    }

    @Override
    public Metric toMetric() {
        return new Metric(
                timestamp, productionDocument != null,
                migratedHead != null, migratedDocument != null);
    }

    /** Content-free metrics representation. */
    public record Metric(
            long timestamp,
            boolean productionDocumentPresent,
            boolean migrationPresent,
            boolean migratedDocumentPresent) {
    }
}
