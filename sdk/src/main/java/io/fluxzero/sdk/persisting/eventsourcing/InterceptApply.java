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
 *
 */

package io.fluxzero.sdk.persisting.eventsourcing;

import io.fluxzero.sdk.modeling.AssertLegal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;

/**
 * Indicates that a method should intercept and potentially transform an update before it is applied to an entity.
 * <p>
 * This annotation is typically used to:
 * <ul>
 *     <li>Suppress updates that should be ignored</li>
 *     <li>Rewrite or correct invalid updates</li>
 *     <li>Split a single update into multiple updates</li>
 * </ul>
 * <p>
 * Interceptors are invoked <strong>before</strong> any {@link Apply @Apply} or {@link AssertLegal @AssertLegal}
 * methods. If multiple interceptors match, they are invoked recursively until the result stabilizes.
 *
 * <p>
 * Interceptors can return:
 * <ul>
 *     <li>The original update (no change)</li>
 *     <li>{@code null} or {@code void} to suppress the update</li>
 *     <li>An {@link java.util.Optional}, {@link Collection}, or {@link java.util.stream.Stream} to emit zero or more updates</li>
 *     <li>A staged {@link io.fluxzero.sdk.modeling.Graph} returned by
 *         {@link io.fluxzero.sdk.modeling.Graph#update(java.util.function.UnaryOperator)} or
 *         {@link io.fluxzero.sdk.modeling.Graph#delete()} to change that independently stored model in the same
 *         commit</li>
 *     <li>A different object to replace the update</li>
 * </ul>
 *
 * <p>
 * Method parameters are automatically injected and may include:
 * <ul>
 *     <li>The current entity (if it exists)</li>
 *     <li>Any parent or ancestor entity in the aggregate</li>
 *     <li>Any independently stored {@link io.fluxzero.sdk.modeling.Model @Model} loaded for the current model commit,
 *         either as its value or as a lazy {@link io.fluxzero.sdk.modeling.Graph}{@code <T>}</li>
 *     <li>The update object (if defined on the entity side)</li>
 *     <li>Context like {@link io.fluxzero.common.api.Metadata}, {@link io.fluxzero.sdk.common.Message}, or
 *         {@link io.fluxzero.sdk.tracking.handling.authentication.User}</li>
 * </ul>
 * Injected models are read inputs unless one of the emitted updates later targets and returns them from an
 * {@link Apply @Apply}.
 *
 * <p>
 * Note that empty entities (where the value is {@code null}) are not injected unless the parameter is annotated with
 * {@code @Nullable}.
 *
 * <h2>Examples</h2>
 *
 * <h3>1. Rewrite a duplicate create into an update (inside the update class)</h3>
 * <pre>{@code
 * @InterceptApply
 * UpdateProject resolveDuplicateCreate(Project project) {
 *     // If this method is invoked, the Project already exists
 *     return new UpdateProject(projectId, details);
 * }
 * }</pre>
 *
 * <h3>2. Suppress a no-op update</h3>
 * <pre>{@code
 * @InterceptApply
 * Object ignoreNoChange(Product product) {
 *     if (product.getDetails().equals(details)) {
 *         return null; // suppress update
 *     }
 *     return this;
 * }
 * }</pre>
 *
 * <p><strong>Note:</strong> You typically do <em>not</em> need to implement this kind of check manually if the
 * enclosing {@link io.fluxzero.sdk.modeling.Aggregate @Aggregate} or specific
 * {@link Apply @Apply} method is configured with
 * {@link io.fluxzero.sdk.modeling.EventPublication#IF_MODIFIED IF_MODIFIED}.
 * That configuration ensures that no event is stored or published if the entity is not modified.
 *
 * <h3>3. Expand a bulk update into individual operations</h3>
 * <pre>{@code
 * @InterceptApply
 * List<CreateTask> explodeBulkCreate() {
 *     return tasks;
 * }
 * }</pre>
 *
 * <h3>4. Change graph models in the same commit</h3>
 * <pre>{@code
 * @InterceptApply
 * List<?> move(Graph<Order> order) {
 *     Graph<OrderLine> line = order.find(lineId, OrderLine.class)
 *             .orElseThrow();
 *     return List.of(this, line.update(value -> value.withOrderId(targetOrderId)));
 * }
 * }</pre>
 * The original domain update remains the stored event shared by every changed model. Direct graph updates are replayed
 * against a fresh pinned state after an accepted conflict, so their update function must be deterministic and free of
 * external side effects. Return ordinary domain updates when their {@link Apply @Apply} publication configuration
 * should govern the transition; returning a graph produced by {@code apply(...)} is deliberately unsupported.
 *
 * <h3>5. Recursive interception</h3>
 * <p>
 * If the result of one {@code @InterceptApply} method is a new update object, Fluxzero will look for matching
 * interceptors for the new value as well — continuing recursively until no further changes occur.
 *
 * @see Apply
 * @see AssertLegal
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InterceptApply {
}
