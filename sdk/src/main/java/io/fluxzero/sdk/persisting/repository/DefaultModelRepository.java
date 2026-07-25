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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.ImmutableEntity;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import lombok.NonNull;

import java.util.Objects;
import java.util.Optional;

/**
 * Default repository for independently stored models.
 * <p>
 * Document-based models use their synchronously maintained direct search document as their normal load source.
 * Event-sourced loading is deliberately rejected until model reconstruction is integrated with the batched
 * model-stream protocol; it must not silently fall back to an aggregate stream or a potentially stale composed
 * document.
 */
public class DefaultModelRepository extends AbstractNamespaced<ModelRepository> implements ModelRepository {

    private final Client client;
    private final DocumentStore documentStore;

    public DefaultModelRepository(Client client, DocumentStore documentStore) {
        this.client = client;
        this.documentStore = documentStore;
    }

    @Override
    protected ModelRepository createForNamespace(String namespace) {
        Client namespacedClient = client.forNamespace(namespace);
        return new DefaultModelRepository(namespacedClient, documentStore.forNamespace(namespace));
    }

    @Override
    public <T> Entity<T> load(@NonNull String modelId, @NonNull Class<T> modelType) {
        if (Object.class.equals(modelType)) {
            throw new EventSourcingException(
                    "Loading an independent model by untyped ID requires model-head lookup support");
        }
        ModelMetadata metadata = ModelMetadata.validate(modelType);
        Model annotation = metadata.model().orElseThrow(() -> new IllegalArgumentException(
                modelType.getName() + " is not annotated with @Model"));
        if (annotation.eventSourced()) {
            throw new EventSourcingException(
                    "Event-sourced model reconstruction is not yet integrated with batched model-stream reads");
        }

        String collection = Optional.of(annotation.collection())
                .filter(value -> !value.isEmpty())
                .map(ApplicationProperties::substituteProperties)
                .orElse(modelType.getSimpleName());
        T value = documentStore.fetchDocument(modelId, collection, modelType).orElse(null);
        String idProperty = metadata.entityId().orElseThrow().name();
        if (value != null) {
            Object storedId = metadata.entityId().orElseThrow().read(value);
            if (storedId == null || !Objects.equals(modelId, storedId.toString())) {
                throw new EventSourcingException(
                        "Stored model document '%s' reports @EntityId '%s'"
                                .formatted(modelId, storedId));
            }
        }
        return ImmutableEntity.<T>builder()
                .id(modelId)
                .type(modelType)
                .idProperty(idProperty)
                .value(value)
                .build();
    }
}
