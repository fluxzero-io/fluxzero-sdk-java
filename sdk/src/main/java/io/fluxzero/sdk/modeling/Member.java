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

package io.fluxzero.sdk.modeling;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated field or getter represents a nested entity or collection of entities within an
 * aggregate, independently stored model, or stateful handler.
 * <p>
 * Entities marked with {@code @Member} share the persistence boundary of their owning root. Within an
 * {@link Aggregate @Aggregate} or {@link Model @Model}, they share the root's stream, cache, search document,
 * snapshots, and lifecycle. When an update targets a nested entity, Fluxzero traverses the root structure to locate
 * the correct entity (or entities). In {@code @Stateful} handlers, member objects may also declare
 * {@code @Handle...} methods and their own {@code @Association} properties; updates are written back by storing the
 * parent stateful handler.
 *
 * <p>
 * For a {@link Model}, choose {@code @Member} only when the nested value deliberately has no independent lifecycle:
 * creation, change history, retention, and deletion all belong to the root. State that can live or evolve
 * independently is another {@link Model}, connected through {@link Parent}, even when it is normally rendered as an
 * item in a root collection. A meaningful identity is strong evidence of that independent lifecycle, but the identity
 * may be {@link EntityId#parentScoped() parent-scoped}; lack of a globally unique functional ID is not a reason to
 * embed. Searchability, update frequency, load strategy, and convenient collection placement are persistence or query
 * choices, not lifecycle boundaries.
 *
 * <p>
 * This annotation supports modeling persistence roots with deliberately embedded values, for example:
 * <pre>{@code
 * @Model
 * public class Invoice {
 *     @EntityId
 *     String invoiceId;
 *
 *     @Member
 *     List<InvoiceLine> lines;
 * }
 * }</pre>
 * Here, a line exists only as part of its invoice and deliberately shares the invoice's entire lifecycle. Updates
 * targeting {@code InvoiceLine} values are routed by matching the identifier declared inside that class.
 *
 * <h2>Support for new entities</h2>
 * <p>
 * If no matching entity is found for a given update, Fluxzero will still evaluate the update against applicable
 * {@code @Apply} and {@code @AssertLegal} methods. This allows new entity creation directly from the update payload
 * when appropriate logic is defined.
 * <br>For example:
 * <pre>{@code
 * @Apply
 * InvoiceLine create() {
 *     return new InvoiceLine(lineId, amount);
 * }
 * }</pre>
 * will be used to create a new {@code InvoiceLine} if no matching line exists in the {@code lines} member list.
 *
 * <h2>Immutability and parent updates</h2>
 * <p>
 * Fluxzero assumes immutability by default. When a nested entity is added, removed, or modified, Fluxzero will attempt
 * to create a new version of the parent entity by copying and updating the annotated container field (list, map, etc.).
 * The parent is not modified directly.
 * <br>This behavior ensures safe update propagation and accurate change tracking, especially during event sourcing.
 * <br>For example, if {@code Invoice} has a list of {@code InvoiceLine}s:
 * <pre>{@code
 * @Member
 * List<InvoiceLine> lines;
 * }</pre>
 * and one line is updated, Fluxzero will replace the {@code lines} list with a new list containing the updated value.
 * <p>
 * For record owners, Fluxzero rebuilds the owner through the canonical constructor when the member is a record
 * component. For Kotlin data classes, Fluxzero can use the generated {@code copy(...)} method. A type can still expose
 * an explicit wither such as {@code withLines(...)} or configure {@link #wither()} when custom update behavior is
 * needed.
 * <pre>{@code
 * public record Invoice(@EntityId String id, @Member List<InvoiceLine> lines) {
 *     public Invoice withLines(List<InvoiceLine> lines) {
 *         return new Invoice(id, lines);
 *     }
 * }
 * }</pre>
 *
 * <h2>Optional attributes</h2>
 * <ul>
 *     <li><strong>{@code idProperty}</strong> (default: empty):<br>
 *         Use this to explicitly specify the identifier property name on the nested entity. By default,
 *         Fluxzero locates the identifier via the {@link io.fluxzero.sdk.modeling.EntityId} annotation.</li>
 *
 *     <li><strong>{@code wither}</strong> (default: empty):<br>
 *         Defines a method (by name) that should be invoked to update the container when the entity is added,
 *         removed, or replaced. Normally, Fluxzero will update the container (e.g., list or map) automatically.
 *         This setting is useful for immutable containers or cases requiring side effects during updates.
 *     </li>
 * </ul>
 *
 * <p>
 * Supported container types:
 * <ul>
 *     <li>Single nested entities (e.g., {@code Product product})</li>
 *     <li>Collections of entities (e.g., {@code List<Product>}). Non-null {@link EntityId} values must be unique
 *     within one member collection.</li>
 *     <li>Maps of entities keyed by their identifier. Newly added map members use {@link EntityId} or
 *     {@link #idProperty()} as the map key.</li>
 * </ul>
 *
 * @see io.fluxzero.sdk.modeling.EntityId
 * @see io.fluxzero.sdk.modeling.Aggregate
 * @see io.fluxzero.sdk.modeling.Model
 * @see io.fluxzero.sdk.persisting.eventsourcing.Apply
 * @see io.fluxzero.sdk.modeling.AssertLegal
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Member {

    /**
     * Specifies the name of the identifier property on the nested entity, if different from the default detected one.
     */
    String idProperty() default "";

    /**
     * Optionally defines the name of a method that should be used to apply updates to the container of the nested
     * entity.
     * <p>
     * Normally, Fluxzero automatically updates the container (for lists, maps, or singletons). This attribute is only
     * necessary if a custom update method must be invoked instead.
     */
    String wither() default "";
}
