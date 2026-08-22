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
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.search.Searchable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an independently identified and stored domain model.
 * <p>
 * Unlike an {@link Aggregate}, a model is its own persistence and lifecycle boundary. Loading or updating it does not
 * require loading a parent, sibling, child, or an artificial aggregate root. A model may still contain embedded
 * entities declared with {@link Member @Member}; those members share the model's stream, cache, search document,
 * snapshots, and lifecycle.
 * <p>
 * Model identity is the repository representation of its {@link EntityId @EntityId}. Applications can use a typed
 * {@link Id}, annotation-level prefix/postfix affixes, or both to isolate otherwise equal functional identifiers.
 * <p>
 * An {@link Apply @Apply} method targets a model by returning that model. Returning {@code null} deletes the targeted
 * model while retaining the applied event according to the configured publication settings. Returning {@code void} is
 * invalid for model applies because it does not identify a stored result. Legacy mutable aggregate applies remain
 * supported.
 *
 * <h2>Event-sourced and document-based loading</h2>
 * {@link #eventSourced()} selects the normal load path, not whether applied events are stored or published. An
 * event-sourced model is reconstructed from its model stream, optionally from a snapshot. A document-based model is
 * loaded directly from its current document. In both cases, event storage and publication are controlled independently
 * by {@link #eventPublication()}, {@link #publicationStrategy()}, and per-apply overrides.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Model(searchable = true)
 * public record Product(@EntityId ProductId productId, String name) {
 *     @Apply
 *     Product rename(RenameProduct command) {
 *         return new Product(productId, command.name());
 *     }
 * }
 * }</pre>
 * An update may instead create or update the model from a payload-side {@code @Apply}. When both sides define an
 * applicable apply, Fluxzero applies the payload first and invokes the model method against that intermediate state.
 * This lets one instance method consistently enforce model-owned behavior for both creation and later updates.
 *
 * @see Aggregate
 * @see io.fluxzero.sdk.Fluxzero#loadModel(Id)
 * @see io.fluxzero.sdk.persisting.repository.ModelRepository
 * @see Member
 * @see Parent
 * @see Apply
 * @see EntityId
 * @see Searchable
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Searchable
public @interface Model {

    /**
     * Conflict handling for this model when an apply does not provide an explicit override.
     */
    ModelConflictPolicy conflictPolicy() default ModelConflictPolicy.DEFAULT;

    /**
     * Controls whether applies producing this model may be exposed as automatic command handlers.
     */
    AutomaticModelHandling automaticHandling() default AutomaticModelHandling.DEFAULT;

    /**
     * Whether normal loads reconstruct the model from its event stream.
     * <p>
     * When disabled, the current model is loaded directly from its document. This setting does not suppress storing or
     * publishing events produced by {@link Apply} methods. A state-changing event-sourced model apply must store its
     * reconstructing event; a {@code PUBLISH_ONLY} or {@link EventPublication#NEVER NEVER} transition that would change
     * state is rejected before commit. A publish-only no-op remains a valid domain notification when publication is
     * explicitly set to {@link EventPublication#ALWAYS ALWAYS}.
     */
    boolean eventSourced() default true;

    /**
     * Whether unknown events should be ignored while reconstructing an event-sourced model.
     */
    boolean ignoreUnknownEvents() default false;

    /**
     * Number of stored model events between snapshots. {@code 0} disables periodic snapshots.
     */
    int snapshotPeriod() default 0;

    /**
     * Maximum number of snapshots retained for this model. Values below {@code 1} are treated as {@code 1}.
     */
    int maxSnapshotCount() default 1;

    /**
     * Whether the latest model state should be stored in the shared application cache.
     * <p>
     * Models participating in an commit may additionally be retained in an commit-local cache until the commit
     * completes.
     */
    boolean cached() default true;

    /**
     * Number of older model versions retained in the shared cache. {@code -1} retains all available cached versions;
     * {@code 0} retains only the latest version.
     * <p>
     * Independent models retain one previous version by default so event handlers can compare the event-visible model
     * with {@link Entity#previous()}. Retaining an unbounded revision chain must be an explicit choice because model
     * caches are expected to contain far more keys than aggregate caches.
     */
    int cachingDepth() default 1;

    /**
     * Frequency at which intermediate states are checkpointed within one reconstruction session.
     * <p>
     * Checkpoints avoid replaying the same prefix for repeated historical dependency loads. They are bounded by the
     * reconstruction session and are not retained as document revisions.
     */
    int checkpointPeriod() default 100;

    /**
     * Controls when model changes are committed and whether completion-phase commits may run concurrently.
     * <p>
     * The default value resolves from {@code fluxzero.model.commitPolicy} when present and otherwise uses
     * {@link ModelCommitPolicy#ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH}. Independent models were introduced with this
     * default, so it does not depend on the active defaults version.
     */
    ModelCommitPolicy commitPolicy() default ModelCommitPolicy.DEFAULT;

    /**
     * Controls whether an applied update produces an event when the returned model is unchanged.
     * <p>
     * Independent models default to {@link EventPublication#IF_MODIFIED IF_MODIFIED}, so a no-op apply does not
     * create a model-stream or globally published event. Use {@link EventPublication#ALWAYS ALWAYS} when an unchanged
     * apply intentionally represents a domain event. This setting is evaluated before {@link #publicationStrategy()}.
     */
    EventPublication eventPublication() default EventPublication.IF_MODIFIED;

    /**
     * Controls whether applied events are stored, published, or both.
     * <p>
     * {@link EventPublicationStrategy#PUBLISH_ONLY PUBLISH_ONLY} may mutate a document-loaded model. For an
     * event-sourced model it may only publish an unchanged result, because otherwise the next reconstruction could not
     * reproduce the committed state.
     */
    EventPublicationStrategy publicationStrategy() default EventPublicationStrategy.DEFAULT;

    /**
     * Controls how globally published events from this model are assigned to a message segment.
     */
    AggregateEventRouting eventRouting() default AggregateEventRouting.MESSAGE_ROUTING_KEY;

    /**
     * Whether the model should be synchronously indexed in Fluxzero's document store.
     * <p>
     * Successful commit completion makes the directly changed model searchable in its own collection. This setting
     * does not control graph participation: a model connected through an explicit {@link Parent#path()} still
     * supplies an internal current document for virtual and materialized graph composition. Composed root documents
     * are separate projections.
     */
    boolean searchable() default false;

    /**
     * Advanced configuration for the model's direct search document.
     * <p>
     * This configuration does not enable indexing by itself; set {@link #searchable()} to {@code true}. Defaults to
     * the model's simple class name as collection and to event timestamps when no timestamp paths are configured.
     */
    Searchable searchProjection() default @Searchable;

    /**
     * Whether Fluxzero should asynchronously materialize the complete model graph as a separate search document.
     * <p>
     * Materialization requires {@link #searchable()} so the root keeps its synchronous direct document. Only the
     * separately named graph collection is allowed to lag; its high-watermark is exposed through the model repository.
     * The collection defaults to {@code <resolved direct-model collection>-graphs}.
     */
    boolean materializeGraph() default false;

    /**
     * Advanced configuration for the materialized whole-graph search document.
     * <p>
     * This configuration does not enable materialization by itself; set {@link #materializeGraph()} to {@code true}.
     */
    GraphProjection graphProjection() default @GraphProjection;
}
