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

package io.fluxzero.sdk.persisting.search;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedObject;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MaterializedGraphFactoryTest {

    @Test
    void upcastsRootAndDescendantFromTheirOwnManifestRevisions() {
        JacksonSerializer serializer = new JacksonSerializer(
                List.of(new GraphNodeUpcaster()));
        serializer.registerTypeCaster(
                "legacy.RevisionedRoot", RevisionedRoot.class.getName());
        serializer.registerTypeCaster(
                "legacy.RevisionedChild", RevisionedChild.class.getName());
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "root")
                .put("oldName", "current root");
        json.putArray("children").addObject()
                .put("id", "child")
                .put("rootId", "root")
                .put("oldValue", "current child");
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                41L,
                List.of("legacy.RevisionedRoot",
                        "legacy.RevisionedChild"),
                List.of("children"),
                List.of(
                        new ModelGraphDocumentManifest.Node(
                                "root", 0, 0, -1, -1, 0),
                        new ModelGraphDocumentManifest.Node(
                                "child", 1, 0, 0, 0, 0)));
        SerializedDocument document = serializer.toDocument(
                json, "root", "revisioned-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));

        Graph<RevisionedRoot> graph = MaterializedGraphFactory.create(
                document, RevisionedRoot.class, serializer,
                () -> mock(ModelRepository.class),
                List.of(RevisionedRoot.class, RevisionedChild.class),
                Map.of());

        assertEquals("current root", graph.get().name());
        assertEquals("current child", graph.children(
                "children", RevisionedChild.class).getFirst().get().value());

        Metadata metadata = Metadata.of(
                ModelGraphDocumentManifest.METADATA_KEY,
                manifest.serialize(), "$start", 1000L, "$end", 2000L);
        DeserializingMessage message = new DeserializingMessage(
                new Message(json, metadata, "root", null),
                MessageType.DOCUMENT, "revisioned-graphs", serializer);
        MaterializedGraphDocumentMigration.Migration migration =
                MaterializedGraphDocumentMigration.create(
                        graph, message, serializer).orElseThrow();
        SerializedDocument replacement = migration.replacement();
        ModelGraphDocumentManifest migrated = ModelGraphDocumentManifest.from(
                replacement).orElseThrow();

        assertEquals(manifest.serialize(), migration.expectedManifest());
        assertEquals(41L, migrated.stateIndex());
        assertEquals(
                List.of(RevisionedRoot.class.getName(),
                        RevisionedChild.class.getName()),
                migrated.types());
        assertEquals(List.of(1, 1), migrated.nodes().stream()
                .map(ModelGraphDocumentManifest.Node::revision).toList());
        assertEquals(1000L, replacement.getTimestamp());
        assertEquals(2000L, replacement.getEnd());
        Graph<RevisionedRoot> rewritten = MaterializedGraphFactory.create(
                replacement, RevisionedRoot.class, serializer,
                () -> mock(ModelRepository.class),
                List.of(RevisionedRoot.class, RevisionedChild.class), Map.of());
        assertEquals("current root", rewritten.get().name());
        assertEquals("current child", rewritten.children(
                RevisionedChild.class).getFirst().get().value());

        DeserializingMessage rewrittenMessage = new DeserializingMessage(
                new Message(serializer.getObjectMapper().valueToTree(rewritten),
                            replacement.getMetadata(), "root", null),
                MessageType.DOCUMENT, "revisioned-graphs", serializer);
        assertEquals(Optional.empty(), MaterializedGraphDocumentMigration.create(
                rewritten, rewrittenMessage, serializer));
    }

    @Test
    void materializedGraphTombstonesRemainObservational() {
        JacksonSerializer serializer = new JacksonSerializer();
        Graph<?> graph = mock(Graph.class);
        DeserializingMessage message = new DeserializingMessage(
                new Message(serializer.getObjectMapper().createObjectNode(),
                            Metadata.of(ModelGraphDocumentManifest.TOMBSTONE_METADATA_KEY,
                                        true), "root", null),
                MessageType.DOCUMENT, "revisioned-graphs", serializer);

        assertEquals(Optional.empty(), MaterializedGraphDocumentMigration.create(
                graph, message, serializer));
        verifyNoInteractions(graph);
    }

    @Test
    void materializesEachSelectedNodeAtMostOnce() {
        CountingRoot.constructions.set(0);
        CountingChild.constructions.set(0);
        CountingJacksonSerializer serializer = new CountingJacksonSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "root");
        ArrayNode children = json.putArray("children");
        children.addObject().put("id", "child").put("rootId", "root");
        ModelGraphDocumentManifest manifest =
                new ModelGraphDocumentManifest(
                        41L,
                        List.of(CountingRoot.class.getName(),
                                CountingChild.class.getName()),
                        List.of("children"),
                        List.of(
                                new ModelGraphDocumentManifest.Node(
                                        "root", 0, 0, -1, -1, 0),
                                new ModelGraphDocumentManifest.Node(
                                        "child", 1, 0, 0, 0, 0)));
        SerializedDocument document = serializer.toDocument(
                json, "root", "root-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));

        Graph<CountingRoot> graph = MaterializedGraphFactory.create(
                document, CountingRoot.class, serializer,
                () -> mock(ModelRepository.class),
                List.of(CountingRoot.class, CountingChild.class),
                Map.of());
        Graph<CountingChild> child = graph.children(
                "children", CountingChild.class).getFirst();

        assertEquals(0, CountingRoot.constructions.get());
        assertEquals(0, CountingChild.constructions.get());
        assertEquals("root", graph.get().id());
        assertEquals("root", graph.get().id());
        assertEquals(1, CountingRoot.constructions.get());
        assertEquals(0, CountingChild.constructions.get());
        assertEquals("child", child.get().id());
        assertEquals("child", child.get().id());
        assertEquals(1, CountingChild.constructions.get());
        assertEquals(0, serializer.normalizations.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesParentsOutsideTheMaterializedDocumentLazily() {
        JacksonSerializer serializer = new JacksonSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "child").put("rootId", "root");
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                41L, List.of(CountingChild.class.getName()), List.of(),
                List.of(new ModelGraphDocumentManifest.Node(
                        "child", 0, 0, -1, -1, 0)));
        SerializedDocument document = serializer.toDocument(
                json, "child", "child-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));
        ModelRepository repository = mock(ModelRepository.class);
        Graph<CountingChild> durable = mock(Graph.class);
        Graph<CountingRoot> parent = mock(Graph.class);
        when(parent.type()).thenReturn(CountingRoot.class);
        when(parent.id()).thenReturn("root");
        when(repository.loadGraphAt(
                "child", CountingChild.class, 41L, Graph.Options.DEFAULT))
                .thenReturn(durable);
        when(durable.parent()).thenReturn(Optional.of(parent));
        when(durable.parents()).thenReturn(List.of(parent));
        when(durable.parent(CountingRoot.class)).thenReturn(Optional.of(parent));
        when(durable.ancestor(CountingRoot.class)).thenReturn(Optional.of(parent));

        Graph<CountingChild> graph = MaterializedGraphFactory.create(
                document, CountingChild.class, serializer,
                () -> repository,
                List.of(CountingRoot.class, CountingChild.class), Map.of());

        assertEquals(Optional.of(parent), graph.parent());
        assertEquals(List.of(parent), graph.parents());
        assertEquals(Optional.of(parent), graph.parent(CountingRoot.class));
        assertEquals(Optional.of(parent), graph.ancestor(CountingRoot.class));
    }

    @Test
    void resolvesAlternateParentsInsideTheMaterializedGraphWithoutRepositoryLoads() {
        JacksonSerializer serializer = new JacksonSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "root");
        ObjectNode primary = json.putArray("primaryParents").addObject()
                .put("id", "primary").put("rootId", "root");
        primary.putArray("children").addObject()
                .put("id", "child")
                .put("primaryId", "primary")
                .put("secondaryId", "secondary")
                .put("alternatePrimaryId", "alternate-primary");
        json.withArray("primaryParents").addObject()
                .put("id", "alternate-primary").put("rootId", "root");
        json.putArray("secondaryParents").addObject()
                .put("id", "secondary").put("rootId", "root");
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                41L,
                List.of(MultiParentRoot.class.getName(),
                        PrimaryParent.class.getName(),
                        SecondaryParent.class.getName(),
                        MultiParentChild.class.getName()),
                List.of("primaryParents", "secondaryParents", "children"),
                List.of(
                        new ModelGraphDocumentManifest.Node(
                                "root", 0, 0, -1, -1, 0),
                        new ModelGraphDocumentManifest.Node(
                                "primary", 1, 0, 0, 0, 0),
                        new ModelGraphDocumentManifest.Node(
                                "child", 3, 0, 1, 2, 0),
                        new ModelGraphDocumentManifest.Node(
                                "secondary", 2, 0, 0, 1, 0),
                        new ModelGraphDocumentManifest.Node(
                                "alternate-primary", 1, 0, 0, 0, 1)));
        SerializedDocument document = serializer.toDocument(
                json, "root", "multi-parent-root-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));
        ModelRepository repository = mock(ModelRepository.class);

        Graph<MultiParentRoot> graph = MaterializedGraphFactory.create(
                document, MultiParentRoot.class, serializer,
                () -> repository,
                List.of(MultiParentRoot.class, PrimaryParent.class,
                        SecondaryParent.class, MultiParentChild.class),
                Map.of());
        Graph<MultiParentChild> child = graph.descendants(
                "primaryParents/children", MultiParentChild.class).getFirst();

        assertEquals("primary", child.parent().orElseThrow().id());
        assertEquals(List.of("primary", "secondary", "alternate-primary"),
                     child.parents().stream().map(Graph::id).toList());
        assertEquals("primary",
                     child.parent(PrimaryParent.class).orElseThrow().id());
        assertEquals("secondary",
                     child.parent(SecondaryParent.class).orElseThrow().id());
        assertEquals("secondary",
                     child.ancestor(SecondaryParent.class).orElseThrow().id());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void resolvesPlacedAncestorBeforeUnrelatedExternalParents() {
        JacksonSerializer serializer = new JacksonSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "primary").put("rootId", "root");
        json.putArray("children").addObject()
                .put("id", "child")
                .put("primaryId", "primary")
                .put("secondaryId", "secondary")
                .put("alternatePrimaryId", "external-primary");
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                41L,
                List.of(PrimaryParent.class.getName(),
                        MultiParentChild.class.getName()),
                List.of("children"),
                List.of(
                        new ModelGraphDocumentManifest.Node(
                                "primary", 0, 0, -1, -1, 0),
                        new ModelGraphDocumentManifest.Node(
                                "child", 1, 0, 0, 0, 0)));
        SerializedDocument document = serializer.toDocument(
                json, "primary", "primary-parent-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));
        ModelRepository repository = mock(ModelRepository.class);

        Graph<PrimaryParent> graph = MaterializedGraphFactory.create(
                document, PrimaryParent.class, serializer,
                () -> repository,
                List.of(MultiParentRoot.class, PrimaryParent.class,
                        SecondaryParent.class, MultiParentChild.class),
                Map.of());
        Graph<MultiParentChild> child = graph.children(
                "children", MultiParentChild.class).getFirst();

        assertSame(graph, child.ancestor(PrimaryParent.class).orElseThrow());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retainsTheRepositoryThatCreatedTheMaterializedGraph() {
        JacksonSerializer serializer = new JacksonSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "child").put("rootId", "root");
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                41L, List.of(CountingChild.class.getName()), List.of(),
                List.of(new ModelGraphDocumentManifest.Node(
                        "child", 0, 0, -1, -1, 0)));
        SerializedDocument document = serializer.toDocument(
                json, "child", "child-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));
        ModelRepository original = mock(ModelRepository.class);
        ModelRepository replacement = mock(ModelRepository.class);
        Graph<CountingChild> durable = mock(Graph.class);
        when(original.loadGraphAt(
                "child", CountingChild.class, 41L, Graph.Options.DEFAULT))
                .thenReturn(durable);
        AtomicReference<ModelRepository> repository =
                new AtomicReference<>(original);

        Graph<CountingChild> graph = MaterializedGraphFactory.create(
                document, CountingChild.class, serializer,
                repository::get,
                List.of(CountingRoot.class, CountingChild.class), Map.of());
        repository.set(replacement);

        assertEquals(durable.revisionStateIndex(), graph.revisionStateIndex());
        org.mockito.Mockito.verify(original).loadGraphAt(
                "child", CountingChild.class, 41L, Graph.Options.DEFAULT);
        org.mockito.Mockito.verifyNoInteractions(replacement);
    }

    @Test
    void rootWithoutParentReferencesDoesNotLoadTheRepository() {
        JacksonSerializer serializer = new JacksonSerializer();
        ObjectNode json = serializer.getObjectMapper().createObjectNode()
                .put("id", "root");
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                41L, List.of(CountingRoot.class.getName()), List.of(),
                List.of(new ModelGraphDocumentManifest.Node(
                        "root", 0, 0, -1, -1, 0)));
        SerializedDocument document = serializer.toDocument(
                json, "root", "root-graphs", null, null,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));
        ModelRepository repository = mock(ModelRepository.class);

        Graph<CountingRoot> graph = MaterializedGraphFactory.create(
                document, CountingRoot.class, serializer,
                () -> repository,
                List.of(CountingRoot.class, CountingChild.class), Map.of());

        assertEquals(Optional.empty(), graph.parent());
        assertEquals(List.of(), graph.parents());
        assertEquals(Optional.empty(), graph.parent(CountingChild.class));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void rejectsMismatchedMaterializedGraphHandlerType() throws Exception {
        var method = InvalidHandler.class.getDeclaredMethod(
                "handle", Graph.class);
        var resolver = new MaterializedGraphParameterResolver(
                new JacksonSerializer(),
                () -> mock(ModelRepository.class), List::of);

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        method.getParameters()[0],
                        method.getAnnotation(HandleDocument.class)));
    }

    @Model
    private record CountingRoot(@EntityId String id) {
        private static final AtomicInteger constructions =
                new AtomicInteger();

        private CountingRoot {
            constructions.incrementAndGet();
        }
    }

    @Model
    @Revision(1)
    private record RevisionedRoot(
            @EntityId String id,
            String name) {
    }

    @Model
    @Revision(1)
    private record RevisionedChild(
            @EntityId String id,
            @Parent(value = RevisionedRoot.class, pathInParent = "children")
            String rootId,
            String value) {
    }

    private static final class GraphNodeUpcaster {
        @Upcast(
                type = "legacy.RevisionedRoot",
                revision = 0)
        ObjectNode upcastRoot(ObjectNode value) {
            value.set("name", value.remove("oldName"));
            return value;
        }

        @Upcast(
                type = "legacy.RevisionedChild",
                revision = 0)
        ObjectNode upcastChild(ObjectNode value) {
            value.set("value", value.remove("oldValue"));
            return value;
        }
    }

    private static final class CountingJacksonSerializer
            extends JacksonSerializer {
        private final AtomicInteger normalizations = new AtomicInteger();

        @Override
        public SerializedObject<byte[]> normalize(
                SerializedObject<?> serializedObject) {
            normalizations.incrementAndGet();
            return super.normalize(serializedObject);
        }
    }

    @Model
    private record CountingChild(
            @EntityId String id,
            @Parent(value = CountingRoot.class, pathInParent = "children")
            String rootId) {
        private static final AtomicInteger constructions =
                new AtomicInteger();

        private CountingChild {
            constructions.incrementAndGet();
        }
    }

    @Model
    private record MultiParentRoot(@EntityId String id) {
    }

    @Model
    private record PrimaryParent(
            @EntityId String id,
            @Parent(value = MultiParentRoot.class, pathInParent = "primaryParents")
            String rootId) {
    }

    @Model
    private record SecondaryParent(
            @EntityId String id,
            @Parent(value = MultiParentRoot.class, pathInParent = "secondaryParents")
            String rootId) {
    }

    @Model
    private record MultiParentChild(
            @EntityId String id,
            @Parent(value = PrimaryParent.class, pathInParent = "children")
            String primaryId,
            @Parent(value = SecondaryParent.class)
            String secondaryId,
            @Parent(value = PrimaryParent.class)
            String alternatePrimaryId) {
    }

    private static final class InvalidHandler {
        @HandleDocument(modelGraph = CountingRoot.class)
        void handle(Graph<CountingChild> graph) {
        }
    }
}
