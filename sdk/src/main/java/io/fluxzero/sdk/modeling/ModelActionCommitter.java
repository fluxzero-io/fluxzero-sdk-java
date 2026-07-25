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
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.search.BulkUpdate;
import io.fluxzero.common.api.search.bulkupdate.DeleteDocument;
import io.fluxzero.common.api.search.bulkupdate.IndexDocument;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.publishing.DispatchInterceptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

    private final EventStoreClient eventStoreClient;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final String source;

    ModelActionCommitter(
            EventStoreClient eventStoreClient,
            DocumentStore documentStore,
            Serializer serializer,
            DispatchInterceptor dispatchInterceptor,
            String source) {
        this.eventStoreClient = Objects.requireNonNull(eventStoreClient);
        this.documentStore = Objects.requireNonNull(documentStore);
        this.serializer = Objects.requireNonNull(serializer);
        this.dispatchInterceptor = Objects.requireNonNull(dispatchInterceptor);
        this.source = source;
    }

    CompletableFuture<Optional<CommitModelActionResult>> commit(
            String actionId, ModelActionEngine.ActionEvaluation evaluation) {
        PreparedCommit prepared = prepare(actionId, evaluation);
        if (prepared.action() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return eventStoreClient.commitModelAction(prepared.action())
                .thenCompose(result -> updateDirectDocuments(prepared.documents())
                        .thenApply(ignored -> Optional.of(result)));
    }

    PreparedCommit prepare(String actionId, ModelActionEngine.ActionEvaluation evaluation) {
        Objects.requireNonNull(actionId, "actionId");
        if (actionId.isBlank()) {
            throw new IllegalArgumentException("Model action ID must not be blank");
        }
        Objects.requireNonNull(evaluation, "evaluation");

        List<ModelActionSubstep> substeps = new ArrayList<>();
        LinkedHashMap<String, DirectDocument> documents = new LinkedHashMap<>();
        for (ModelActionEngine.AppliedSubstep appliedSubstep : evaluation.substeps()) {
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
                applyEventRouting(event, transitions);
            }

            List<ModelActionTarget> targets = new ArrayList<>(transitions.size());
            for (EffectiveTransition transition : transitions) {
                ModelActionEngine.Transition sourceTransition = transition.transition();
                targets.add(ModelActionTarget.builder()
                                    .modelId(sourceTransition.modelId())
                                    .storeEvent(transition.storeEvent())
                                    .updateState(true)
                                    .delete(sourceTransition.after() == null)
                                    .relationships(relationships(sourceTransition))
                                    .build());
                directDocument(sourceTransition, appliedSubstep.message().getTimestamp())
                        .ifPresent(document -> documents.put(sourceTransition.modelId(), document));
            }
            substeps.add(ModelActionSubstep.builder()
                                 .event(event)
                                 .publishEvent(publishEvent)
                                 .targets(List.copyOf(targets))
                                 .build());
        }
        if (substeps.isEmpty()) {
            return new PreparedCommit(null, List.of());
        }
        CommitModelAction action = new CommitModelAction(
                actionId, evaluation.readStateIndex(), evaluation.readModelIds(),
                List.copyOf(substeps), STORED);
        return new PreparedCommit(action, List.copyOf(documents.values()));
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

    private static Optional<DirectDocument> directDocument(
            ModelActionEngine.Transition transition, Instant eventTimestamp) {
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
            return Optional.of(new DirectDocument(
                    transition.modelId(), collection, null, null, null));
        }
        Instant begin = parseTimeProperty(
                blankToNull(model.timestampPath()), value, false, () -> eventTimestamp);
        Instant end = parseTimeProperty(
                blankToNull(model.endPath()), value, true, () -> begin);
        return Optional.of(new DirectDocument(
                transition.modelId(), collection, value, begin, end));
    }

    private CompletableFuture<Void> updateDirectDocuments(List<DirectDocument> documents) {
        if (documents.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<BulkUpdate> updates = documents.stream().map(document ->
                document.value() == null
                        ? DeleteDocument.builder()
                                .id(document.modelId())
                                .collection(document.collection())
                                .build()
                        : IndexDocument.builder()
                                .object(document.value())
                                .id(document.modelId())
                                .collection(document.collection())
                                .timestamp(document.begin())
                                .end(document.end())
                                .build()).map(BulkUpdate.class::cast).toList();
        return documentStore.bulkUpdate(updates);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record PreparedCommit(CommitModelAction action, List<DirectDocument> documents) {
    }

    record DirectDocument(
            String modelId, String collection, Object value, Instant begin, Instant end) {
    }

    private record EffectiveTransition(
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
