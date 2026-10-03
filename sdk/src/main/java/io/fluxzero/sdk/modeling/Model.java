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
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;

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
 * Choose this boundary from domain lifecycle first: state that can be created, changed, retained, deleted, or whose
 * history matters independently is a separate model, even when it is normally displayed in a parent's collection.
 * Connect such a child with {@link Parent @Parent}. A meaningful domain identity is strong evidence for that boundary,
 * not an additional gate: an independently living child may use a globally unique ID or a
 * {@link EntityId#parentScoped() parent-scoped} ID. Collection shape, searchability, storage format, update frequency,
 * and convenient embedding do not make independently living state a {@link Member}.
 * <p>
 * Model identity is the repository representation of its {@link EntityId @EntityId}. Applications can use a typed
 * {@link Id}, annotation-level prefix/postfix affixes, or both to isolate otherwise equal functional identifiers.
 * <p>
 * An {@link Apply @Apply} method targets a model by returning that model. Returning {@code null} deletes the targeted
 * model while retaining the applied event according to the configured publication settings. Returning {@code void} is
 * invalid for model applies because it does not identify a stored result. Legacy mutable aggregate applies remain
 * supported.
 *
 * <h2>Persistence</h2>
 * {@link #persistence()} makes the durable representations and authoritative load path explicit. Event-sourced models
 * are reconstructed from their model stream, optionally from a snapshot. Adding {@link ModelPersistence#DOCUMENT}
 * maintains internal current state; when event sourcing is absent, that current state is authoritative. Event storage
 * and publication remain independent and are controlled by {@link #eventPublication()},
 * {@link #publicationStrategy()}, and per-apply overrides. Internal component documents used for Graph composition are
 * likewise orthogonal and never change the selected load path.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Model
 * public record Product(@EntityId ProductId productId, ProductDetails details) {
 *     @Apply
 *     Product rename(RenameProduct command) {
 *         return new Product(productId, details.withName(command.name()));
 *     }
 * }
 * }</pre>
 * Here {@code ProductDetails} is an immutable business value with a copy method such as Lombok's generated
 * {@code withName}. Use a cohesive details value even when the only descriptive field is a name; keep identity,
 * relationships and simple status distinct. A plain details value needs neither {@code @Model} nor {@link Member}.
 * A focused rename command may carry a scalar while replacing only that field in the existing details.
 * An update may instead create or update the model from a payload-side {@code @Apply}. When both sides define an
 * applicable apply, Fluxzero applies the payload first and invokes the model method against that intermediate state.
 * This lets one instance method consistently enforce model-owned behavior for both creation and later updates.
 * <p>
 * Persistence and searchability are separate choices. Enable {@link #searchable()} to query this Model and its
 * composed descendants; {@link SearchSettings#includeDescendants()} limits a root's Graph scope. An explicit
 * {@link Parent#pathInParent()} only describes composition, never storage. The default Graph mode retains separate
 * indexed node documents and composes them when read. Choose {@link GraphProjectionMode#ASYNC} or
 * {@link GraphProjectionMode#AWAIT} to additionally store the composed Graph.
 *
 * Model declarations are indexed at compilation by {@link ModelTypeProcessor}. Enable SDK annotation processing
 * (Kotlin: kapt) in each contract module and preserve {@link ModelTypes#INDEX} when packaging. This index discovers
 * classes without registering message handlers; the optional serialization {@code @RegisterType} is not required.
 * Contract JARs built before this index was introduced must be rebuilt for cold discovery. A shaded JAR must append
 * all contributing Model indexes rather than retain only one.
 * Identified abstract/interface contracts remain discoverable; identity-less abstract/interface inheritance templates
 * are not standalone Models and are omitted from the runtime catalog.
 *
 * @see Aggregate
 * @see io.fluxzero.sdk.Fluxzero#loadModel(Id)
 * @see io.fluxzero.sdk.persisting.repository.ModelRepository
 * @see Member
 * @see Parent
 * @see Apply
 * @see EntityId
 * @see ModelPersistence
 * @see SearchSettings
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Model {

    /**
     * Stable logical name of this Model in persisted Model metadata.
     * <p>
     * The default is the simple name of the concrete Model class. This name deliberately does not contain the Java
     * package, so moving or renaming a class remains possible by retaining its previous logical name explicitly.
     * Applications sharing one Runtime namespace can prepend an application-scoped prefix with
     * {@code fluxzero.model.namePrefix}; the prefix is concatenated literally and should therefore include any desired
     * separator; prefix {@code billing} and name {@code Invoice} become {@code billingInvoice}.
     * <p>
     * This is a durable identity. Changing it for an existing Model creates a different Model type and requires an
     * application-managed data transition.
     */
    String name() default "";

    /**
     * Conflict handling for this model when an apply does not provide an explicit override.
     * {@link ModelConflictPolicy#DEFAULT} inherits the application policy, which defaults to
     * {@link ModelConflictPolicy#RETRY} for updates and creations alike, independently of the defaults version.
     * Factory compatibility is checked again after retry; retry does not implicitly turn a factory into an upsert.
     */
    ModelConflictPolicy conflictPolicy() default ModelConflictPolicy.DEFAULT;

    /**
     * Controls whether applies producing this model may be exposed as automatic command handlers.
     */
    AutomaticModelHandling automaticHandling() default AutomaticModelHandling.DEFAULT;

    /**
     * Durable representations and authoritative load path for this Model.
     * <p>
     * The set must contain at least one unique value. When {@link ModelPersistence#EVENT_SOURCED EVENT_SOURCED} is
     * present, the event stream is authoritative. Otherwise {@link ModelPersistence#DOCUMENT DOCUMENT} is
     * authoritative. To use {@link Graph#previous()} for historical values, keep {@code EVENT_SOURCED} enabled:
     * {@code DOCUMENT} alone stores current state, not previous versions. Adding {@code DOCUMENT} to event sourcing
     * preserves history; replacing event sourcing with {@code DOCUMENT} does not.
     * <p>
     * This setting does not suppress storing or publishing events produced by {@link Apply} methods. A state-changing
     * event-sourced Model apply must store its reconstructing event; a {@code PUBLISH_ONLY} or
     * {@link EventPublication#NEVER NEVER} transition that would change state is rejected before commit. A publish-only
     * no-op remains a valid domain notification when publication is explicitly set to
     * {@link EventPublication#ALWAYS ALWAYS}.
     */
    ModelPersistence[] persistence() default {ModelPersistence.EVENT_SOURCED};

    /**
     * Whether unknown events should be ignored while reconstructing an event-sourced model.
     */
    boolean ignoreUnknownEvents() default false;

    /**
     * Number of stored model events between snapshots. The default {@code 0} disables periodic snapshots.
     * Enable only to bound measured replay work; snapshots do not create a searchable current document.
     */
    int snapshotPeriod() default 0;

    /**
     * Maximum number of snapshots retained for this model. Any negative value retains all periodic snapshots;
     * {@code 0} is treated as {@code 1}. This does not enable snapshots: {@link #snapshotPeriod()} must be positive.
     * Unlimited retention does not prevent explicit physical erasure and does not change event-history retention.
     * Enable negative values only after upgrading the Runtime to support unlimited Model snapshot retention.
     */
    int maxSnapshotCount() default 1;

    /**
     * Whether the latest model state should be stored in the shared application cache.
     * <p>
     * Models participating in a commit may additionally be retained in a commit-local cache until the commit
     * completes. A document revision alone is not a namespace snapshot: simple DOCUMENT writes verify that revision
     * when an additional transactional read first needs a shared boundary; complex contexts verify before evaluation.
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
     * Controls whether an applied update produces an event, including unchanged results.
     * <p>
     * Independent models default to {@link EventPublication#IF_MODIFIED IF_MODIFIED}, so a no-op apply does not
     * create a model-stream or globally published event. Use {@link EventPublication#ALWAYS ALWAYS} when an unchanged
     * apply intentionally represents a domain event. This setting is evaluated before {@link #publicationStrategy()}.
     * {@link EventPublication#NEVER NEVER} permits eventless state changes only for document-loaded Models, not
     * event-sourced Models. It does not suppress incoming command/webrequest logs, results or application logging.
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
     * Whether this Model independently activates search.
     * <p>
     * Setting this to {@code true} starts a searchable scope at this Model and, by default, makes composed descendants
     * effectively searchable as well. Setting it to {@code false} does <strong>not</strong> mean that this Model can
     * never be searched; it only means that this Model does not activate search by itself. A searchable ancestor can
     * still include it.
     * <p>
     * {@link Model#searchable()} starts a searchable scope. {@link Parent#propagateSearch()} controls whether that scope may
     * propagate across a parent relationship. Set {@code @Parent(propagateSearch = false)} to block inherited searchability
     * through one edge while leaving the child's own search activation and other parent relationships independent.
     * {@link SearchSettings#includeDescendants()} can limit this Model's own searchable scope to the Model itself; it
     * does not prevent the Model from participating in a broader searchable ancestor scope.
     * <p>
     * Search activation does not require every property to participate in text search. Use
     * {@link SearchExclude @SearchExclude} on individual properties or types to exclude them from text indexing and
     * matching while retaining them in the stored and returned document.
     * <p>
     * Whether the complete composed Graph is materialized is a separate choice controlled by {@link #graphProjection()}.
     * Its default mode is {@link GraphProjectionMode#NONE NONE}: Fluxzero keeps the separate indexed node documents and
     * composes Graph results live when queried, without storing a complete Graph document. Use
     * {@link GraphProjectionMode#ASYNC ASYNC} or {@link GraphProjectionMode#AWAIT AWAIT} only when a stored composed
     * Graph is desired.
     */
    boolean searchable() default false;

    /** Per-node index settings and the scope of Graph queries rooted at this Model. Does not activate search. */
    SearchSettings searchSettings() default @SearchSettings;

    /**
     * Optional materialization of a complete composed Graph, configured independently from search activation.
     * The default {@link GraphProjectionMode#NONE NONE} stores no complete Graph document and composes searchable
     * Graphs live from the separate indexed nodes. ASYNC/AWAIT additionally maintain a stored composed Graph.
     */
    GraphProjection graphProjection() default @GraphProjection;
}
