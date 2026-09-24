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

import io.fluxzero.common.caching.NoOpCache;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRepositoryNamespaceTest {
    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void reusesRepositoryOnlyWhenBothNamespaceResourcesAreIdentical(boolean sameClient, boolean sameDocuments) {
        Client client = mock(Client.class);
        Client selectedClient = sameClient ? client : mock(Client.class);
        DocumentStore documents = mock(DocumentStore.class);
        DocumentStore selectedDocuments = sameDocuments ? documents : mock(DocumentStore.class);
        for (Client resource : List.of(client, selectedClient)) {
            when(resource.forNamespace(null)).thenReturn(client);
            when(resource.getEventStoreClient()).thenReturn(mock(EventStoreClient.class));
        }
        when(client.forNamespace("selected")).thenReturn(selectedClient);
        when(documents.forNamespace("selected")).thenReturn(selectedDocuments);
        var repository = new DefaultModelRepository(client, documents, new JacksonSerializer(),
                mock(EntityHelper.class), null, NoOpCache.INSTANCE, List.of(), "");

        ModelRepository selected = repository.forNamespace("selected");

        if (sameClient && sameDocuments) {
            assertSame(repository, selected);
        } else {
            assertNotSame(repository, selected);
        }
        assertSame(selected, repository.forNamespace("selected"));
    }
}
