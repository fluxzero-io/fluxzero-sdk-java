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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultModelRepositoryTest {

    private final Client client = mock(Client.class);
    private final DocumentStore documentStore = mock(DocumentStore.class);
    private final DefaultModelRepository repository = new DefaultModelRepository(client, documentStore);

    @Test
    void loadsDocumentBasedModelFromItsDirectSearchCollection() {
        ProductId id = new ProductId("1");
        Product product = new Product(id, "first");
        when(documentStore.fetchDocument(id.toString(), "products", Product.class))
                .thenReturn(Optional.of(product));

        var result = repository.load(id);

        assertEquals(id.toString(), result.id());
        assertEquals(Product.class, result.type());
        assertEquals(product, result.get());
        assertEquals("productId", result.idProperty());
        verify(documentStore).fetchDocument(id.toString(), "products", Product.class);
    }

    @Test
    void missingDirectDocumentReturnsTypedEmptyEntity() {
        ProductId id = new ProductId("missing");
        when(documentStore.fetchDocument(id.toString(), "products", Product.class))
                .thenReturn(Optional.empty());

        var result = repository.load(id);

        assertEquals(id.toString(), result.id());
        assertEquals(Product.class, result.type());
        assertFalse(result.isPresent());
    }

    @Test
    void rejectsDocumentWhoseEntityIdDoesNotMatchStorageKey() {
        when(documentStore.fetchDocument("product-1", "products", Product.class))
                .thenReturn(Optional.of(new Product(new ProductId("other"), "wrong")));

        EventSourcingException exception = assertThrows(
                EventSourcingException.class,
                () -> repository.load("product-1", Product.class));

        assertEquals(
                "Stored model document 'product-1' reports @EntityId 'product-other'",
                exception.getMessage());
    }

    @Test
    void doesNotPretendDirectDocumentsCanEventSourceAModel() {
        EventSourcingException exception = assertThrows(
                EventSourcingException.class,
                () -> repository.load("account-1", Account.class));

        assertEquals(
                "Event-sourced model reconstruction is not yet integrated with batched model-stream reads",
                exception.getMessage());
    }

    @Test
    void untypedIdWaitsForModelHeadLookupProtocol() {
        EventSourcingException exception = assertThrows(
                EventSourcingException.class,
                () -> repository.load("product-1", Object.class));

        assertEquals(
                "Loading an independent model by untyped ID requires model-head lookup support",
                exception.getMessage());
    }

    @Test
    void standardFluxzeroConfigurationLoadsFromItsDirectDocumentStore() {
        ProductId id = new ProductId("configured");
        Product product = new Product(id, "configured");
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null))) {
            fluxzero.documentStore().index(product, id, "products").join();

            var result = fluxzero.modelRepository().load(id);

            assertEquals(product, result.get());
            assertEquals(id.toString(), result.id());
        }
    }

    @Model(eventSourced = false, searchable = true, collection = "products")
    private record Product(@EntityId ProductId productId, String name) {
    }

    private static class ProductId extends Id<Product> {
        ProductId(String id) {
            super(id, "product-");
        }
    }

    @Model
    private record Account(@EntityId String accountId) {
    }
}
