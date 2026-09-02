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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static io.fluxzero.sdk.modeling.ModelCommitPolicy.ASYNC_AFTER_BATCH;
import static io.fluxzero.sdk.modeling.EventPublication.IF_MODIFIED;
import static io.fluxzero.sdk.modeling.EventPublicationStrategy.STORE_ONLY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    @Test
    void exposesIndependentStorageDefaults() {
        Model model = DefaultModel.class.getAnnotation(Model.class);

        assertArrayEquals(
                new ModelPersistence[]{ModelPersistence.EVENT_SOURCED},
                model.persistence());
        assertFalse(model.ignoreUnknownEvents());
        assertEquals(0, model.snapshotPeriod());
        assertEquals(1, model.maxSnapshotCount());
        assertTrue(model.cached());
        assertEquals(1, model.cachingDepth());
        assertEquals(100, model.checkpointPeriod());
        assertEquals(ModelCommitPolicy.DEFAULT, model.commitPolicy());
        assertEquals(IF_MODIFIED, model.eventPublication());
        assertEquals(EventPublicationStrategy.DEFAULT, model.publicationStrategy());
        assertEquals(ModelConflictPolicy.DEFAULT, model.conflictPolicy());
        assertEquals(AutomaticModelHandling.DEFAULT, model.automaticHandling());
        assertFalse(model.materializeGraph());
        assertTrue(model.document().searchable());
        assertEquals("", model.document().collection());
        assertEquals("", model.document().timestampPath());
        assertEquals("", model.document().endPath());
    }

    @Test
    void liveModelGraphsAreUnboundedByDefault() {
        assertEquals(-1, Graph.Options.DEFAULT.maxDepth());
        assertEquals(-1, Graph.Options.DEFAULT.maxModels());
        assertDoesNotThrow(() -> new Graph.Options(
                Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                     () -> new Graph.Options(-2, 1));
        assertThrows(IllegalArgumentException.class,
                     () -> new Graph.Options(1, 0));
    }

    @Test
    void exposesAggregateEquivalentConfiguration() {
        Model model = ConfiguredModel.class.getAnnotation(Model.class);

        assertArrayEquals(
                new ModelPersistence[]{
                        ModelPersistence.EVENT_SOURCED,
                        ModelPersistence.DOCUMENT},
                model.persistence());
        assertTrue(model.ignoreUnknownEvents());
        assertEquals(20, model.snapshotPeriod());
        assertEquals(3, model.maxSnapshotCount());
        assertFalse(model.cached());
        assertEquals(5, model.cachingDepth());
        assertEquals(8, model.checkpointPeriod());
        assertEquals(ASYNC_AFTER_BATCH, model.commitPolicy());
        assertEquals(IF_MODIFIED, model.eventPublication());
        assertEquals(STORE_ONLY, model.publicationStrategy());
        assertEquals(ModelConflictPolicy.FAIL, model.conflictPolicy());
        assertEquals(AutomaticModelHandling.DISABLED, model.automaticHandling());
        assertEquals("configured-models", model.document().collection());
        assertEquals("createdAt", model.document().timestampPath());
        assertEquals("expiresAt", model.document().endPath());
    }

    @Test
    void addsIndependentCommitAndGraphSettingsToAggregateEquivalentSettings() {
        Set<String> modelSettings = Arrays.stream(Model.class.getDeclaredMethods())
                .map(method -> method.getName()).collect(Collectors.toSet());
        Set<String> aggregateSettings = Arrays.stream(Aggregate.class.getDeclaredMethods())
                .map(method -> method.getName()).collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "automaticHandling",
                        "conflictPolicy",
                        "document",
                        "graphProjection",
                        "materializeGraph",
                        "persistence"),
                modelSettings.stream()
                        .filter(setting ->
                                        !aggregateSettings
                                                .contains(setting))
                        .collect(
                                Collectors.toSet()));
        assertEquals(Set.of("collection", "timestampPath", "endPath", "eventRouting", "eventSourced", "searchable"),
                     aggregateSettings.stream()
                             .filter(setting -> !modelSettings.contains(setting))
                             .collect(Collectors.toSet()));
        assertFalse(modelSettings.contains("name"));
    }

    @Test
    void isInheritedAndUsesCentralReflectionMetadata() {
        Model annotation = ReflectionUtils.getTypeMetadata(InheritedModel.class).typeAnnotation(Model.class);

        assertNotNull(annotation);
        assertArrayEquals(
                new ModelPersistence[]{
                        ModelPersistence.EVENT_SOURCED,
                        ModelPersistence.DOCUMENT},
                annotation.persistence());
        assertEquals("base-models", annotation.document().collection());
    }

    @Test
    void resolvesDocumentProjectionConfiguration() {
        SearchParameters searchable = ClientUtils.getSearchParameters(ConfiguredModel.class);

        assertTrue(searchable.isSearchable());
        assertEquals("configured-models", searchable.getCollection());
        assertEquals("createdAt", searchable.getTimestampPath());
        assertEquals("expiresAt", searchable.getEndPath());
    }

    @Test
    void keepsReferenceOnlyDocumentsOutOfThePublicModelCollection() {
        EntityMetadata metadata = EntityMetadata.validate(
                ReferenceOnlyDocumentModel.class);
        SearchParameters search = ClientUtils.getSearchParameters(
                ReferenceOnlyDocumentModel.class);

        assertTrue(metadata.rootConfiguration().orElseThrow().directDocument());
        assertFalse(metadata.rootConfiguration().orElseThrow().publicDocument());
        assertEquals(
                io.fluxzero.common.api.modeling.ModelDocumentMutation
                        .referenceModelDocumentCollection(
                                ReferenceOnlyDocumentModel.class.getName()),
                metadata.modelDocumentCollection().orElseThrow());
        assertFalse(search.isSearchable());
    }

    @Test
    void rejectsEmptyAndDuplicatePersistenceRepresentations() {
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(EmptyPersistenceModel.class));
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(DuplicatePersistenceModel.class));
    }

    @Test
    void rejectsDocumentProjectionSettingsWithoutMatchingPersistence() {
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(InvalidReferenceOnlyProjection.class));
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(PrivateDocumentWithPublicCollection.class));
    }

    @Test
    void documentConfigurationRequiresDirectDocumentPersistence() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> EntityMetadata.validate(InvalidDocumentConfiguration.class));

        assertTrue(exception.getMessage().contains("requires persistence that stores a direct document"));
    }

    @Test
    void documentPersistenceRejectsEventSourcingOptions() {
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(DocumentWithUnknownEventPolicy.class));
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(DocumentWithSnapshots.class));
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(DocumentWithSnapshotRetention.class));
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(DocumentWithReplayCheckpoints.class));
    }

    @Test
    void rejectsVoidApplyDeclaredByModelDuringHandlerDiscovery() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> DefaultEntityHelper.validateModelApplyMethods(MutableModel.class));

        assertTrue(exception.getMessage().contains("void is not supported for @Model targets"));
        assertTrue(exception.getMessage().contains("Return the resulting model, or return null to delete it"));
    }

    @Test
    void rejectsVoidApplyWithDirectModelParameterDuringHandlerDiscovery() {
        assertThrows(IllegalStateException.class,
                     () -> DefaultEntityHelper.validateModelApplyMethods(MutableModelUpdate.class));
    }

    @Test
    void rejectsVoidApplyWithEntityWrappedModelParameterDuringHandlerDiscovery() {
        assertThrows(IllegalStateException.class,
                     () -> DefaultEntityHelper.validateModelApplyMethods(WrappedMutableModelUpdate.class));
    }

    @Test
    void preservesLegacyMutableAggregateApply() {
        assertDoesNotThrow(() -> DefaultEntityHelper.validateModelApplyMethods(LegacyMutableAggregate.class));
        assertDoesNotThrow(() -> DefaultEntityHelper.validateModelApplyMethods(LegacyMutableUpdate.class));
    }

    @Test
    void acceptsModelApplyReturningStateOrNull() {
        assertDoesNotThrow(() -> DefaultEntityHelper.validateModelApplyMethods(ImmutableModel.class));
        assertDoesNotThrow(() -> DefaultEntityHelper.validateModelApplyMethods(ImmutableModelUpdate.class));
    }

    @Model
    private static class DefaultModel {
    }

    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT},
            ignoreUnknownEvents = true,
            snapshotPeriod = 20,
            maxSnapshotCount = 3,
            cached = false,
            cachingDepth = 5,
            checkpointPeriod = 8,
            commitPolicy = ASYNC_AFTER_BATCH,
            eventPublication = IF_MODIFIED,
            publicationStrategy = STORE_ONLY,
            conflictPolicy = ModelConflictPolicy.FAIL,
            automaticHandling = AutomaticModelHandling.DISABLED,
            document = @DocumentProjection(
                    collection = "configured-models",
                    timestampPath = "createdAt",
                    endPath = "expiresAt"))
    private static class ConfiguredModel {
    }

    @Model(
            persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT},
            document = @DocumentProjection(collection = "base-models"))
    private static class BaseModel {
    }

    @Model(document = @DocumentProjection(collection = "inactive-models"))
    private static class InvalidDocumentConfiguration {
    }

    @Model(
            persistence = ModelPersistence.DOCUMENT,
            document = @DocumentProjection(searchable = false))
    private record ReferenceOnlyDocumentModel(@EntityId String id) {
    }

    @Model(persistence = {})
    private static class EmptyPersistenceModel {
    }

    @Model(persistence = {
            ModelPersistence.EVENT_SOURCED,
            ModelPersistence.EVENT_SOURCED})
    private static class DuplicatePersistenceModel {
    }

    @Model(document = @DocumentProjection(searchable = false))
    private static class InvalidReferenceOnlyProjection {
    }

    @Model(
            persistence = ModelPersistence.DOCUMENT,
            document = @DocumentProjection(
                    searchable = false,
                    collection = "public-models"))
    private static class PrivateDocumentWithPublicCollection {
    }

    @Model(persistence = ModelPersistence.DOCUMENT, ignoreUnknownEvents = true)
    private static class DocumentWithUnknownEventPolicy {
    }

    @Model(persistence = ModelPersistence.DOCUMENT, snapshotPeriod = 1)
    private static class DocumentWithSnapshots {
    }

    @Model(persistence = ModelPersistence.DOCUMENT, maxSnapshotCount = 2)
    private static class DocumentWithSnapshotRetention {
    }

    @Model(persistence = ModelPersistence.DOCUMENT, checkpointPeriod = 10)
    private static class DocumentWithReplayCheckpoints {
    }

    private static class InheritedModel extends BaseModel {
    }

    @Model
    private static class MutableModel {
        @EntityId
        String id;

        @Apply
        void apply(Object update) {
        }
    }

    private static class MutableModelUpdate {
        @Apply
        void apply(MutableModel model) {
        }
    }

    private static class WrappedMutableModelUpdate {
        @Apply
        void apply(Entity<MutableModel> model) {
        }
    }

    @Aggregate
    private static class LegacyMutableAggregate {
        @Apply
        void apply(Object update) {
        }
    }

    private static class LegacyMutableUpdate {
        @Apply
        void apply(LegacyMutableAggregate aggregate) {
        }
    }

    @Model
    private record ImmutableModel(@EntityId String id) {
        @Apply
        ImmutableModel apply(Object update) {
            return this;
        }
    }

    private static class ImmutableModelUpdate {
        @Apply
        ImmutableModel apply(ImmutableModel model) {
            return model;
        }
    }
}
