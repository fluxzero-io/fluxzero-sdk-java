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

package io.fluxzero.common.search;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.SortableEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.fluxzero.common.search.Document.EntryType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGraphDocumentStitcherTest {

    @Test
    void composesCompleteGraphWithoutImplicitLimits() {
        ModelGraphComposition composition =
                ModelGraphComposition.builder().build();

        assertEquals(ModelGraphComposition.UNBOUNDED, composition.getMaxDepth());
        assertEquals(ModelGraphComposition.UNBOUNDED, composition.getMaxModels());
        assertEquals(ModelGraphComposition.UNBOUNDED, composition.getMaxPlacements());
        assertEquals(ModelGraphComposition.UNBOUNDED, composition.getMaxCollections());
        assertEquals(ModelGraphComposition.UNBOUNDED, composition.getMaxBytes());
    }

    @Test
    void stitchesChildrenAndGrandchildrenInStableModelIdOrder() {
        SerializedDocument root =
                document("root", "roots", "name",
                         "root");
        SerializedDocument childB =
                document("child-b", "children",
                         "name", "B");
        SerializedDocument childA =
                document("child-a", "children",
                         "name", "A");
        SerializedDocument grandchild =
                document("grandchild", "details",
                         "value", "detail");
        Map<String, SerializedDocument> documents =
                Map.of(
                        "root", root,
                        "child-a", childA,
                        "child-b", childB,
                        "grandchild", grandchild);

        SerializedDocument stitched =
                ModelGraphDocumentStitcher.stitch(
                                List.of(root),
                                List.of(
                                        edge(
                                                "child-b",
                                                "root",
                                                "children"),
                                        edge(
                                                "child-a",
                                                "root",
                                                "children"),
                                        edge(
                                                "grandchild",
                                                "child-a",
                                                "details")),
                                documents,
                                ModelGraphComposition
                                        .builder()
                                        .build())
                        .getFirst();

        Document result =
                stitched.deserializeDocument();
        assertEquals(
                "A",
                result.getEntryAtPath(
                                "children/0/name")
                        .orElseThrow().getValue());
        assertEquals(
                "B",
                result.getEntryAtPath(
                                "children/1/name")
                        .orElseThrow().getValue());
        assertEquals(
                "detail",
                result.getEntryAtPath(
                                "children/0/details/0/value")
                        .orElseThrow().getValue());
        assertTrue(result.getFacets().contains(
                new FacetEntry(
                        "children/0/category",
                        "value")));
        assertTrue(result.getSortables().contains(
                new SortableEntry(
                        "children/0/rank",
                        "sortable")));
        assertEquals(
                "root A detail B",
                result.getSummary());
    }

    @Test
    void preservesExactTypedPlacementsInHiddenManifest() {
        SerializedDocument root = typedDocument(
                "root", "roots", "example.Root",
                "name", "root");
        SerializedDocument child = typedDocument(
                "child", "children", "example.Child",
                "name", "child");
        SerializedDocument shared = typedDocument(
                "shared", "details", "example.Detail",
                "name", "detail");

        SerializedDocument stitched =
                ModelGraphDocumentStitcher.stitch(
                                List.of(root),
                                List.of(
                                        edge("child", "root", "children"),
                                        edge("shared", "root", "details"),
                                        edge("shared", "child", "details")),
                                Map.of("root", root, "child", child,
                                       "shared", shared),
                                ModelGraphComposition.builder().build(),
                                42L)
                        .getFirst();

        ModelGraphDocumentManifest manifest =
                ModelGraphDocumentManifest.from(stitched)
                        .orElseThrow();
        assertEquals(ModelGraphDocumentManifest.CURRENT_VERSION,
                     manifest.version());
        assertEquals(42L, manifest.stateIndex());
        assertEquals(List.of("example.Root", "example.Child",
                             "example.Detail"),
                     manifest.types());
        assertEquals(List.of("children", "details"),
                     manifest.relationshipPaths());
        assertEquals(List.of(
                new ModelGraphDocumentManifest.Node(
                        "root", 0, -1, -1, 0),
                new ModelGraphDocumentManifest.Node(
                        "child", 1, 0, 0, 0),
                new ModelGraphDocumentManifest.Node(
                        "shared", 2, 1, 1, 0),
                new ModelGraphDocumentManifest.Node(
                        "shared", 2, 0, 1, 0)),
                     manifest.nodes());
        assertTrue(stitched.deserializeDocument()
                           .getMatchingEntries(path ->
                                   path.getValue().startsWith(
                                           "$metadata/"
                                           + ModelGraphDocumentManifest.METADATA_KEY))
                           .findAny().isPresent());
    }

    @Test
    void manifestUsesSuppliedRootWhenItIsAbsentFromDocumentMap() {
        SerializedDocument root = typedDocument(
                "root", "roots", "example.Root",
                "name", "root");
        SerializedDocument child = typedDocument(
                "child", "children", "example.Child",
                "name", "child");

        SerializedDocument stitched = ModelGraphDocumentStitcher.stitch(
                        List.of(root),
                        List.of(edge("child", "root", "children")),
                        Map.of("child", child),
                        ModelGraphComposition.builder().build(),
                        12L)
                .getFirst();

        ModelGraphDocumentManifest manifest =
                ModelGraphDocumentManifest.from(stitched).orElseThrow();
        assertEquals(List.of("example.Root", "example.Child"),
                     manifest.types());
        assertEquals(List.of("root", "child"),
                     manifest.nodes().stream()
                             .map(ModelGraphDocumentManifest.Node::id)
                             .toList());
    }

    @Test
    void appliesProjectionPathOverridesWithoutChangingChildListSemantics() {
        List<ModelGraphEdge> edges =
                ModelGraphDocumentStitcher
                        .applyPathOverrides(
                                List.of(
                                        edge(
                                                "child-b",
                                                "root",
                                                "children"),
                                        edge(
                                                "child-a",
                                                "root",
                                                "children")),
                                List.of(
                                        new ModelGraphPathOverride(
                                                "children",
                                                "projected/items")));

        Document result =
                ModelGraphDocumentStitcher.stitch(
                                List.of(document(
                                        "root", "roots",
                                        "name", "root")),
                                edges,
                                Map.of(
                                        "root", document(
                                                "root", "roots",
                                                "name", "root"),
                                        "child-a", document(
                                                "child-a", "firstType",
                                                "name", "A"),
                                        "child-b", document(
                                                "child-b", "secondType",
                                                "name", "B")),
                                ModelGraphComposition.builder()
                                        .build())
                        .getFirst()
                        .deserializeDocument();

        assertEquals(
                "A",
                result.getEntryAtPath(
                                "projected/items/0/name")
                        .orElseThrow().getValue());
        assertEquals(
                "B",
                result.getEntryAtPath(
                                "projected/items/1/name")
                        .orElseThrow().getValue());
    }

    @Test
    void explicitDepthLimitFailsInsteadOfReturningAPartialGraph() {
        SerializedDocument root = document("root", "roots", "name", "root");
        SerializedDocument child = document("child", "children", "name", "child");
        SerializedDocument grandchild = document("grandchild", "grandchildren", "name", "grandchild");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ModelGraphDocumentStitcher.stitch(
                        List.of(root),
                        List.of(edge("child", "root", "children"),
                                edge("grandchild", "child", "grandchildren")),
                        Map.of("root", root, "child", child, "grandchild", grandchild),
                        ModelGraphComposition.builder().maxDepth(1).build()));

        assertTrue(failure.getMessage().contains("maxDepth 1"));
    }

    @Test
    void rejectsDifferentCanonicalPathsProjectedToOnePath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelGraphDocumentStitcher
                        .applyPathOverrides(
                                List.of(),
                                List.of(
                                        new ModelGraphPathOverride(
                                                "children",
                                                "items"),
                                        new ModelGraphPathOverride(
                                                "details",
                                                "items"))));
    }

    @Test
    void omitsMissingDocumentsAndChildMetadata() {
        SerializedDocument root =
                document("root", "roots", "name",
                         "root");
        SerializedDocument child =
                new SerializedDocument(
                        document(
                                "child", "children",
                                "name", "child")
                                .deserializeDocument()
                                .toBuilder()
                                .entries(
                                        new LinkedHashMap<>(
                                                Map.of(
                                                        new Document.Entry(
                                                                TEXT,
                                                                "secret"),
                                                        List.of(
                                                                new Document.Path(
                                                                        "$metadata/token")))))
                                .build());

        Document stitched =
                ModelGraphDocumentStitcher.stitch(
                                List.of(root),
                                List.of(
                                        edge(
                                                "missing",
                                                "root",
                                                "children"),
                                        edge(
                                                "child",
                                                "root",
                                                "children")),
                                Map.of(
                                        "root", root,
                                        "child", child),
                                ModelGraphComposition
                                        .builder()
                                        .build())
                        .getFirst()
                        .deserializeDocument();

        assertFalse(stitched.getEntries().values()
                            .stream()
                            .flatMap(List::stream)
                            .anyMatch(path ->
                                              path.getValue()
                                                      .contains(
                                                              "$metadata/token")));
    }

    @Test
    void rejectsDirectAndNestedCompositionPathCollisions() {
        SerializedDocument directCollision =
                document("root", "roots",
                         "children/name", "root");
        SerializedDocument child =
                document("child", "children",
                         "name", "child");

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelGraphDocumentStitcher
                        .stitch(
                                List.of(
                                        directCollision),
                                List.of(edge(
                                        "child", "root",
                                        "children")),
                                Map.of(
                                        "root",
                                        directCollision,
                                        "child", child),
                                ModelGraphComposition
                                        .builder()
                                        .build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelGraphDocumentStitcher
                        .stitch(
                                List.of(document(
                                        "root", "roots",
                                        "name", "root")),
                                List.of(
                                        edge(
                                                "child",
                                                "root", "items"),
                                        edge(
                                                "other",
                                                "root",
                                                "items/details")),
                                Map.of(
                                        "root", document(
                                                "root",
                                                "roots",
                                                "name",
                                                "root"),
                                        "child", child,
                                        "other", document(
                                                "other",
                                                "children",
                                                "name",
                                                "other")),
                                ModelGraphComposition
                                        .builder()
                                        .build()));
    }

    @Test
    void boundsSharedDagPlacements() {
        SerializedDocument firstRoot =
                document("root-a", "roots",
                         "name", "A");
        SerializedDocument secondRoot =
                document("root-b", "roots",
                         "name", "B");
        SerializedDocument shared =
                document("shared", "children",
                         "name", "shared");

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelGraphDocumentStitcher
                        .stitch(
                                List.of(
                                        firstRoot,
                                        secondRoot),
                                List.of(
                                        edge(
                                                "shared",
                                                "root-a",
                                                "children"),
                                        edge(
                                                "shared",
                                                "root-b",
                                                "children")),
                                Map.of(
                                        "root-a",
                                        firstRoot,
                                        "root-b",
                                        secondRoot,
                                        "shared", shared),
                                ModelGraphComposition
                                        .builder()
                                        .maxPlacements(1)
                                .build()));
    }

    @Test
    void rejectsOversizedSourceBeforeDeserialization() {
        SerializedDocument oversized =
                new SerializedDocument(
                        "root", 1L, null, "roots",
                        new Data<>(
                                new byte[1_024],
                                "invalid", 0,
                                "application/octet-stream"),
                        null, Set.of(), Set.of());

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelGraphDocumentStitcher
                                .stitch(
                                        List.of(
                                                oversized),
                                        List.of(),
                                        Map.of(
                                                "root",
                                                oversized),
                                        ModelGraphComposition
                                                .builder()
                                                .maxBytes(100L)
                                                .build()));

        assertTrue(failure.getMessage()
                           .contains("maxBytes 100"));
    }

    @Test
    void boundsSourceAndComposedOutputAsOneAllocationBudget() {
        SerializedDocument root =
                document(
                        "root", "roots",
                        "name", "root");
        long outputBytes =
                ModelGraphDocumentStitcher.stitch(
                                List.of(root),
                                List.of(),
                                Map.of("root", root),
                                ModelGraphComposition.builder()
                                        .build())
                        .getFirst().bytes();
        long combined =
                root.bytes() + outputBytes;

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelGraphDocumentStitcher
                                .stitch(
                                        List.of(root),
                                        List.of(),
                                        Map.of(
                                                "root", root),
                                        ModelGraphComposition
                                                .builder()
                                                .maxBytes(
                                                        combined
                                                        - 1L)
                                                .build()));

        assertTrue(failure.getMessage()
                           .contains("maxBytes "
                                     + (combined - 1L)));
    }

    private static SerializedDocument document(
            String id,
            String collection,
            String path,
            String value) {
        Document document = Document.builder()
                .id(id)
                .type("type")
                .revision(0)
                .collection(collection)
                .timestamp(
                        Instant.ofEpochMilli(1L))
                .entries(Map.of(
                        new Document.Entry(
                                TEXT, value),
                        List.of(new Document.Path(
                                path))))
                .summary(() -> value)
                .facets(Set.of(
                        new FacetEntry(
                                "category", "value")))
                .sortables(Set.of(
                        new SortableEntry(
                                "rank", "sortable")))
                .build();
        return new SerializedDocument(document);
    }

    private static SerializedDocument typedDocument(
            String id,
            String collection,
            String type,
            String path,
            String value) {
        return new SerializedDocument(
                document(id, collection, path, value)
                        .deserializeDocument().toBuilder()
                        .type(type).build());
    }

    private static ModelGraphEdge edge(
            String child,
            String parent,
            String path) {
        return new ModelGraphEdge(
                child, parent, "type",
                path, 1L, null);
    }
}
