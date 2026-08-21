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

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.web.ApiDoc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;

import static io.fluxzero.sdk.modeling.EntityMetadata.HandlerKind.APPLY;
import static io.fluxzero.sdk.modeling.EntityMetadata.HandlerKind.ASSERT_LEGAL;
import static io.fluxzero.sdk.modeling.EntityMetadata.HandlerKind.INTERCEPT_APPLY;
import static io.fluxzero.sdk.modeling.EntityMetadata.RootKind.AGGREGATE;
import static io.fluxzero.sdk.modeling.EntityMetadata.RootKind.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityMetadataTest {

    @Test
    void isOwnedByCentralTypeMetadata() {
        EntityMetadata first = EntityMetadata.of(ChildModel.class);
        EntityMetadata second = EntityMetadata.of(ChildModel.class);
        EntityMetadata fromTypeMetadata = ReflectionUtils.getTypeMetadata(ChildModel.class)
                .specializedMetadata(EntityMetadata.class, ignored -> {
                    throw new AssertionError("metadata should already be cached");
                });

        assertSame(first, second);
        assertSame(first, fromTypeMetadata);
    }

    @Test
    void capturesEntityIdAndParentTypesAndPaths() {
        EntityMetadata metadata = EntityMetadata.validate(ChildModel.class);
        ChildModel value = new ChildModel(
                new ChildModelId("child"), new ParentModelId("parent"), "external-parent");

        assertTrue(metadata.isModel());
        assertEquals("childId", metadata.entityId().orElseThrow().name());
        assertEquals("child-child", metadata.entityId().orElseThrow().read(value).toString());
        assertEquals(List.of("parentId", "externalParentId"),
                     metadata.parentReferences().stream().map(reference -> reference.property().name()).toList());
        assertEquals(List.of("items", "externalItems"),
                     metadata.parentReferences().stream().map(EntityMetadata.ParentReference::path).toList());
        assertEquals(List.of(ParentModel.class, ParentModel.class),
                     metadata.parentReferences().stream().map(EntityMetadata.ParentReference::parentModelType).toList());
        assertEquals("parent-parent", metadata.parentReferences().getFirst().read(value).toString());
        assertEquals("external-parent", metadata.parentReferences().getLast().read(value));
        assertTrue(metadata.parentReferences().stream().allMatch(EntityMetadata.ParentReference::automaticallyComposed));
        assertEquals("Child items", metadata.parentReferences().getFirst().apiDoc().description());
        assertEquals("", metadata.parentReferences().getLast().apiDoc().description());
    }

    @Test
    void composesEntityIdAffixesOutsideTypedIdRepositoryPrefix() {
        EntityMetadata metadata = EntityMetadata.validate(AffixedModel.class);
        AffixedModelId id = new AffixedModelId("123");

        assertEquals("move-model-123-state", metadata.repositoryId(id));
        assertEquals("move-model-123-state", metadata.repositoryId("123"));
        assertEquals("move-model-123-state", metadata.repositoryIdOf(new AffixedModel(id)));
        assertEquals("123", id.getFunctionalId());
        assertEquals("model-123", id.toString());
    }

    @Test
    void parentScopedIdentitySelectsTheDeepestNonNullParentFromAPayload() {
        EntityMetadata metadata = EntityMetadata.validate(ScopedLeafModel.class);
        ScopedTargetPayload payload = new ScopedTargetPayload("root", "branch", "leaf");

        assertEquals(
                metadata.repositoryId("leaf", "branch", ScopedBranchModel.class),
                metadata.repositoryId("leaf", payload));
    }

    @Test
    void parentScopedIdentityRequiresOneUnambiguousParent() {
        assertThrows(IllegalStateException.class,
                     () -> EntityMetadata.validate(ParentlessScopedModel.class));

        EntityMetadata metadata = EntityMetadata.validate(AmbiguousScopedModel.class);
        assertThrows(IllegalArgumentException.class,
                     () -> metadata.repositoryIdOf(
                             new AmbiguousScopedModel("leaf", "first", "second")));
    }

    @Test
    void exposesAggregateNeutralRootConfiguration() {
        EntityMetadata.RootConfiguration model = EntityMetadata.of(ConfiguredModel.class)
                .rootConfiguration().orElseThrow();
        EntityMetadata.RootConfiguration aggregate = EntityMetadata.of(ConfiguredAggregate.class)
                .rootConfiguration().orElseThrow();

        assertEquals(MODEL, model.kind());
        assertFalse(model.eventSourced());
        assertEquals("models", model.collection());
        assertEquals(AGGREGATE, aggregate.kind());
        assertFalse(aggregate.eventSourced());
        assertEquals("aggregates", aggregate.collection());
    }

    @Test
    void validatesAndExposesMaterializedGraphProjection() {
        var configuration =
                EntityMetadata.validate(ProjectedModel.class)
                        .graphProjectionConfiguration()
                        .orElseThrow();

        assertEquals(
                "projected-models",
                configuration.getRootCollection());
        assertEquals(
                "projected-graphs",
                configuration.getCollection());
        assertEquals(
                io.fluxzero.common.api.search.ModelGraphComposition.UNBOUNDED,
                configuration.getComposition()
                        .getMaxDepth());
        assertEquals(
                "items",
                configuration.getPathOverrides()
                        .getFirst()
                        .getProjectionPath());
        assertThrows(
                IllegalStateException.class,
                () -> EntityMetadata.validate(
                        UnsearchableProjectedModel.class));
        assertEquals(
                "default-projected-models-graphs",
                EntityMetadata.validate(DefaultProjectedModel.class)
                        .graphProjectionConfiguration()
                        .orElseThrow().getCollection());
        assertTrue(EntityMetadata.validate(ParentModel.class)
                           .graphProjectionConfiguration().isEmpty());
        assertTrue(EntityMetadata.validate(ConfiguredUnmaterializedModel.class)
                           .graphProjectionConfiguration().isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> EntityMetadata.validate(ConflictingProjectionCollection.class)
                        .graphProjectionConfiguration());
    }

    @Test
    void keepsApplicationResolvedGraphConfigurationOutOfTheClassCache() {
        assertEquals(
                List.of("first-models", "first-graphs"),
                projectionCollections("first"));
        assertEquals(
                List.of("second-models", "second-graphs"),
                projectionCollections("second"));
    }

    private static List<String> projectionCollections(String prefix) {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        "graphRootCollection", prefix + "-models",
                        "graphProjectionCollection", prefix + "-graphs"))
                        .andThen(existing))
                .build(LocalClient.newInstance())) {
            return fluxzero.apply(ignored -> {
                EntityMetadata.graphProjectionRoots(ConfiguredProjectionCollections.class);
                var configuration = EntityMetadata.validate(ConfiguredProjectionCollections.class)
                        .graphProjectionConfiguration()
                        .orElseThrow();
                return List.of(configuration.getRootCollection(), configuration.getCollection());
            });
        }
    }

    @Test
    void aggregateAndModelRootsShareOnlyTheNeutralPersistedContract() {
        assertTrue(PersistedRoot.class.isAssignableFrom(AggregateRoot.class));
        assertTrue(PersistedRoot.class.isAssignableFrom(ModelRoot.class));
        assertFalse(ModelRoot.class.isAssignableFrom(AggregateRoot.class));
        assertEquals(Set.of("parent", "lastEventId", "lastEventIndex", "withEventIndex", "sequenceNumber",
                            "withSequenceNumber", "timestamp", "previous"),
                     stream(AggregateRoot.class.getDeclaredMethods())
                             .filter(method -> !method.isSynthetic())
                             .map(java.lang.reflect.Method::getName)
                             .collect(toSet()));
    }

    @Test
    void rootConfigurationCannotSilentlyDriftFromAnnotations() {
        Set<String> modelSettings = stream(Model.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(toSet());
        Set<String> aggregateSettings = stream(Aggregate.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(toSet());
        Set<String> configurationSettings = stream(EntityMetadata.RootConfiguration.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .filter(name -> !"kind".equals(name))
                .collect(toSet());

        Set<String> expectedSharedSettings = new java.util.HashSet<>(aggregateSettings);
        expectedSharedSettings.removeAll(Set.of("collection", "timestampPath", "endPath"));
        assertEquals(expectedSharedSettings,
                     modelSettings.stream()
                             .filter(name ->
                                             !Set.of(
                                                     "automaticHandling",
                                                     "conflictPolicy",
                                                     "graphProjection",
                                                     "materializeGraph",
                                                     "searchProjection")
                                                     .contains(name))
                             .collect(toSet()));
        Set<String> expectedConfiguration = new java.util.HashSet<>(modelSettings);
        expectedConfiguration.remove("searchProjection");
        expectedConfiguration.addAll(Set.of("collection", "timestampPath", "endPath"));
        assertEquals(expectedConfiguration, configurationSettings);
    }

    @Test
    void aggregateAndModelRootsUseTheSameSnapshotPolicy() {
        EntityMetadata.SnapshotSettings model = EntityMetadata.of(SnapshotModel.class)
                .rootConfiguration().orElseThrow().snapshotSettings(false);
        EntityMetadata.SnapshotSettings aggregate = EntityMetadata.of(SnapshotAggregate.class)
                .rootConfiguration().orElseThrow().snapshotSettings(false);

        assertEquals(model, aggregate);
        assertEquals(3, model.period());
        assertEquals(2, model.maxCount());
        assertFalse(model.due(1L, 1));
        assertTrue(model.due(2L, 1));
        assertTrue(model.due(3L, 3));
        assertEquals(1, EntityMetadata.of(SnapshotAggregate.class).rootConfiguration().orElseThrow()
                .snapshotSettings(true).period());
    }

    @Test
    void transitionSettingsSeparateWireStrategyFromEffectivePolicy() {
        EntityMetadata.TransitionSettings aggregate = EntityMetadata.of(SnapshotAggregate.class)
                .rootConfiguration().orElseThrow().transitionSettings(null);
        EntityMetadata.TransitionSettings model = EntityMetadata.of(SnapshotModel.class)
                .rootConfiguration().orElseThrow().transitionSettings(null);

        assertEquals(EventPublication.ALWAYS, aggregate.publication());
        assertEquals(EventPublication.IF_MODIFIED, model.publication());
        assertEquals(EventPublicationStrategy.DEFAULT, aggregate.eventStrategy());
        assertEquals(EventPublicationStrategy.DEFAULT, model.eventStrategy());
        assertEquals(EventPublicationStrategy.STORE_AND_PUBLISH, aggregate.strategy());
        assertEquals(EventPublicationStrategy.STORE_AND_PUBLISH, model.strategy());
    }

    @Test
    void resolvesParentMetadataDeclaredOnAnInterfaceProperty() {
        EntityMetadata.ParentReference parent =
                EntityMetadata.validate(InterfaceChildModel.class).parentReferences().getFirst();

        assertEquals("parentId", parent.property().name());
        assertEquals("interfaceChildren", parent.path());
        assertSame(ParentModel.class, parent.parentModelType());
    }

    @Test
    void resolvesPolymorphicParentFromConcreteTypedId() {
        EntityMetadata.ParentReference parent = EntityMetadata.validate(PolymorphicChildModel.class)
                .parentReferences().getFirst();
        ParentModelId parentId = new ParentModelId("parent");
        AlternateParentModelId alternateId = new AlternateParentModelId("alternate");

        assertEquals(List.of(ParentModel.class, AlternateParentModel.class), parent.parentModelTypes());
        assertNull(parent.parentModelType());
        assertSame(ParentModel.class, parent.parentModelType(parentId));
        assertSame(AlternateParentModel.class, parent.parentModelType(alternateId));
        assertEquals("parent-parent", parent.repositoryId(parentId));
        assertEquals("alternate-alternate", parent.repositoryId(alternateId));
    }

    @Test
    void capturesApplyTargetsAndDependenciesForAllModelHandlerKinds() {
        EntityMetadata metadata = EntityMetadata.of(ModelHandlers.class);

        assertEquals(List.of(APPLY, ASSERT_LEGAL, INTERCEPT_APPLY),
                     metadata.handlerMethods().stream().map(EntityMetadata.HandlerMethod::kind).toList());
        EntityMetadata.HandlerMethod apply = metadata.applyMethods().getFirst();
        assertEquals(List.of(ParentModel.class), apply.targetModelTypes());
        assertEquals(1, apply.modelParameters().size());
        assertSame(ParentModel.class, apply.modelParameters().getFirst().modelType());
        assertTrue(apply.modelParameters().getFirst().entityWrapped());
        assertEquals("parentId", apply.modelParameters().getFirst().associationProperty());
        assertNull(apply.receiverModelType());
    }

    @Test
    void capturesTypedAndRuntimeValidatedCollectionApplyResults() {
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(CollectionApplyResults.class)
                        .applyMethods();

        EntityMetadata.HandlerMethod typed = handlers.stream()
                .filter(handler -> handler.executable().getName()
                        .equals("typed"))
                .findFirst().orElseThrow();
        assertEquals(List.of(ParentModel.class), typed.targetModelTypes());
        assertTrue(typed.collectionApplyResult());
        assertFalse(typed.dynamicApplyResult());
        assertTrue(typed.hasApplyResult());

        EntityMetadata.HandlerMethod dynamic = handlers.stream()
                .filter(handler -> handler.executable().getName()
                        .equals("dynamic"))
                .findFirst().orElseThrow();
        assertTrue(dynamic.targetModelTypes().isEmpty());
        assertTrue(dynamic.collectionApplyResult());
        assertTrue(dynamic.dynamicApplyResult());
        assertTrue(dynamic.hasApplyResult());
    }

    @Test
    void capturesModelConstructorAsApplyTarget() {
        EntityMetadata.HandlerMethod constructor = EntityMetadata.of(ConstructorModel.class).applyMethods().getFirst();

        assertInstanceOf(java.lang.reflect.Constructor.class, constructor.executable());
        assertEquals(List.of(ConstructorModel.class), constructor.targetModelTypes());
        assertNull(constructor.receiverModelType());
    }

    @Test
    void resolvesApplyTargetFromGenericHandlerContract() {
        EntityMetadata.HandlerMethod handler = EntityMetadata.of(GenericParentUpdate.class)
                .applyMethods().getFirst();

        assertEquals(List.of(ParentModel.class), handler.targetModelTypes());
        assertEquals(GenericUpdate.class, handler.executable().getDeclaringClass());
    }

    @Test
    void capturesModelReceiverDependencies() {
        List<EntityMetadata.HandlerMethod> handlers = EntityMetadata.of(ReceiverModel.class).handlerMethods();

        assertEquals(3, handlers.size());
        assertTrue(handlers.stream().allMatch(handler -> handler.receiverModelType() == ReceiverModel.class));
    }

    @Test
    void recognizesGraphAsALazyModelDependency() throws Exception {
        var parameter = GraphDependency.class
                .getDeclaredMethod("handle", Graph.class)
                .getParameters()[0];

        EntityMetadata.ModelParameter dependency =
                EntityMetadata.inspectModelParameter(parameter).orElseThrow();

        assertSame(ParentModel.class, dependency.modelType());
        assertFalse(dependency.entityWrapped());
        assertTrue(dependency.graphWrapped());
        assertFalse(dependency.collectionWrapped());
    }

    @Test
    void recognizesAssociatedGraphCollectionAsOneLazyModelDependency() throws Exception {
        var parameter = GraphDependency.class
                .getDeclaredMethod("handleMany", List.class)
                .getParameters()[0];

        EntityMetadata.ModelParameter dependency =
                EntityMetadata.inspectModelParameter(parameter).orElseThrow();

        assertSame(ParentModel.class, dependency.modelType());
        assertTrue(dependency.graphWrapped());
        assertTrue(dependency.collectionWrapped());
        assertEquals("parentIds", dependency.associationProperty());
    }

    @Test
    void doesNotTreatNestedHelperTypeAsModel() {
        assertFalse(EntityMetadata.of(OuterModel.NestedHelper.class).isModel());
    }

    private static class GraphDependency {
        @SuppressWarnings("unused")
        void handle(Graph<ParentModel> graph) {
        }

        @SuppressWarnings("unused")
        void handleMany(@Association("parentIds") List<Graph<ParentModel>> graphs) {
        }
    }

    @Test
    void acceptsUniqueQualifiersForMultipleDependenciesOfSameType() {
        EntityMetadata.HandlerMethod apply = EntityMetadata.of(QualifiedTransfer.class).applyMethods().getFirst();

        assertEquals(List.of("sourceId", "destinationId"),
                     apply.modelParameters().stream()
                             .map(EntityMetadata.ModelParameter::associationProperty)
                             .toList());
    }

    @Test
    void rejectsMissingAndDuplicateEntityIds() {
        assertMessage(MissingIdModel.class, "exactly one @EntityId", "found 0");
        assertMessage(DuplicateIdModel.class, "exactly one @EntityId", "found 2");
    }

    @Test
    void rejectsModelAggregateCombination() {
        assertMessage(ModelAggregate.class, "both @Model and @Aggregate");
    }

    @Test
    void rejectsInvalidParentDeclarations() {
        assertMessage(ParentOnNonModel.class, "@ParentId is only supported on @Model");
        assertMessage(CollectionParentModel.class, "must contain one scalar ID");
        assertMessage(PaddedPathModel.class, "must not be blank or have surrounding whitespace");
        assertMessage(InvalidPathModel.class, "relative path without empty segments");
        assertMessage(TraversalPathModel.class, "must not contain '.' or '..' segments");
        assertMessage(NumericPathModel.class, "must not contain numeric segments");
        assertMessage(MetadataPathModel.class, "reserved document metadata path");
        assertMessage(UntypedPathModel.class, "requires a typed Id<T> or an explicit parent model type");
        assertMessage(InvalidTypedParentModel.class, "which is not annotated with @Model");
        assertMessage(InvalidExplicitParentModel.class, "which is not annotated with @Model");
        assertMessage(MismatchedParentModel.class, "explicitly refers to", "but its ID type refers to");
        assertMessage(ConflictingPolymorphicParentModel.class, "either value or types");
        assertMessage(UntypedPolymorphicParentModel.class, "requires an Id property");
        assertMessage(InvalidPolymorphicParentModel.class, "which is not annotated with @Model");
    }

    @Test
    void rejectsAmbiguousDependenciesWithoutQualifiers() {
        assertMessage(AmbiguousTransfer.class, "multiple", ParentModel.class.getName(), "@Association");
    }

    @Test
    void rejectsInvalidDependencyQualifier() {
        assertMessage(InvalidQualifier.class, "at most one @Association property");
    }

    @Test
    void detectsStaticallyTypedParentCyclesWithPath() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> EntityMetadata.validate(CycleA.class));

        assertTrue(exception.getMessage().contains("Model parent cycle detected"));
        assertTrue(exception.getMessage().contains(CycleA.class.getName()));
        assertTrue(exception.getMessage().contains(CycleB.class.getName()));
    }

    @Test
    void leavesUntypedParentCycleDetectionToCommitTime() {
        assertNull(EntityMetadata.validate(UntypedParentModel.class)
                           .parentReferences().getFirst().parentModelType());
    }

    private static void assertMessage(Class<?> type, String... fragments) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> EntityMetadata.validate(type));
        for (String fragment : fragments) {
            assertTrue(exception.getMessage().contains(fragment),
                       () -> "Expected '%s' in '%s'".formatted(fragment, exception.getMessage()));
        }
    }

    @Model
    private record ParentModel(@EntityId ParentModelId parentId) {
    }

    @Model(eventSourced = false, searchable = true,
            searchProjection = @Searchable(collection = "models"))
    private record ConfiguredModel(@EntityId String id) {
    }

    @Model(
            searchable = true,
            searchProjection = @Searchable(collection = "projected-models"),
            materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "projected-graphs",
                    pathOverrides = @GraphPathOverride(
                            path = "children",
                            projectionPath = "items")))
    private record ProjectedModel(
            @EntityId String id) {
    }

    @Model(
            materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "invalid-graphs"))
    private record UnsearchableProjectedModel(
            @EntityId String id) {
    }

    @Model(
            searchable = true,
            searchProjection = @Searchable(collection = "default-projected-models"),
            materializeGraph = true)
    private record DefaultProjectedModel(
            @EntityId String id) {
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(collection = "ignored-graphs"))
    private record ConfiguredUnmaterializedModel(
            @EntityId String id) {
    }

    @Model(
            searchable = true,
            searchProjection = @Searchable(collection = "same-collection"),
            materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "same-collection"))
    private record ConflictingProjectionCollection(
            @EntityId String id) {
    }

    @Model(
            searchable = true,
            searchProjection = @Searchable(collection = "${graphRootCollection}"),
            materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "${graphProjectionCollection}"))
    private record ConfiguredProjectionCollections(
            @EntityId String id) {
    }

    @Aggregate(eventSourced = false, searchable = true, collection = "aggregates")
    private record ConfiguredAggregate(@EntityId String id) {
    }

    @Model(snapshotPeriod = 3, maxSnapshotCount = 2)
    private record SnapshotModel(@EntityId String id) {
    }

    @Aggregate(snapshotPeriod = 3, maxSnapshotCount = 2)
    private record SnapshotAggregate(@EntityId String id) {
    }

    private static class ParentModelId extends Id<ParentModel> {
        ParentModelId(String id) {
            super(id, "parent-");
        }
    }

    @Model
    private record AlternateParentModel(@EntityId AlternateParentModelId parentId) {
    }

    private static class AlternateParentModelId extends Id<AlternateParentModel> {
        AlternateParentModelId(String id) {
            super(id, "alternate-");
        }
    }

    @Model
    private record PolymorphicChildModel(
            @EntityId String id,
            @ParentId(types = {ParentModel.class, AlternateParentModel.class}, path = "children") Id<?> parentId) {
    }

    @Model
    private record ChildModel(
            @EntityId ChildModelId childId,
            @ParentId(path = "items", apiDoc = @ApiDoc(description = "Child items")) ParentModelId parentId,
            @ParentId(value = ParentModel.class, path = "externalItems") String externalParentId) {
    }

    private static class ChildModelId extends Id<ChildModel> {
        ChildModelId(String id) {
            super(id, "child-");
        }
    }

    @Model
    private record AffixedModel(
            @EntityId(prefix = "move-", postfix = "-state") AffixedModelId id) {
    }

    private static class AffixedModelId extends Id<AffixedModel> {
        AffixedModelId(String id) {
            super(id, "model-");
        }
    }

    @Model
    private record ScopedRootModel(@EntityId String rootId) {
    }

    @Model
    private record ScopedBranchModel(
            @EntityId String branchId,
            @ParentId(value = ScopedRootModel.class, path = "branches") String rootId) {
    }

    @Model
    private record ScopedLeafModel(
            @EntityId(parentScoped = true) String leafId,
            @ParentId(value = ScopedRootModel.class, path = "leaves") String rootId,
            @ParentId(value = ScopedBranchModel.class, path = "leaves") String branchId) {
    }

    private record ScopedTargetPayload(String rootId, String branchId, String leafId) {
    }

    @Model
    private record ParentlessScopedModel(
            @EntityId(parentScoped = true) String id) {
    }

    @Model
    private record OtherScopedRootModel(@EntityId String rootId) {
    }

    @Model
    private record AmbiguousScopedModel(
            @EntityId(parentScoped = true) String leafId,
            @ParentId(value = ScopedRootModel.class, path = "leaves") String firstRootId,
            @ParentId(value = OtherScopedRootModel.class, path = "leaves") String secondRootId) {
    }

    private interface ParentLink {
        @ParentId(path = "interfaceChildren")
        ParentModelId parentId();
    }

    @Model
    private record InterfaceChildModel(
            @EntityId String id, ParentModelId parentId) implements ParentLink {
    }

    private static class ModelHandlers {
        @Apply
        ParentModel apply(@Association("parentId") Entity<ParentModel> parent) {
            return parent.get();
        }

        @AssertLegal
        void assertLegal(ParentModel parent) {
        }

        @InterceptApply
        Object intercept(ParentModel parent) {
            return this;
        }
    }

    private interface GenericUpdate<T> {
        @Apply
        default T apply() {
            return null;
        }
    }

    private record GenericParentUpdate(ParentModelId parentId) implements GenericUpdate<ParentModel> {
    }

    @Model
    private static class ConstructorModel {
        @EntityId
        String id;

        @Apply
        ConstructorModel(CreateConstructorModel command) {
            id = command.id();
        }
    }

    @Model
    private record ReceiverModel(@EntityId String id) {
        @Apply
        ReceiverModel apply(RenameReceiver command) {
            return this;
        }

        @AssertLegal
        void assertLegal(RenameReceiver command) {
        }

        @InterceptApply
        Object intercept(RenameReceiver command) {
            return command;
        }
    }

    private record RenameReceiver(String id) {
    }

    private record CreateConstructorModel(String id) {
    }

    @Model
    private static class OuterModel {
        @EntityId
        String id;

        private static class NestedHelper {
        }
    }

    private static class QualifiedTransfer {
        @Apply
        ParentModel transfer(
                @Association("sourceId") ParentModel source,
                @Association("destinationId") Entity<ParentModel> destination) {
            return source;
        }
    }

    private static class CollectionApplyResults {
        @Apply
        List<ParentModel> typed() {
            return List.of();
        }

        @Apply
        List<Object> dynamic() {
            return List.of();
        }
    }

    @Model
    private static class MissingIdModel {
    }

    @Model
    private record DuplicateIdModel(@EntityId String first, @EntityId String second) {
    }

    @Model
    @Aggregate
    private record ModelAggregate(@EntityId String id) {
    }

    private record ParentOnNonModel(@ParentId String parentId) {
    }

    @Model
    private record CollectionParentModel(@EntityId String id, @ParentId List<String> parentIds) {
    }

    @Model
    private record PaddedPathModel(@EntityId String id,
                                   @ParentId(value = ParentModel.class, path = " items ") String parent) {
    }

    @Model
    private record InvalidPathModel(@EntityId String id,
                                    @ParentId(value = ParentModel.class, path = "items//archived") String parent) {
    }

    @Model
    private record TraversalPathModel(@EntityId String id,
                                      @ParentId(value = ParentModel.class, path = "items/../archived") String parent) {
    }

    @Model
    private record NumericPathModel(
            @EntityId String id,
            @ParentId(value = ParentModel.class, path = "items/0")
            String parent) {
    }

    @Model
    private record MetadataPathModel(
            @EntityId String id,
            @ParentId(value = ParentModel.class, path = "$metadata/items")
            String parent) {
    }

    @Model
    private record UntypedPathModel(@EntityId String id, @ParentId(path = "items") String parent) {
    }

    private static class NotAModel {
    }

    private static class NotAModelId extends Id<NotAModel> {
        NotAModelId(String id) {
            super(id);
        }
    }

    @Model
    private record InvalidTypedParentModel(@EntityId String id, @ParentId NotAModelId parentId) {
    }

    @Model
    private record InvalidExplicitParentModel(@EntityId String id, @ParentId(NotAModel.class) String parentId) {
    }

    @Model
    private record MismatchedParentModel(
            @EntityId String id, @ParentId(value = ChildModel.class) ParentModelId parentId) {
    }

    @Model
    private record ConflictingPolymorphicParentModel(
            @EntityId String id,
            @ParentId(value = ParentModel.class, types = AlternateParentModel.class) Id<?> parentId) {
    }

    @Model
    private record UntypedPolymorphicParentModel(
            @EntityId String id,
            @ParentId(types = {ParentModel.class, AlternateParentModel.class}) String parentId) {
    }

    @Model
    private record InvalidPolymorphicParentModel(
            @EntityId String id,
            @ParentId(types = {ParentModel.class, NotAModel.class}) Id<?> parentId) {
    }

    private static class AmbiguousTransfer {
        @Apply
        ParentModel transfer(ParentModel source, ParentModel destination) {
            return source;
        }
    }

    private static class InvalidQualifier {
        @Apply
        ParentModel apply(@Association({"first", "second"}) ParentModel parent) {
            return parent;
        }
    }

    private static class CycleAId extends Id<CycleA> {
        CycleAId(String id) {
            super(id);
        }
    }

    private static class CycleBId extends Id<CycleB> {
        CycleBId(String id) {
            super(id);
        }
    }

    @Model
    private record CycleA(@EntityId CycleAId id, @ParentId CycleBId parentId) {
    }

    @Model
    private record CycleB(@EntityId CycleBId id, @ParentId CycleAId parentId) {
    }

    @Model
    private record UntypedParentModel(@EntityId String id, @ParentId String parentId) {
    }
}
