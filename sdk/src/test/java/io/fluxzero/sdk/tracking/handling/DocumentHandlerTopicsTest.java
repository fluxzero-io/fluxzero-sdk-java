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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.search.Searchable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DocumentHandlerTopicsTest {
    @Test
    void modelAndGraphContextDoNotReplaceTheOrdinaryPayload() {
        for (String name : new String[]{"modelContext", "graphContext", "explicitClass", "explicitCollection"}) {
            var method = Arrays.stream(Handlers.class.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                    .findFirst().orElseThrow();
            var annotation = method.getAnnotation(HandleDocument.class);
            assertEquals("inspection", DocumentHandlerTopics.resolve(annotation, method), name);
            assertEquals(Void.class, DocumentHandlerTopics.graphType(annotation, method), name);
            assertEquals(Void.class, DocumentHandlerTopics.modelSourceType(annotation, method), name);
        }
    }

    @Test
    void onlyBareGraphPayloadSelectsGraphUpdatesAndMetadataDoesNotAffectInference() {
        var method = Arrays.stream(Handlers.class.getDeclaredMethods()).filter(m -> m.getName().equals("graph"))
                .findFirst().orElseThrow();
        assertEquals(Account.class, DocumentHandlerTopics.graphType(method.getAnnotation(HandleDocument.class), method));
    }

    @Test
    void internalStateIsExplicitAndDoesNotImplySearchability() {
        var method = Arrays.stream(Handlers.class.getDeclaredMethods()).filter(m -> m.getName().equals("state"))
                .findFirst().orElseThrow();
        assertNotNull(DocumentHandlerTopics.resolve(method.getAnnotation(HandleDocument.class), method));
        var invalid = Arrays.stream(Handlers.class.getDeclaredMethods()).filter(m -> m.getName().equals("notSearchable"))
                .findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> DocumentHandlerTopics.resolve(invalid.getAnnotation(HandleDocument.class), invalid));
    }

    static class Handlers {
        @HandleDocument void modelContext(Inspection payload, Account account) { }
        @HandleDocument void graphContext(Inspection payload, Graph<Account> account) { }
        @HandleDocument(documentClass = Inspection.class) void explicitClass(Graph<Account> account, Inspection payload) { }
        @HandleDocument("inspection") void explicitCollection(Graph<Account> account, Inspection payload) { }
        @HandleDocument void graph(Metadata metadata, Graph<Account> account) { }
        @HandleDocument(source = DocumentSource.MODEL_STATE) void state(Internal internal) { }
        @HandleDocument void notSearchable(Internal internal) { }
    }

    @Searchable(collection = "inspection") record Inspection(String accountId) { }
    @Model(searchable = true) record Account(@EntityId String id) { }
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT) record Internal(@EntityId String id) { }
}
