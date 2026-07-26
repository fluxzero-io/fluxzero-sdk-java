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

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.search.BulkUpdate;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.bulkupdate.DeleteDocument;
import io.fluxzero.common.api.search.bulkupdate.IndexDocument;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.function.Function;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.SearchUtils.parseTimeProperty;

/**
 * Converts a side-effect-free {@link ModelActionEngine} evaluation into one authoritative runtime commit and then
 * synchronously updates every directly searchable model document.
 * <p>
 * The original event payload is serialized once per substep. Per-target stream membership remains separate, while
 * global publication is the union of all targeted model publication policies. Direct search is deliberately awaited
 * after the authoritative commit so a successful model action is immediately searchable and retries can repair a
 * failed document update using the same durable action ID.
 */
final class ModelActionCommitter {
    private static final int MAX_PENDING_REPAIRS = 10_000;

    private final EventStoreClient eventStoreClient;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final DocumentSerializer documentSerializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final String source;
    private final Function<CommittedAction, CompletableFuture<Void>> afterCommit;
    private final Map<String, PendingCommit> pendingRepairs =
            new ConcurrentHashMap<>();
    private final Semaphore pendingRepairCapacity =
            new Semaphore(MAX_PENDING_REPAIRS);

    ModelActionCommitter(
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source) {
        this(eventStoreClient, documentStore, serializer, documentSerializer,
             dispatchInterceptor, source,
             ignored -> CompletableFuture.completedFuture(null));
    }

