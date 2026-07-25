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

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.tracking.handling.Association;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;

import static io.fluxzero.sdk.modeling.ModelMetadata.HandlerKind.APPLY;
import static io.fluxzero.sdk.modeling.ModelMetadata.HandlerKind.ASSERT_LEGAL;
import static io.fluxzero.sdk.modeling.ModelMetadata.HandlerKind.INTERCEPT_APPLY;
import static io.fluxzero.sdk.modeling.ModelMetadata.RootKind.AGGREGATE;
import static io.fluxzero.sdk.modeling.ModelMetadata.RootKind.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelMetadataTest {

    @Test
    void isOwnedByCentralTypeMetadata() {
        ModelMetadata first = ModelMetadata.of(ChildModel.class);
        ModelMetadata second = ModelMetadata.of(ChildModel.class);
        ModelMetadata fromTypeMetadata = ReflectionUtils.getTypeMetadata(ChildModel.class)
                .specializedMetadata(ModelMetadata.class, ignored -> {
                    throw new AssertionError("metadata should already be cached");
                });

        assertSame(first, second);
        assertSame(first, fromTypeMetadata);
    }

    @Test
    void capturesEntityIdAndParentTypesAndPaths() {
        ModelMetadata metadata = ModelMetadata.validate(ChildModel.class);
        ChildModel value = new ChildModel(
                new ChildModelId("child"), new ParentModelId("parent"), "external-parent");

        assertTrue(metadata.isModel());
        assertEquals("childId", metadata.entityId().orElseThrow().name());
        assertEquals("child-child", metadata.entityId().orElseThrow().read(value).toString());
        assertEquals(List.of("parentId", "externalParentId"),
                     metadata.parentReferences().stream().map(reference -> reference.property().name()).toList());
        assertEquals(List.of("items", "externalItems"),
                     metadata.parentReferences().stream().map(ModelMetadata.ParentReference::path).toList());
        assertEquals(List.of(ParentModel.class, ParentModel.class),
                     metadata.parentReferences().stream().map(ModelMetadata.ParentReference::parentModelType).toList());
        assertEquals("parent-parent", metadata.parentReferences().getFirst().read(value).toString());
        assertEquals("external-parent", metadata.parentReferences().getLast().read(value));
        assertTrue(metadata.parentReferences().stream().allMatch(ModelMetadata.ParentReference::automaticallyComposed));
    }

    @Test
    void exposesAggregateNeutralRootConfiguration() {
        ModelMetadata.RootConfiguration model = ModelMetadata.of(ConfiguredModel.class)
                .rootConfiguration().orElseThrow();
        ModelMetadata.RootConfiguration aggregate = ModelMetadata.of(ConfiguredAggregate.class)
                .rootConfiguration().orElseThrow();

        assertEquals(MODEL, model.kind());
        assertFalse(model.eventSourced());
        assertEquals("models", model.collection());
        assertEquals(AGGREGATE, aggregate.kind());
        assertFalse(aggregate.eventSourced());
        assertEquals("aggregates", aggregate.collection());
    }

    @Test
    void legacyAggregateRootIsAlsoAModelRoot() {
        assertTrue(ModelRoot.class.isAssignableFrom(AggregateRoot.class));
        assertEquals(Set.of("parent", "lastEventId", "lastEventIndex", "withEventIndex", "sequenceNumber",
                            "withSequenceNumber", "timestamp", "previous"),
                     stream(AggregateRoot.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)
                             .collect(toSet()));
    }

    @Test
    void rootConfigurationCannotSilentlyDriftFromAnnotations() {
        Set<String> modelSettings = stream(Model.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(toSet());
        Set<String> aggregateSettings = stream(Aggregate.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(toSet());
        Set<String> configurationSettings = stream(ModelMetadata.RootConfiguration.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .filter(name -> !"kind".equals(name))
                .collect(toSet());

        assertEquals(modelSettings, aggregateSettings);
        assertEquals(modelSettings, configurationSettings);
    }

    @Test
    void resolvesParentMetadataDeclaredOnAnInterfaceProperty() {
        ModelMetadata.ParentReference parent =
                ModelMetadata.validate(InterfaceChildModel.class).parentReferences().getFirst();

        assertEquals("parentId", parent.property().name());
        assertEquals("interfaceChildren", parent.path());
        assertSame(ParentModel.class, parent.parentModelType());
    }

    @Test
    void capturesApplyTargetsAndDependenciesForAllModelHandlerKinds() {
        ModelMetadata metadata = ModelMetadata.of(ModelHandlers.class);

        assertEquals(List.of(APPLY, ASSERT_LEGAL, INTERCEPT_APPLY),
                     metadata.handlerMethods().stream().map(ModelMetadata.HandlerMethod::kind).toList());
        ModelMetadata.HandlerMethod apply = metadata.applyMethods().getFirst();
        assertEquals(List.of(ParentModel.class), apply.targetModelTypes());
        assertEquals(1, apply.modelParameters().size());
        assertSame(ParentModel.class, apply.modelParameters().getFirst().modelType());
        assertTrue(apply.modelParameters().getFirst().entityWrapped());
        assertEquals("parentId", apply.modelParameters().getFirst().associationProperty());
        assertNull(apply.receiverModelType());
    }

    @Test
    void capturesModelConstructorAsApplyTarget() {
        ModelMetadata.HandlerMethod constructor = ModelMetadata.of(ConstructorModel.class).applyMethods().getFirst();

        assertInstanceOf(java.lang.reflect.Constructor.class, constructor.executable());
        assertEquals(List.of(ConstructorModel.class), constructor.targetModelTypes());
        assertNull(constructor.receiverModelType());
    }

    @Test
    void capturesModelReceiverDependencies() {
        List<ModelMetadata.HandlerMethod> handlers = ModelMetadata.of(ReceiverModel.class).handlerMethods();

        assertEquals(3, handlers.size());
        assertTrue(handlers.stream().allMatch(handler -> handler.receiverModelType() == ReceiverModel.class));
    }

    @Test
    void doesNotTreatNestedHelperTypeAsModel() {
        assertFalse(ModelMetadata.of(OuterModel.NestedHelper.class).isModel());
    }

    @Test
    void acceptsUniqueQualifiersForMultipleDependenciesOfSameType() {
        ModelMetadata.HandlerMethod apply = ModelMetadata.of(QualifiedTransfer.class).applyMethods().getFirst();

        assertEquals(List.of("sourceId", "destinationId"),
                     apply.modelParameters().stream()
                             .map(ModelMetadata.ModelParameter::associationProperty)
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
        assertMessage(UntypedPathModel.class, "requires a typed Id<T> or an explicit parent model type");
        assertMessage(InvalidTypedParentModel.class, "which is not annotated with @Model");
        assertMessage(InvalidExplicitParentModel.class, "which is not annotated with @Model");
        assertMessage(MismatchedParentModel.class, "explicitly refers to", "but its ID type refers to");
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
                IllegalStateException.class, () -> ModelMetadata.validate(CycleA.class));

        assertTrue(exception.getMessage().contains("Model parent cycle detected"));
        assertTrue(exception.getMessage().contains(CycleA.class.getName()));
        assertTrue(exception.getMessage().contains(CycleB.class.getName()));
    }

    @Test
    void leavesUntypedParentCycleDetectionToCommitTime() {
        assertNull(ModelMetadata.validate(UntypedParentModel.class)
                           .parentReferences().getFirst().parentModelType());
    }

    private static void assertMessage(Class<?> type, String... fragments) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> ModelMetadata.validate(type));
        for (String fragment : fragments) {
            assertTrue(exception.getMessage().contains(fragment),
                       () -> "Expected '%s' in '%s'".formatted(fragment, exception.getMessage()));
        }
    }

    @Model
    private record ParentModel(@EntityId ParentModelId parentId) {
    }

    @Model(eventSourced = false, searchable = true, collection = "models")
    private record ConfiguredModel(@EntityId String id) {
    }

    @Aggregate(eventSourced = false, searchable = true, collection = "aggregates")
    private record ConfiguredAggregate(@EntityId String id) {
    }

    private static class ParentModelId extends Id<ParentModel> {
        ParentModelId(String id) {
            super(id, "parent-");
        }
    }

    @Model
    private record ChildModel(
            @EntityId ChildModelId childId,
            @ParentId(path = "items") ParentModelId parentId,
            @ParentId(value = ParentModel.class, path = "externalItems") String externalParentId) {
    }

    private static class ChildModelId extends Id<ChildModel> {
        ChildModelId(String id) {
            super(id, "child-");
        }
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
