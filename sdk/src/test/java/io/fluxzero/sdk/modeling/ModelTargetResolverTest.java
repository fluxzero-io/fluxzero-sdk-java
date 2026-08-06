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

import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.tracking.handling.Association;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.sdk.modeling.ModelTargetResolver.Access.READ_ONLY;
import static io.fluxzero.sdk.modeling.ModelTargetResolver.Access.READ_WRITE;
import static io.fluxzero.sdk.modeling.ModelTargetResolver.Access.WRITE_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTargetResolverTest {

    @Test
    void resolvesModelReceiverByEntityIdNameAndMarksItReadWrite() {
        ModelTargetResolver.TargetPlan plan = ModelTargetResolver.plan(
                RenameProduct.class, ModelMetadata.of(Product.class).handlerMethods());

        ModelTargetResolver.Resolution resolution =
                plan.resolve(new RenameProduct(new ProductId("1"), "new name"));

        assertEquals(List.of(new ModelTargetResolver.ResolvedModel(
                             "product-1", Product.class, READ_WRITE, List.of("productId"))),
                     resolution.models());
        assertTrue(resolution.deferredWrites().isEmpty());
    }

    @Test
    void resolvesUniqueTypedIdWhenPropertyNameDiffers() {
        ModelTargetResolver.Resolution resolution = ModelTargetResolver.plan(
                        RenameProductByTarget.class, ModelMetadata.of(Product.class).handlerMethods())
                .resolve(new RenameProductByTarget(new ProductId("1")));

        assertEquals("product-1", resolution.models().getFirst().modelId());
        assertEquals(List.of("target"), resolution.models().getFirst().sourceProperties());
    }

    @Test
    void appliesEntityIdAffixOutsideTypedIdPrefix() {
        ModelTargetResolver.Resolution resolution = ModelTargetResolver.plan(
                        RenameAffixed.class, ModelMetadata.of(Affixed.class).handlerMethods())
                .resolve(new RenameAffixed(new AffixedId("1")));

        assertEquals("move-affixed-1", resolution.models().getFirst().modelId());
    }

    @Test
    void resolvesGetterOnlyPayloadProperties() {
        ModelTargetResolver.Resolution resolution = ModelTargetResolver.plan(
                        GetterOnlyRename.class, ModelMetadata.of(Product.class).handlerMethods())
                .resolve(new GetterOnlyRename("getter"));

        assertEquals("product-getter", resolution.models().getFirst().modelId());
    }

    @Test
    void canonicalEntityIdNameWinsOverOtherIdsOfSameType() {
        ModelTargetResolver.Resolution resolution = ModelTargetResolver.plan(
                        MergeProduct.class, ModelMetadata.of(Product.class).handlerMethods())
                .resolve(new MergeProduct(new ProductId("target"), new ProductId("other")));

        assertEquals("product-target", resolution.models().getFirst().modelId());
        assertEquals(List.of("productId"), resolution.models().getFirst().sourceProperties());
    }

    @Test
    void associationOnModelParameterOverridesAutomaticPropertyMatching() {
        ModelTargetResolver.Resolution resolution = ModelTargetResolver.plan(
                        CheckOrder.class, ModelMetadata.of(CheckOrder.class).handlerMethods())
                .resolve(new CheckOrder(new OrderId("ignored"), new OrderId("selected")));

        assertEquals(List.of(new ModelTargetResolver.ResolvedModel(
                             "order-selected", Order.class, READ_ONLY, List.of("selectedOrder"))),
                     resolution.models());
    }

    @Test
    void resolvesAndDeduplicatesAllDirectCrossModelDependencies() {
        ReserveInventory command = new ReserveInventory(new OrderId("1"), new InventoryId("2"));

        ModelTargetResolver.Resolution resolution = ModelTargetResolver.resolve(
                new Message(command), ModelMetadata.of(ReserveInventory.class).handlerMethods());

        assertEquals(List.of("order-1", "inventory-2"),
                     resolution.models().stream().map(ModelTargetResolver.ResolvedModel::modelId).toList());
        assertEquals(READ_WRITE, resolution.models().getFirst().access());
        assertEquals(READ_ONLY, resolution.models().getLast().access());
    }

    @Test
    void resolvesWriteOnlyCreationTarget() {
        CreateProduct command = new CreateProduct(new ProductId("new"));

        ModelTargetResolver.Resolution resolution = ModelTargetResolver.resolve(
                command, ModelMetadata.of(CreateProduct.class).handlerMethods());

        assertEquals(List.of(new ModelTargetResolver.ResolvedModel(
                             "product-new", Product.class, WRITE_ONLY, List.of("productId"))),
                     resolution.models());
    }

    @Test
    void resolvesAssertionReceiverAsReadOnly() {
        ModelTargetResolver.Resolution resolution = ModelTargetResolver.resolve(
                new CheckGuardedProduct(new GuardedProductId("1")),
                ModelMetadata.of(GuardedProduct.class).handlerMethods());

        assertEquals(READ_ONLY, resolution.models().getFirst().access());
    }

    @Test
    void neverRequiresParentIdToTargetChildModel() {
        ModelTargetResolver.Resolution resolution = ModelTargetResolver.resolve(
                new RenameChild(new ChildId("1")),
                ModelMetadata.of(Child.class).handlerMethods());

        assertEquals(List.of("child-1"),
                     resolution.models().stream().map(ModelTargetResolver.ResolvedModel::modelId).toList());
    }

    @Test
    void classifiesMissingDirectModelParameterAsAncestorDependency() {
        ModelTargetResolver.Resolution resolution =
                ModelTargetResolver.resolve(
                        new CheckChild(new ChildId("1")),
                        ModelMetadata.of(
                                CheckChild.class).handlerMethods());

        assertEquals(
                List.of("child-1"),
                resolution.models().stream()
                        .map(ModelTargetResolver.ResolvedModel::modelId)
                        .toList());
        assertEquals(
                List.of(new ModelTargetResolver.AncestorDependency(
                        Parent.class, null,
                        ModelMetadata.of(CheckChild.class)
                                .handlerMethods().getFirst()
                                .executable().toGenericString())),
                resolution.ancestorDependencies());
    }

    @Test
    void missingAssociationPropertyQualifiesAncestorPath() {
        ModelTargetResolver.Resolution resolution =
                ModelTargetResolver.resolve(
                        new CheckQualifiedChild(new ChildId("1")),
                        ModelMetadata.of(
                                CheckQualifiedChild.class)
                                .handlerMethods());

        assertEquals(
                "parents",
                resolution.ancestorDependencies()
                        .getFirst().association());
    }

    @Test
    void defersWriteSelectionWhenReturnTypeHasMultipleQualifiedCandidates() {
        Transfer command = new Transfer(new AccountId("source"), new AccountId("destination"));

        ModelTargetResolver.Resolution resolution = ModelTargetResolver.resolve(
                command, ModelMetadata.of(Transfer.class).handlerMethods());

        assertEquals(List.of("account-source", "account-destination"),
                     resolution.models().stream().map(ModelTargetResolver.ResolvedModel::modelId).toList());
        assertTrue(resolution.models().stream().allMatch(model -> model.access() == READ_ONLY));
        assertEquals(List.of("account-source", "account-destination"),
                     resolution.deferredWrites().getFirst().candidateModelIds());
    }

    @Test
    void sameResolvedCandidateIsLoadedOnceAndBecomesWriteTarget() {
        AccountId same = new AccountId("same");

        ModelTargetResolver.Resolution resolution = ModelTargetResolver.resolve(
                new Transfer(same, same), ModelMetadata.of(Transfer.class).handlerMethods());

        assertEquals(1, resolution.models().size());
        assertEquals(READ_WRITE, resolution.models().getFirst().access());
        assertEquals(List.of("sourceId", "destinationId"), resolution.models().getFirst().sourceProperties());
        assertTrue(resolution.deferredWrites().isEmpty());
    }

    @Test
    void rejectsAmbiguousTypedIdsWithoutQualifier() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ModelTargetResolver.plan(
                        AmbiguousRename.class, ModelMetadata.of(Product.class).handlerMethods()));

        assertTrue(exception.getMessage().contains("ambiguous"));
        assertTrue(exception.getMessage().contains("source"));
        assertTrue(exception.getMessage().contains("destination"));
        assertTrue(exception.getMessage().contains("@Association"));
    }

    @Test
    void rejectsMissingIdsAndInvalidExplicitPropertiesDuringPlanning() {
        assertMessage(MissingProductId.class, Product.class, "no property named 'productId'");
        assertMessage(CollectionCheckOrder.class, CollectionCheckOrder.class, "must contain one direct model ID");
    }

    @Test
    void rejectsNullIdsAndPayloadTypeMismatchDuringResolution() {
        ModelTargetResolver.TargetPlan plan = ModelTargetResolver.plan(
                RenameProduct.class, ModelMetadata.of(Product.class).handlerMethods());

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> plan.resolve(new RenameProduct(null, "name"))).getMessage().contains("resolved to null"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> plan.resolve(new MissingProductId("name"))).getMessage().contains("Expected payload"));
    }

    @Test
    void rejectsOneGlobalIdBeingClaimedByIncompatibleModelTypes() {
        SameStringId command = new SameStringId("same", "same");
        ModelTargetResolver.TargetPlan plan = ModelTargetResolver.plan(
                SameStringId.class, ModelMetadata.of(SameStringId.class).handlerMethods());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> plan.resolve(command));

        assertTrue(exception.getMessage().contains("incompatible types"));
        assertTrue(exception.getMessage().contains("same"));
    }

    private static void assertMessage(Class<?> payloadType, Class<?> handlerType, String expected) {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ModelTargetResolver.plan(payloadType, ModelMetadata.of(handlerType).handlerMethods()));
        assertTrue(exception.getMessage().contains(expected),
                   () -> "Expected '%s' in '%s'".formatted(expected, exception.getMessage()));
    }

    @Model
    private record Product(@EntityId ProductId productId, String name) {
        @Apply
        Product rename(Object command) {
            return this;
        }
    }

    private static class ProductId extends Id<Product> {
        ProductId(String id) {
            super(id, "product-");
        }
    }

    private record RenameProduct(ProductId productId, String name) {
    }

    private record RenameProductByTarget(ProductId target) {
    }

    @Model
    private record Affixed(@EntityId(prefix = "move-") AffixedId affixedId) {
        @Apply
        Affixed rename(RenameAffixed command) {
            return this;
        }
    }

    private static class AffixedId extends Id<Affixed> {
        AffixedId(String id) {
            super(id, "affixed-");
        }
    }

    private record RenameAffixed(AffixedId affixedId) {
    }

    private static class GetterOnlyRename {
        private final String rawId;

        private GetterOnlyRename(String rawId) {
            this.rawId = rawId;
        }

        ProductId productId() {
            return new ProductId(rawId);
        }
    }

    private record MergeProduct(ProductId productId, ProductId otherProductId) {
    }

    private record AmbiguousRename(ProductId source, ProductId destination) {
    }

    private record MissingProductId(String name) {
    }

    private record CreateProduct(ProductId productId) {
        @Apply
        Product create() {
            return new Product(productId, "new");
        }
    }

    @Model
    private record GuardedProduct(@EntityId GuardedProductId guardedProductId) {
        @AssertLegal
        void check(CheckGuardedProduct command) {
        }
    }

    private static class GuardedProductId extends Id<GuardedProduct> {
        GuardedProductId(String id) {
            super(id, "guarded-");
        }
    }

    private record CheckGuardedProduct(GuardedProductId guardedProductId) {
    }

    @Model
    private record Order(@EntityId OrderId orderId) {
    }

    private static class OrderId extends Id<Order> {
        OrderId(String id) {
            super(id, "order-");
        }
    }

    private record CheckOrder(OrderId orderId, OrderId selectedOrder) {
        @AssertLegal
        void check(@Association("selectedOrder") Order order) {
        }
    }

    private record BrokenCheckOrder(OrderId orderId) {
        @AssertLegal
        void check(@Association("missing") Order order) {
        }
    }

    private record CollectionCheckOrder(List<OrderId> orders) {
        @AssertLegal
        void check(@Association("orders") Order order) {
        }
    }

    @Model
    private record Inventory(@EntityId InventoryId inventoryId) {
    }

    private static class InventoryId extends Id<Inventory> {
        InventoryId(String id) {
            super(id, "inventory-");
        }
    }

    private record ReserveInventory(OrderId orderId, InventoryId inventoryId) {
        @Apply
        Order reserve(Order order, Inventory inventory) {
            return order;
        }
    }

    @Model
    private record Account(@EntityId AccountId accountId) {
    }

    private static class AccountId extends Id<Account> {
        AccountId(String id) {
            super(id, "account-");
        }
    }

    private record Transfer(AccountId sourceId, AccountId destinationId) {
        @Apply
        Account transfer(
                @Association("sourceId") Account source,
                @Association("destinationId") Account destination) {
            return destination;
        }
    }

    @Model
    private record Other(@EntityId(prefix = "product-") String id) {
    }

    private record SameStringId(String product, String other) {
        @AssertLegal
        void check(@Association("product") Product product, @Association("other") Other other) {
        }
    }

    @Model
    private record Parent(@EntityId ParentIdValue parentId) {
    }

    private static class ParentIdValue extends Id<Parent> {
        ParentIdValue(String id) {
            super(id, "parent-");
        }
    }

    @Model
    private record Child(@EntityId ChildId childId, @ParentId ParentIdValue parentId) {
        @Apply
        Child rename(RenameChild command) {
            return this;
        }
    }

    private static class ChildId extends Id<Child> {
        ChildId(String id) {
            super(id, "child-");
        }
    }

    private record RenameChild(ChildId childId) {
    }

    private record CheckChild(ChildId childId) {
        @AssertLegal
        void check(Child child, Parent parent) {
        }
    }

    private record CheckQualifiedChild(ChildId childId) {
        @AssertLegal
        void check(
                Child child,
                @Association("parents") Parent parent) {
        }
    }
}
