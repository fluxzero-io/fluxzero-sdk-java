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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.publishing.dataprotection.MissingProtectedDataPolicy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or constructor as a handler for document messages within a search collection.
 * <p>
 * This is a specialization of {@link HandleMessage} for {@link MessageType#DOCUMENT} messages. It allows consuming
 * updates from the document store in near real-time—similar to event tracking. Handlers can either specify a collection
 * name or a document class, or infer a Model node or logical Graph from the handler parameter.
 * </p>
 *
 * <h2>Document Tracking Semantics</h2>
 * <p>
 * Each time a document is (re)indexed in Fluxzero, it receives a new tracking index. When a handler is subscribed
 * via {@code @HandleDocument}, it will receive the most recent version of the document for each index. If the tracker
 * is behind (e.g., during a replay), earlier versions of the same document may be skipped—only the latest version is
 * retained.
 * </p>
 *
 * <h2>Transforming and Updating Documents</h2>
 * <p>
 * A powerful feature of document handlers is that they can return a modified version of the document to update it
 * in-place. This allows document transformations and upcasting to be applied automatically and reliably.
 * </p>
 * <p>
 * For this behavior to take effect:
 * <ul>
 *   <li>The returned document must have a higher {@link Revision} than the one stored</li>
 *   <li>The updated version will be stored in the document collection under the same ID</li>
 * </ul>
 *
 * <p>
 * Ordinary document replacements preserve all metadata embedded in the handled stored version, including when
 * the payload was upcast. Derived search indexes/exclusions are regenerated from the replacement. Tracking-envelope metadata is not copied into the document. Return a
 * {@link io.fluxzero.sdk.common.Message} to replace the complete document metadata explicitly; empty metadata removes
 * it. The Message payload must still have a higher revision. Its ID and timestamp do not override the stored
 * document identity or the existing timestamp-selection rules. A plain {@code null} result still deletes the document.
 * </p>
 * <p>
 * Independent Model values use the stricter schema-only contract described by {@link #source()}.
 * This mechanism supports fully-automated data migrations: handlers can evolve or patch documents over time,
 * and changes are persisted across application restarts.
 * </p>
 *
 * @see Searchable
 * @see HandleMessage
 * @see MessageType#DOCUMENT
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@HandleMessage(MessageType.DOCUMENT)
public @interface HandleDocument {
    /**
     * Determines how the handler responds when referenced protected data is no longer available.
     */
    MissingProtectedDataPolicy onMissingProtectedData() default MissingProtectedDataPolicy.DEFAULT;

    /**
     * Optional name of the document collection. If provided, {@link #documentClass()} is ignored.
     *
     * <p>
     * If neither documentClass nor value are specified, the first parameter of the method is used to determine the
     * collection:
     *
     * <pre>{@code
     * class OrganisationHandler {
     *     @HandleDocument
     *     void on(Organisation organisation, Metadata metadata) { ... }
     * }
     * }</pre>
     *
     * @see Searchable
     */
    String value() default "";

    /**
     * Optional class of the documents to handle. If annotated with {@link Searchable}, the annotation defines the
     * collection; otherwise the class name is used.
     * <p>
     * If neither documentClass nor value are specified, the first parameter of the method is used to determine the
     * collection:
     *
     * <pre>{@code
     * class OrganisationHandler {
     *     @HandleDocument
     *     void on(Organisation organisation, Metadata metadata) { ... }
     * }
     * }</pre>
     *
     * @see Searchable
     */
    Class<?> documentClass() default Void.class;

    /**
     * Document source to observe. {@link DocumentSource#SEARCH} is the default: a Model parameter selects its
     * canonical searchable node, and {@code Graph<Model>} selects that root and its included descendants. Ancestor
     * content alone does not trigger the latter. Graph handling works with every projection mode; {@code NONE}
     * composes the latest indexed nodes when reading an update and does not retain previous Graph snapshots.
     * <p>
     * {@link DocumentSource#MODEL_STATE} explicitly selects maintained internal Model state for observation or
     * schema migration, including non-searchable DOCUMENT Models. It requires a Model value, not a Graph, and cannot
     * be combined with an explicit collection. There is no automatic fallback between sources.
     * <p>
     * Returning an injected Model value may persist a higher-revision schema upcast, but must preserve identity and
     * business state. Use Model commands for business changes. Returning the injected Graph migrates evolved
     * canonical nodes by default, including live Graphs in projection mode NONE; see {@link #graphMigration()}.
     * A void handler only observes. Neither route creates historical Graph snapshots that the selected storage does not maintain.
     */
    DocumentSource source() default DocumentSource.SEARCH;

    /**
     * Migration target when this handler returns its complete injected Graph. The default upcasts the verified
     * current source of each evolved node, preserving business state, identity, relationships and history. Returning
     * a Graph with modified values or topology is rejected. Nodes are migrated individually and idempotently;
     * concurrent changes are re-read, and persistent contention fails handling so the consumer can retry.
     * Custom serializers must implement {@link io.fluxzero.sdk.persisting.search.DocumentSerializer#modelStateSnapshot(Object)}
     * for this default route; the PROJECTION override retains the previous serializer contract.
     * <p>
     * Completion confirms node storage. Affected projections follow durably, including for Models configured with
     * AWAIT, whose waiting guarantee applies to ordinary Model commits. Select {@link GraphMigrationTarget#PROJECTION}
     * to retain projection-only migration; this does not write canonical nodes and is observational for live Graphs.
     */
    GraphMigrationTarget graphMigration() default GraphMigrationTarget.MODEL_STATE;

    /**
     * If {@code true}, disables this handler during discovery.
     */
    boolean disabled() default false;
}