    ModelActionCommitter(
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DocumentSerializer documentSerializer,
            DispatchInterceptor dispatchInterceptor,
            String source,
            Function<CommittedAction, CompletableFuture<Void>> afterCommit) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient);
        this.documentStore = Objects.requireNonNull(documentStore);
        this.serializer = Objects.requireNonNull(serializer);
        this.documentSerializer = Objects.requireNonNull(documentSerializer);
        this.dispatchInterceptor = Objects.requireNonNull(dispatchInterceptor);
        this.source = source;
        this.afterCommit = Objects.requireNonNull(afterCommit);
    }

    CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId, ModelActionEngine.ActionEvaluation evaluation) {
        return commit(actionId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy) {
        PendingCommit pending = pendingRepairs.get(actionId);
        if (pending == null) {
            PreparedCommit prepared =
                    prepare(actionId, evaluation, conflictPolicy);
            if (prepared.action() == null) {
                return CompletableFuture.completedFuture(
                        Optional.empty());
            }
            if (!pendingRepairCapacity.tryAcquire()) {
                throw new RejectedExecutionException(
                        "Too many model actions are awaiting commit or direct-document repair");
            }
            PendingCommit candidate =
                    new PendingCommit(evaluation, prepared);
            PendingCommit known =
                    pendingRepairs.putIfAbsent(
                            actionId, candidate);
            if (known != null) {
                pendingRepairCapacity.release();
            }
            pending = known == null
                    ? candidate : known;
        }
        PendingCommit retained = pending;
        PreparedCommit prepared = pending.prepared();
        return eventStoreClient.commitModelAction(prepared.action())
                .thenCompose(result -> {
                    if (!result.isAccepted()) {
                        clearPending(
                                actionId, retained);
                        return CompletableFuture.completedFuture(
                                Optional.of(result));
                    }
                    return updateDirectDocuments(
                            prepared.documents())
                            .thenCompose(ignored ->
                                                 afterCommit.apply(
                                                         new CommittedAction(
                                                                 retained.evaluation(),
                                                                 prepared,
                                                                 result)))
                            .thenApply(ignored -> {
                                clearPending(
                                        actionId, retained);
                                return Optional.of(result);
                            });
                });
    }

    private void clearPending(
            String actionId, PendingCommit pending) {
        if (pendingRepairs.remove(
                actionId, pending)) {
            pendingRepairCapacity.release();
        }
    }

    CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxRetries,
            Supplier<CompletableFuture<ModelActionEngine.ActionEvaluation>> reload) {
        Objects.requireNonNull(conflictResolver, "conflictResolver");
        Objects.requireNonNull(reload, "reload");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Maximum model conflict retries must not be negative");
        }
        return commit(
                actionId, evaluation, conflictPolicy, conflictResolver, maxRetries, reload, 0);
    }

    private CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy,
            ModelConflictResolver conflictResolver,
            int maxRetries,
            Supplier<CompletableFuture<ModelActionEngine.ActionEvaluation>> reload,
            int retries) {
        return commit(actionId, evaluation, conflictPolicy).thenCompose(optional -> {
            if (optional.isEmpty() || optional.get().isAccepted()) {
                return CompletableFuture.completedFuture(optional);
            }
            CommitModelActionResult conflict = optional.get();
            ModelConflictResolver.Resolution resolution;
            try {
                resolution = Objects.requireNonNull(
                        conflictResolver.resolve(
                                new ModelConflictResolver.Context(conflict, retries, maxRetries)),
                        "Model conflict resolver returned null");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (resolution != ModelConflictResolver.Resolution.RETRY
                || !conflict.isRetryAllowed() || retries >= maxRetries) {
                return CompletableFuture.failedFuture(new ModelActionConflictException(conflict));
            }
            CompletableFuture<ModelActionEngine.ActionEvaluation> reloaded;
            try {
                reloaded = Objects.requireNonNull(
                        reload.get(), "Model conflict reload returned null");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return reloaded.thenCompose(next -> commit(
                    actionId, next, conflictPolicy, conflictResolver,
                    maxRetries, reload, retries + 1));
        });
    }

    PreparedCommit prepare(String actionId, ModelActionEngine.ActionEvaluation evaluation) {
        return prepare(actionId, evaluation, ModelConflictPolicy.ACCEPT);
    }

    PreparedCommit prepare(
            String actionId,
            ModelActionEngine.ActionEvaluation evaluation,
            ModelConflictPolicy conflictPolicy) {
        Objects.requireNonNull(actionId, "actionId");
        if (actionId.isBlank()) {
            throw new IllegalArgumentException("Model action ID must not be blank");
        }
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");

        List<ModelActionSubstep> substeps = new ArrayList<>();
        List<List<EffectiveTransition>> transitionGroups = new ArrayList<>();
        LinkedHashMap<String, DirectDocumentCandidate> documents = new LinkedHashMap<>();
        for (int evaluatedSubstep = 0;
             evaluatedSubstep < evaluation.substeps().size();
             evaluatedSubstep++) {
            ModelActionEngine.AppliedSubstep appliedSubstep =
                    evaluation.substeps().get(evaluatedSubstep);
            List<EffectiveTransition> transitions = appliedSubstep.transitions().stream()
                    .map(this::effectiveTransition)
                    .flatMap(Optional::stream)
                    .toList();
            if (transitions.isEmpty()) {
                continue;
            }
            boolean publishEvent = transitions.stream().anyMatch(EffectiveTransition::publishEvent);
            boolean eventRequired = publishEvent
                                    || transitions.stream().anyMatch(EffectiveTransition::storeEvent);
            SerializedMessage event = eventRequired ? serialize(appliedSubstep.message()) : null;
            if (event != null) {
                event.setSource(source);
                event.setMetadata(event.getMetadata().with(
                        ModelEventMetadata.ACTION_ID, actionId,
                        ModelEventMetadata.SUBSTEP, substeps.size()));
                applyEventRouting(event, transitions);
            }

            List<ModelActionTarget> targets = new ArrayList<>(transitions.size());
            for (EffectiveTransition transition : transitions) {
                ModelActionEngine.Transition sourceTransition = transition.transition();
                targets.add(ModelActionTarget.builder()
                                    .modelId(sourceTransition.modelId())
                                    .modelType(sourceTransition.modelType().getName())
                                    .storeEvent(transition.storeEvent())
                                    .updateState(true)
                                    .delete(sourceTransition.after() == null)
                                    .relationships(relationships(sourceTransition))
                                    .build());
                directDocument(
                        sourceTransition, appliedSubstep.message().getTimestamp(),
                        appliedSubstep.message().getMetadata())
                        .ifPresent(document -> documents.put(sourceTransition.modelId(), document));
            }
            substeps.add(ModelActionSubstep.builder()
                                 .event(event)
                                 .publishEvent(publishEvent)
                                 .targets(List.copyOf(targets))
                                 .build());
            transitionGroups.add(transitions);
        }
        if (substeps.isEmpty()) {
            return new PreparedCommit(null, List.of(), List.of());
        }
        CommitModelAction action = new CommitModelAction(
                actionId, evaluation.readStateIndex(), evaluation.readModelIds(),
                List.copyOf(substeps), conflictPolicy, STORED);
        return new PreparedCommit(
                action, documents.values().stream().map(this::serializeDocument).toList(),
                List.copyOf(transitionGroups));
    }

    private SerializedMessage serialize(DeserializingMessage message) {
        SerializedMessage serialized = dispatchInterceptor.modifySerializedMessage(
                message.toMessage().serialize(serializer), message.toMessage(), EVENT, null);
        if (serialized == null) {
            throw new IllegalStateException(
                    "Serialized model event was suppressed after @Apply evaluation; "
                    + "logical event suppression must happen before model applies");
        }
        return serialized;
    }

    private Optional<EffectiveTransition> effectiveTransition(ModelActionEngine.Transition transition) {
        Publication publication = publication(transition);
        if (publication.eventPublication() == EventPublication.IF_MODIFIED
            && Objects.equals(transition.before(), transition.after())) {
            return Optional.empty();
        }
        if (publication.eventPublication() == EventPublication.NEVER) {
            return Optional.of(new EffectiveTransition(
                    transition, false, false, publication.eventRouting()));
        }
        return Optional.of(switch (publication.publicationStrategy()) {
            case STORE_AND_PUBLISH ->
                    new EffectiveTransition(transition, true, true, publication.eventRouting());
            case STORE_ONLY ->
                    new EffectiveTransition(transition, true, false, publication.eventRouting());
            case PUBLISH_ONLY ->
                    new EffectiveTransition(transition, false, true, publication.eventRouting());
            case DEFAULT -> throw new IllegalStateException("Unresolved model publication strategy");
        });
    }

    private Publication publication(ModelActionEngine.Transition transition) {
        ModelMetadata.RootConfiguration model = ModelMetadata.of(transition.modelType())
                .rootConfiguration().orElseThrow(() -> new IllegalStateException(
                        transition.modelType().getName() + " is not an independent model"));
        Apply apply = transition.handler().getAnnotation(Apply.class);
        EventPublication eventPublication =
                apply != null && apply.eventPublication() != EventPublication.DEFAULT
                        ? apply.eventPublication()
                        : model.eventPublication() == EventPublication.DEFAULT
                                ? EventPublication.ALWAYS : model.eventPublication();
        EventPublicationStrategy strategy =
                apply != null && apply.publicationStrategy() != EventPublicationStrategy.DEFAULT
                        ? apply.publicationStrategy()
                        : model.publicationStrategy() == EventPublicationStrategy.DEFAULT
                                ? EventPublicationStrategy.STORE_AND_PUBLISH : model.publicationStrategy();
        AggregateEventRouting routing =
                apply != null && apply.eventRouting() != AggregateEventRouting.DEFAULT
                        ? apply.eventRouting()
                        : model.eventRouting() == AggregateEventRouting.DEFAULT
                                ? AggregateEventRouting.MESSAGE_ROUTING_KEY : model.eventRouting();
        return new Publication(eventPublication, strategy, routing);
    }

    private static void applyEventRouting(
            SerializedMessage event, List<EffectiveTransition> transitions) {
        List<EffectiveTransition> published = transitions.stream()
                .filter(EffectiveTransition::publishEvent).toList();
        if (published.isEmpty()) {
            return;
        }
        boolean aggregateIdRouting = published.stream()
                .anyMatch(transition -> transition.eventRouting() == AggregateEventRouting.AGGREGATE_ID);
        boolean messageRouting = published.stream()
                .anyMatch(transition -> transition.eventRouting() == AggregateEventRouting.MESSAGE_ROUTING_KEY);
        if (aggregateIdRouting && (messageRouting || published.size() != 1)) {
            throw new IllegalStateException(
                    "One model event cannot use conflicting aggregate-ID routing for multiple published targets");
        }
        if (aggregateIdRouting) {
            event.setSegment(ConsistentHashing.computeSegment(
                    published.getFirst().transition().modelId()));
        }
    }

    private static List<ModelRelationship> relationships(ModelActionEngine.Transition transition) {
        if (transition.after() == null) {
            return List.of();
        }
        LinkedHashMap<RelationshipKey, ModelRelationship> result = new LinkedHashMap<>();
        for (ModelMetadata.ParentReference parent :
                ModelMetadata.of(transition.after().getClass()).parentReferences()) {
            Object parentId = parent.read(transition.after());
            if (parentId == null) {
                continue;
            }
            ModelRelationship relationship = ModelRelationship.builder()
                    .parentId(parentId.toString())
                    .parentType(parent.parentModelType() == null
                                        ? null : parent.parentModelType().getName())
                    .path(parent.path().isEmpty() ? null : parent.path())
                    .build();
            if (transition.modelId().equals(relationship.getParentId())) {
                throw new IllegalStateException(
                        "Model '%s' cannot be its own parent".formatted(transition.modelId()));
            }
            result.putIfAbsent(new RelationshipKey(
                    relationship.getParentId(), relationship.getParentType(), relationship.getPath()), relationship);
        }
        return List.copyOf(result.values());
    }

    private static Optional<DirectDocumentCandidate> directDocument(
            ModelActionEngine.Transition transition, Instant eventTimestamp, Metadata metadata) {
        ModelMetadata.RootConfiguration model = ModelMetadata.of(transition.modelType())
                .rootConfiguration().orElseThrow();
        if (!model.searchable()) {
            return Optional.empty();
        }
        String collection = Optional.of(model.collection())
                .filter(value -> !value.isEmpty())
                .map(ApplicationProperties::substituteProperties)
                .orElse(transition.modelType().getSimpleName());
        Object value = transition.after();
        if (value == null) {
            return Optional.of(new DirectDocumentCandidate(
                    transition.modelId(), collection, null, null, null, metadata));
        }
        Instant begin = parseTimeProperty(
                blankToNull(model.timestampPath()), value, false, () -> eventTimestamp);
        Instant end = parseTimeProperty(
                blankToNull(model.endPath()), value, true, () -> begin);
        return Optional.of(new DirectDocumentCandidate(
                transition.modelId(), collection, value, begin, end, metadata));
    }

    private DirectDocument serializeDocument(DirectDocumentCandidate candidate) {
        SerializedDocument document = candidate.value() == null ? null : documentSerializer.toDocument(
                candidate.value(), candidate.modelId(), candidate.collection(),
                candidate.begin(), candidate.end(), candidate.metadata());
        return new DirectDocument(candidate.modelId(), candidate.collection(), document);
    }

    private CompletableFuture<Void> updateDirectDocuments(List<DirectDocument> documents) {
        if (documents.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<BulkUpdate> updates = documents.stream().map(document ->
                document.document() == null
                        ? DeleteDocument.builder()
                                .id(document.modelId())
                                .collection(document.collection())
                                .build()
                        : IndexDocument.fromDocument(document.document()))
                .map(BulkUpdate.class::cast).toList();
        return documentStore.bulkUpdate(updates);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record PreparedCommit(
            CommitModelAction action,
            List<DirectDocument> documents,
            List<List<EffectiveTransition>> transitionGroups) {
    }

    record CommittedAction(
            ModelActionEngine.ActionEvaluation evaluation,
            PreparedCommit prepared,
            CommitModelActionResult result) {
    }

    private record PendingCommit(
            ModelActionEngine.ActionEvaluation evaluation,
            PreparedCommit prepared) {
    }

    record DirectDocument(
            String modelId, String collection, SerializedDocument document) {
    }

    private record DirectDocumentCandidate(
            String modelId,
            String collection,
            Object value,
            Instant begin,
            Instant end,
            Metadata metadata) {
    }

    record EffectiveTransition(
            ModelActionEngine.Transition transition,
            boolean storeEvent,
            boolean publishEvent,
            AggregateEventRouting eventRouting) {
    }

    private record Publication(
            EventPublication eventPublication,
            EventPublicationStrategy publicationStrategy,
            AggregateEventRouting eventRouting) {
    }

    private record RelationshipKey(String parentId, String parentType, String path) {
    }
}
