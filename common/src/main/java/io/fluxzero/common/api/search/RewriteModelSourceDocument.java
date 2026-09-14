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
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelHeadState;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

/**
 * Persists a schema-only rewrite of an internal Model source without advancing the Model's head or history.
 * Both the complete head and the verified previous document proof must still match. A stale request is a no-op;
 * ordinary document writes never acquire trusted Model-state provenance through this operation.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class RewriteModelSourceDocument extends Command {
    @NonNull SerializedDocument document;
    @NonNull ModelHeadState expectedHead;
    @NonNull String expectedProof;
    @NonNull Guarantee guarantee;

    /** Validates the source-only, non-deleting envelope before any persistence action. */
    public void validate() {
        if (expectedHead.isDeleted() || !document.getId().equals(expectedHead.getModelId())
            || !document.getCollection().startsWith(ModelDocumentMutation.PRIVATE_MODEL_DOCUMENT_COLLECTION_PREFIX)
            || expectedProof.isBlank()) {
            throw new IllegalArgumentException("A schema rewrite requires a live internal Model source with unchanged identity");
        }
    }

    @Override
    public String routingKey() { return document.getId(); }
}
