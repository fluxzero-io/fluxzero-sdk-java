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
package io.fluxzero.sdk.persisting.search;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.RewriteModelSourceDocument;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.modeling.ModelDocumentProof;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.persisting.search.client.SearchClient;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

/** Internal schema-only return guard for handlers explicitly selecting a maintained Model source. */
public final class ModelSourceDocumentMigration {
    private final DocumentSerializer serializer;
    private final EntityMetadata metadata;
    private final String modelId;
    private final Object handledState;
    private final RewriteModelSourceDocument rewrite;
    private final SearchClient client;

    private ModelSourceDocumentMigration(DocumentSerializer serializer, EntityMetadata metadata,
                                         String modelId, Object handledState,
                                         RewriteModelSourceDocument rewrite, SearchClient client) {
        this.serializer = serializer;
        this.metadata = metadata;
        this.modelId = modelId;
        this.handledState = handledState;
        this.rewrite = rewrite;
        this.client = client;
    }

    /** Captures the handled value before application code can mutate it, and inspects the verified current source. */
    public static ModelSourceDocumentMigration prepare(DeserializingMessage message, Class<?> type, String collection,
                                                       DocumentSerializer serializer, SearchClient client) {
        if (client == null) {
            throw new UnsupportedOperationException("Internal Model source migration has no configured client");
        }
        EntityMetadata metadata = EntityMetadata.validate(type);
        Object handledState = serializer.modelStateSnapshot(message.getPayload());
        validateIdentity(metadata, message.getMessageId(), message.getPayload());
        var inspected = client.fetchModelDocument(new GetDocument(message.getMessageId(), collection, true, true));
        SerializedDocument current = inspected.getDocument();
        ModelHeadState head = inspected.getModelHead();
        if (current == null || head == null || head.isDeleted()) {
            return new ModelSourceDocumentMigration(serializer, metadata, message.getMessageId(), handledState, null, client);
        }
        if (!inspected.isModelStateVerified()) {
            throw new IllegalStateException("Internal Model source has no verified durable state: " + message.getMessageId());
        }
        Function<Object, SerializedDocument> serialize = value -> {
            validateIdentity(metadata, current.getId(), value);
            SerializedDocument result = serializer.toDocument(value, current.getId(), collection,
                    instant(current.getTimestamp()), instant(current.getEnd()), current.getMetadata());
            return !metadata.rootConfiguration().orElseThrow().publicDocument() && !metadata.maintainsGraphComponentDocument()
                    ? result.withoutSearchIndexes() : result;
        };
        SerializedDocument handled = serialize.apply(message.getPayload());
        RewriteModelSourceDocument rewrite = null;
        if (handled.getDocument().getRevision() > current.getDocument().getRevision()) {
            Object currentValue = serializer.fromDocument(current, type);
            if (Objects.equals(handledState, serializer.modelStateSnapshot(currentValue))) {
                SerializedDocument expected = serialize.apply(currentValue);
                rewrite = new RewriteModelSourceDocument(expected, head, ModelDocumentProof.of(current, head), Guarantee.STORED);
            }
        }
        return new ModelSourceDocumentMigration(serializer, metadata, message.getMessageId(), handledState, rewrite, client);
    }

    /** Rejects state mutations and conditionally persists only the verified source's ordinary upcast result. */
    public void finish(Object result) {
        validateIdentity(metadata, modelId, result);
        if (!handledState.equals(serializer.modelStateSnapshot(result))) {
            throw new IllegalArgumentException("Model source migration cannot change business state; return the injected upcast value unchanged");
        }
        if (rewrite != null) {
            client.rewriteModelSourceDocument(rewrite).join();
        }
    }

    private static void validateIdentity(EntityMetadata metadata, String id, Object value) {
        if (value == null || !metadata.type().isInstance(value) || !id.equals(metadata.repositoryIdOf(value))) {
            throw new IllegalArgumentException("Model source migration must retain Model type and identity; use @Apply for state changes or deletion");
        }
    }

    private static Instant instant(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }
}
