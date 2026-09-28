/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelSearchConfigurationTest {
    @Test
    void repositoryAndSearchKeepTheirOwningApplicationCollections() {
        try (var first = application("first"); var second = application("second")) {
            first.apply(fc -> fc.executeModelCommit(new Message(new Create("id", "first"))).join());
            second.apply(fc -> fc.executeModelCommit(new Message(new Create("id", "second"))).join());
            assertEquals("first", first.modelRepository().loadCurrentState("id", Stored.class).value().value());
            second.apply(fc -> {
                assertEquals("first", first.modelRepository().loadCurrentState("id", Stored.class).value().value());
                assertEquals(List.of(new Stored("id", "first")), first.documentStore().search(Stored.class).fetchAll());
                assertEquals("first", first.documentStore().searchGraph(Stored.class, false).fetchAll().getFirst().get().value());
                assertEquals("first", first.documentStore().searchGraph(Stored.class, true).fetchAll().getFirst().get().value());
                return null;
            });
        }
    }

    private Fluxzero application(String collection) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of("nodeCollection", collection)))
                .build(LocalClient.newInstance(null));
    }

    @org.junit.jupiter.api.Test
    void projectionCatalogIncludesConcreteAncestorsAndStoredAbstractContracts() {
        var roots = EntityMetadata.graphProjectionRoots(CatalogChild.class,
                java.util.List.of(CatalogParent.class, ConcreteCatalogParent.class, CatalogAncestor.class,
                        ConcreteCatalogAncestor.class, CatalogChild.class));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(CatalogParent.class, ConcreteCatalogAncestor.class),
                roots.stream().map(EntityMetadata.GraphProjectionRoot::modelType).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void excludedParentStopsSearchInheritanceButKeepsDomainComposition() {
        var excluded = EntityMetadata.of(PrivateBranch.class);
        org.junit.jupiter.api.Assertions.assertFalse(excluded.isSearchable());
        org.junit.jupiter.api.Assertions.assertFalse(EntityMetadata.of(PrivateLeaf.class).isSearchable());
        org.junit.jupiter.api.Assertions.assertTrue(excluded.participatesInGraphComposition());
        org.junit.jupiter.api.Assertions.assertTrue(excluded.modelDocumentCollection().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(EntityMetadata.graphProjectionRoots(PrivateLeaf.class).isEmpty());
        var catalog = EntityMetadata.of(CatalogParent.class).graphSearchConfiguration(
                List.of(CatalogParent.class, PrivateBranch.class, PrivateLeaf.class), "").orElseThrow();
        assertEquals(1, catalog.getModelRevisions().size());
    }

    @Test
    void duplicatePathsKeepCascadeAndLetSearchExclusionWin() {
        var relations = EntityMetadata.of(DuplicateBranch.class).parentRelationships(
                "child", new DuplicateBranch("child", "parent", "parent"));
        assertEquals(1, relations.size());
        org.junit.jupiter.api.Assertions.assertTrue(relations.getFirst().searchExcluded());
        org.junit.jupiter.api.Assertions.assertTrue(relations.getFirst().deleteOnParentDeletion());
    }

    @Model(searchable = false)
    record PrivateBranch(@EntityId String id,
            @Parent(value = CatalogParent.class, pathInParent = "private", searchable = false) String parent) { }
    @Model(searchable = false)
    record PrivateLeaf(@EntityId String id,
            @Parent(value = PrivateBranch.class, pathInParent = "leaves") String parent) { }
    @Model(searchable = true)
    record DuplicateBranch(@EntityId String id,
            @Parent(value = CatalogParent.class, pathInParent = "children", deleteOnParentDeletion = false) String first,
            @Parent(value = CatalogParent.class, pathInParent = "children", searchable = false) String second) { }

    @Model(searchable = true, graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    interface CatalogParent { @EntityId String id(); }
    @Model(searchable = true, searchSettings = @SearchSettings(includeDescendants = false),
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    record ConcreteCatalogParent(@EntityId String id,
            @Parent(value = CatalogAncestor.class, pathInParent = "parents") String ancestor) implements CatalogParent { }
    @Model(searchable = true)
    interface CatalogAncestor { @EntityId String id(); }
    @Model(searchable = true, graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    record ConcreteCatalogAncestor(@EntityId String id) implements CatalogAncestor { }
    @Model(searchable = false)
    record CatalogChild(@EntityId String id, @Parent(value = CatalogParent.class, pathInParent = "children") String parent) { }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT,
            searchSettings = @SearchSettings(collection = "${nodeCollection}"),
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT, collection = "${nodeCollection}-graphs"))
    record Stored(@EntityId String id, String value) {}
    record Create(String id, String value) {
        @Apply Stored apply() { return new Stored(id, value); }
    }
}
