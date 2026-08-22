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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitConflict;
import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelCommitValidator;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository.Commit;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class DefaultModelRepositoryCommitTest {

    private final DefaultModelRepository repository = mock(DefaultModelRepository.class);
    private final EventStoreClient eventStoreClient = mock(EventStoreClient.class);
    private final JacksonSerializer serializer = new JacksonSerializer();
    private final Commit protocol = repository.new Commit(
            eventStoreClient, serializer, serializer,
            DispatchInterceptor.noOp, "client-1", serializer,
            GraphProjectionCompletion.ASYNC);

    @Test
    void preparesHandlerlessGraphDeletionUsingModelPublicationPolicy() {
        StoredOnlyId id = new StoredOnlyId("graph-delete");
        StoredOnly before = new StoredOnly(id);
        RemoveStoredOnly event = new RemoveStoredOnly(id);
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(
                        event,
                        Change.applied(
                                id.toString(), StoredOnly.class, 0L,
                                null, before, null, null,
                                current -> current.update(ignored -> null), false)),
                Map.of());

        CommitModels commit = protocol.prepare(
                "graph-delete", evaluation).commit();

        assertEquals(1, commit.getSubsteps().size());
        assertEquals(
                DirectModelUpdate.class.getName(),
                commit.getSubsteps().getFirst().getEvent().getType());
        DirectModelUpdate update = assertInstanceOf(
                DirectModelUpdate.class,
                serializer.deserializeMessage(
                        commit.getSubsteps().getFirst().getEvent(),
                        MessageType.EVENT).getPayload());
        assertNull(update.target(id.toString()).getState());
        assertFalse(commit.getSubsteps().getFirst().isPublishEvent());
        assertTrue(commit.getSubsteps().getFirst()
                           .getTargets().getFirst().isStoreEvent());
    }

    @Test
    void sharesOneDomainEventAcrossOrdinaryAndGraphDeletionTargets()
            throws Exception {
        StoredOnlyId storedId = new StoredOnlyId("delete");
        PublishedOnlyId publishedId = new PublishedOnlyId("retain");
        StoredOnly stored = new StoredOnly(storedId);
        PublishedOnly published = new PublishedOnly(publishedId);
        MixedUpdate event = new MixedUpdate(storedId, publishedId);
        DeserializingMessage message = new DeserializingMessage(
                new Message(event), MessageType.EVENT, null);
        var evaluation = evaluation(
                List.of(storedId.toString(), publishedId.toString()),
                List.of(
                        new TestStep(
                                message, List.of(
                                transition(
                                        publishedId, PublishedOnly.class,
                                        published, published,
                                        MixedUpdate.class, "apply", PublishedOnly.class))),
                        new TestStep(
                                message, List.of(
                                Change.applied(
                                        storedId.toString(), StoredOnly.class, 0L,
                                        null, stored, null, null,
                                        current -> current.update(ignored -> null), false)))),
                Map.of(publishedId.toString(), published));

        Commit.Outcome prepared =
                protocol.prepare("shared-event-delete", evaluation);

        assertEquals(2, prepared.commit().getSubsteps().size());
        assertEquals(1, prepared.commit().getSubsteps().getFirst()
                .getTargets().size());
        assertEquals(MixedUpdate.class.getName(), prepared.commit()
                .getSubsteps().getFirst().getEvent().getType());
        assertTrue(prepared.commit().getSubsteps().getFirst().isPublishEvent());
        assertEquals(DirectModelUpdate.class.getName(), prepared.commit()
                .getSubsteps().getLast().getEvent().getType());
        assertFalse(prepared.commit().getSubsteps().getLast().isPublishEvent());
        DirectModelUpdate direct = assertInstanceOf(
                DirectModelUpdate.class,
                serializer.deserializeMessage(
                        prepared.commit().getSubsteps().getLast().getEvent(),
                        MessageType.EVENT).getPayload());
        assertNull(direct.target(storedId.toString()).getState());
        assertEquals(2, prepared.changes().size());
    }

    @Test
    void publishesAStandaloneGraphUpdateWithoutStoringTheDomainEventAsItsReplay() {
        OrderId id = new OrderId("standalone-graph");
        Order before = new Order(id, null, "before", Instant.EPOCH);
        Order after = new Order(id, null, "after", Instant.EPOCH.plusSeconds(1));
        UpdateOrder event = new UpdateOrder(id);
        var transition = Change.applied(
                id.toString(), Order.class, 0L, null,
                before, after, null, current -> current, false);
        var evaluation = evaluation(
                List.of(id.toString()), substep(event, transition),
                Map.of(id.toString(), after));

        Commit.Outcome prepared =
                protocol.prepare("standalone-graph", evaluation);

        ModelCommitValidator.validate(prepared.commit());
        assertEquals(2, prepared.commit().getSubsteps().size());
        var publication = prepared.commit().getSubsteps().getFirst();
        assertEquals(UpdateOrder.class.getName(), publication.getEvent().getType());
        assertTrue(publication.isPublishEvent());
        assertEquals(1, publication.getTargets().size());
        assertEquals(id.toString(), publication.getTargets().getFirst().getModelId());
        assertNull(publication.getTargets().getFirst().getExpectedSequenceNumber());
        assertFalse(publication.getTargets().getFirst().isStoreEvent());
        assertFalse(publication.getTargets().getFirst().isUpdateState());
        var storage = prepared.commit().getSubsteps().getLast();
        assertEquals(DirectModelUpdate.class.getName(), storage.getEvent().getType());
        assertFalse(storage.isPublishEvent());
        assertEquals(id.toString(), storage.getTargets().getFirst().getModelId());
        assertTrue(storage.getTargets().getFirst().isStoreEvent());
        DirectModelUpdate direct = assertInstanceOf(
                DirectModelUpdate.class,
                serializer.deserializeMessage(
                        storage.getEvent(), MessageType.EVENT).getPayload());
        assertEquals(
                after,
                serializer.deserialize(direct.target(id.toString()).getState()));
        assertEquals(
                List.of(transition.withEffects(true, false, true)),
                List.copyOf(prepared.changes()));
    }

    @Test
    void standaloneGraphUpdateRegeneratesItsReplayStateAfterAcceptRebase() {
        OrderId id = new OrderId("standalone-rebase");
        Order before = new Order(id, null, "before", Instant.EPOCH);
        Order stale = new Order(id, null, "stale", Instant.EPOCH.plusSeconds(1));
        Order merged = new Order(id, null, "merged", Instant.EPOCH.plusSeconds(2));
        UpdateOrder event = new UpdateOrder(id);
        var original = evaluation(
                List.of(id.toString()),
                substep(event, Change.applied(
                        id.toString(), Order.class, 0L, null,
                        before, stale, null, current -> current, false)),
                Map.of(id.toString(), stale));
        var rebased = evaluation(
                51L, List.of(id.toString()), Map.of(id.toString(), Order.class),
                List.of(substep(event, Change.applied(
                        id.toString(), Order.class, 0L, null,
                        before, merged, null, current -> current, false))),
                Map.of(id.toString(), merged));
        AtomicInteger commits = new AtomicInteger();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels request = invocation.getArgument(0);
            if (commits.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(
                        CommitModelsResult.rebase(
                                request.getRequestId(), request.getCommitId(),
                                List.of(new ModelCommitConflict(
                                        id.toString(), 51L, 0L)), 51L));
            }
            return CompletableFuture.completedFuture(result(request));
        });

        var accepted = ModelPipeline.commit(
                protocol, "standalone-rebase", original, ModelConflictPolicy.ACCEPT,
                ModelPipeline.Retry.accepting((result, current) -> {
                    assertEquals(51L, result.getRebaseStateIndex());
                    assertEquals(1, current.steps().size());
                    return CompletableFuture.completedFuture(rebased);
                }), null, -1).join();

        assertTrue(accepted.orElseThrow().isAccepted());
        ArgumentCaptor<CommitModels> requests =
                ArgumentCaptor.forClass(CommitModels.class);
        verify(eventStoreClient, times(2)).commitModels(requests.capture());
        CommitModels retried = requests.getAllValues().getLast();
        assertEquals(2, retried.getSubsteps().size());
        DirectModelUpdate direct = assertInstanceOf(
                DirectModelUpdate.class,
                serializer.deserializeMessage(
                        retried.getSubsteps().getLast().getEvent(),
                        MessageType.EVENT).getPayload());
        assertEquals(
                merged,
                serializer.deserialize(direct.target(id.toString()).getState()));
    }

    @Test
    void includesCompleteModelAliasesOnlyForAliasAwareTypes() throws Exception {
        AliasedId id = new AliasedId("1");
        AliasedModel value = new AliasedModel(
                id, "primary", List.of("secondary", "secondary"));
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(
                        new UpdateAliased(id),
                        transition(
                                id, AliasedModel.class, null, value,
                                UpdateAliased.class, "apply", AliasedModel.class)),
                Map.of(id.toString(), value));

        var target = protocol.prepare("commit-alias", evaluation)
                .commit().getSubsteps().getFirst().getTargets().getFirst();

        assertEquals(
                List.of("code-primary", "alternative-secondary"),
                target.getAliases());

        var deletion = evaluation(
                List.of(id.toString()),
                substep(
                        new UpdateAliased(id),
                        transition(
                                id, AliasedModel.class, value, null,
                                UpdateAliased.class, "apply", AliasedModel.class)),
                Map.of());
        assertEquals(
                List.of(),
                protocol.prepare("delete-alias", deletion)
                        .commit().getSubsteps().getFirst().getTargets().getFirst()
                        .getAliases());

        OrderId orderId = new OrderId("without-alias");
        Order order = new Order(orderId, null, "new", Instant.EPOCH);
        var ordinary = evaluation(
                List.of(orderId.toString()),
                substep(
                        new UpdateOrder(orderId),
                        transition(
                                orderId, Order.class, null, order,
                                UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), order));
        var ordinaryTarget = protocol.prepare("commit-ordinary", ordinary)
                .commit().getSubsteps().getFirst().getTargets().getFirst();
        assertNull(ordinaryTarget.getAliases());
        assertEquals(-1L, ordinaryTarget.getExpectedSequenceNumber());
    }

    @Test
    void commitsOriginalEventOnceWithoutResendingAnUnchangedChildOwnedRelationship() throws Exception {
        OrderId orderId = new OrderId("1");
        CustomerId customerId = new CustomerId("1");
        Order before = new Order(orderId, customerId, "pending", Instant.parse("2026-01-01T00:00:00Z"));
        Order after = new Order(orderId, customerId, "confirmed", Instant.parse("2026-01-02T00:00:00Z"));
        UpdateOrder event = new UpdateOrder(orderId);
        var evaluation = evaluation(
                List.of(orderId.toString(), customerId.toString()),
                substep(
                        Metadata.of("tenant", "north"),
                        event,
                        transition(orderId, Order.class, before, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), after));
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        var result = protocol.commit("commit-1", evaluation).join();

        assertTrue(result.isPresent());
        ArgumentCaptor<CommitModels> captor = ArgumentCaptor.forClass(CommitModels.class);
        verify(eventStoreClient).commitModels(captor.capture());
        CommitModels commit = captor.getValue();
        assertEquals("commit-1", commit.getCommitId());
        assertEquals(List.of(orderId.toString(), customerId.toString()), commit.getReadModelIds());
        assertEquals(1, commit.getSubsteps().size());
        assertEquals(UpdateOrder.class.getName(), commit.getSubsteps().getFirst().getEvent().getType());
        assertEquals("client-1", commit.getSubsteps().getFirst().getEvent().getSource());
        assertTrue(commit.getSubsteps().getFirst().isPublishEvent());
        var target = commit.getSubsteps().getFirst().getTargets().getFirst();
        assertEquals(orderId.toString(), target.getModelId());
        assertEquals(-1L, target.getExpectedSequenceNumber());
        assertTrue(target.isStoreEvent());
        assertNotNull(target.getDocument());
        assertEquals(
                after,
                serializer.fromDocument(
                        target.getDocument().getDocument(),
                        Order.class));
        assertFalse(target.isUpdateRelationships());
        assertTrue(target.getRelationships().isEmpty());
        SerializedDocument document =
                target.getDocument().getDocument();
        assertEquals(after, serializer.fromDocument(document, Order.class));
        assertEquals(
                "orders",
                target.getDocument().getCollection());
        assertEquals(after.changedAt().toEpochMilli(), document.getTimestamp());
        assertEquals(after.changedAt().toEpochMilli(), document.getEnd());
        assertEquals(7, document.getDocument().getRevision());
        assertEquals("north", document.getMetadata().get("tenant"));
    }

    @Test
    void nonSearchableChildWithExplicitPathSuppliesPrivateGraphDocument()
            throws Exception {
        GraphOnlyChildId id =
                new GraphOnlyChildId("1");
        GraphOnlyChild after =
                new GraphOnlyChild(
                        id,
                        new CustomerId("1"),
                        "child");
        var evaluation =
                evaluation(
                        List.of(id.toString()),
                        substep(
                                new UpdateGraphOnlyChild(
                                        id),
                                transition(
                                        id,
                                        GraphOnlyChild.class,
                                        null, after,
                                        UpdateGraphOnlyChild.class,
                                        "apply",
                                        GraphOnlyChild.class)),
                        Map.of(id.toString(), after));
        when(eventStoreClient.commitModels(
                any())).thenAnswer(invocation ->
                                           CompletableFuture.completedFuture(
                                                   result(
                                                           invocation.getArgument(
                                                                   0))));

        protocol.commit(
                        "graph-component",
                        evaluation)
                .join();

        ArgumentCaptor<CommitModels> commit =
                ArgumentCaptor.forClass(
                        CommitModels.class);
        verify(eventStoreClient)
                .commitModels(
                        commit.capture());
        var document =
                commit.getValue()
                        .getSubsteps().getFirst()
                        .getTargets().getFirst()
                        .getDocument();
        assertNotNull(document);
        assertEquals(
                ModelDocumentMutation
                        .GRAPH_COMPONENT_COLLECTION,
                document.getCollection());
        assertEquals(
                after,
                serializer.fromDocument(
                        document.getDocument(),
                        GraphOnlyChild.class));
    }

    @Test
    void nonSearchableModelWithoutExplicitPathKeepsDocumentFreeFastPath()
            throws Exception {
        CustomerId id =
                new CustomerId("document-free");
        Customer after =
                new Customer(id);
        var evaluation =
                evaluation(
                        List.of(id.toString()),
                        substep(
                                new UpdateCustomer(id),
                                transition(
                                        id, Customer.class,
                                        null, after,
                                        UpdateCustomer.class,
                                        "apply",
                                        Customer.class)),
                        Map.of(id.toString(), after));
        when(eventStoreClient.commitModels(
                any())).thenAnswer(invocation ->
                                           CompletableFuture.completedFuture(
                                                   result(
                                                           invocation.getArgument(
                                                                   0))));

        protocol.commit(
                        "document-free",
                        evaluation)
                .join();

        ArgumentCaptor<CommitModels> commit =
                ArgumentCaptor.forClass(
                        CommitModels.class);
        verify(eventStoreClient)
                .commitModels(
                        commit.capture());
        assertNull(
                commit.getValue()
                        .getSubsteps().getFirst()
                        .getTargets().getFirst()
                        .getDocument());
    }

    @Test
    void includesTheCompleteRelationshipReplacementWhenAParentChanges() throws Exception {
        OrderId orderId = new OrderId("1");
        CustomerId previousCustomer = new CustomerId("1");
        CustomerId nextCustomer = new CustomerId("2");
        Order before = new Order(
                orderId, previousCustomer, "pending",
                Instant.parse("2026-01-01T00:00:00Z"));
        Order after = new Order(
                orderId, nextCustomer, "confirmed",
                Instant.parse("2026-01-02T00:00:00Z"));
        var evaluation = evaluation(
                List.of(
                        orderId.toString(),
                        previousCustomer.toString(),
                        nextCustomer.toString()),
                substep(
                        new UpdateOrder(orderId),
                        transition(
                                orderId, Order.class, before, after,
                                UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), after));
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation ->
                                    CompletableFuture.completedFuture(
                                            result(invocation.getArgument(0))));
        protocol.commit("move-order", evaluation).join();

        ArgumentCaptor<CommitModels> captor =
                ArgumentCaptor.forClass(CommitModels.class);
        verify(eventStoreClient).commitModels(captor.capture());
        var target = captor.getValue().getSubsteps()
                .getFirst().getTargets().getFirst();
        assertTrue(target.isUpdateRelationships());
        assertEquals(1, target.getRelationships().size());
        assertEquals(
                nextCustomer.toString(),
                target.getRelationships().getFirst().getParentId());
        assertEquals(
                Customer.class.getName(),
                target.getRelationships().getFirst().getParentType());
        assertEquals(
                "orders",
                target.getRelationships().getFirst().getPath());
    }

    @Test
    void selectsTheConcreteParentTypeForAPolymorphicRelationship() {
        AlternateCustomerId parentId = new AlternateCustomerId("alternate");
        PolymorphicContact after = new PolymorphicContact("contact-1", parentId);
        var evaluation = evaluation(
                List.of("contact-1", parentId.toString()),
                substep(new CreatePolymorphicContact(),
                        Change.applied(
                                "contact-1", PolymorphicContact.class, -1L,
                                null, null, after, null,
                                null, false)),
                Map.of("contact-1", after));

        CommitModels commit = protocol.prepare("polymorphic-parent", evaluation).commit();

        var target = commit.getSubsteps().stream()
                .flatMap(step -> step.getTargets().stream())
                .filter(candidate -> "contact-1".equals(candidate.getModelId()))
                .findFirst().orElseThrow();
        assertEquals(1, target.getRelationships().size());
        assertEquals(parentId.toString(), target.getRelationships().getFirst().getParentId());
        assertEquals(AlternateCustomer.class.getName(),
                     target.getRelationships().getFirst().getParentType());
        assertEquals("contacts", target.getRelationships().getFirst().getPath());
    }

    @Test
    void rejectedCommitDoesNotWriteDirectSearchDocuments() throws Exception {
        OrderId orderId = new OrderId("1");
        Order before = new Order(orderId, null, "pending", Instant.parse("2026-01-01T00:00:00Z"));
        Order after = new Order(orderId, null, "confirmed", Instant.parse("2026-01-02T00:00:00Z"));
        var evaluation = evaluation(
                List.of(orderId.toString()),
                substep(
                        new UpdateOrder(orderId),
                        transition(orderId, Order.class, before, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), after));
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels request = invocation.getArgument(0);
            return CompletableFuture.completedFuture(conflict(request, false));
        });

        var result = protocol.commit(
                "commit-1", evaluation, ModelConflictPolicy.FAIL).join();

        assertFalse(result.orElseThrow().isAccepted());
        ArgumentCaptor<CommitModels> captor = ArgumentCaptor.forClass(CommitModels.class);
        verify(eventStoreClient).commitModels(captor.capture());
        assertEquals(ModelConflictPolicy.FAIL, captor.getValue().getConflictPolicy());
    }

    @Test
    void relationSafeConflictCanReloadAndRetryWithinTheConfiguredBound() throws Exception {
        OrderId orderId = new OrderId("1");
        Order before = new Order(orderId, null, "pending", Instant.parse("2026-01-01T00:00:00Z"));
        Order first = new Order(orderId, null, "confirmed", Instant.parse("2026-01-02T00:00:00Z"));
        Order reloaded = new Order(orderId, null, "confirmed", Instant.parse("2026-01-03T00:00:00Z"));
        var firstEvaluation = evaluation(
                List.of(orderId.toString()),
                substep(
                        new UpdateOrder(orderId),
                        transition(orderId, Order.class, before, first, UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), first));
        var reloadedEvaluation = evaluation(
                42L, firstEvaluation.readModelIds(),
                firstEvaluation.readModelTypes(),
                List.of(substep(
                        new UpdateOrder(orderId),
                        transition(
                                orderId, Order.class, before, reloaded,
                                UpdateOrder.class, "apply", Order.class))),
                Map.of(orderId.toString(), reloaded));
        AtomicInteger commits = new AtomicInteger();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels request = invocation.getArgument(0);
            return CompletableFuture.completedFuture(
                    commits.getAndIncrement() == 0 ? conflict(request, true) : result(request));
        });
        AtomicInteger reloads = new AtomicInteger();

        var result = ModelPipeline.commit(
                protocol, "commit-1", firstEvaluation,
                ModelConflictPolicy.RETRY,
                ModelPipeline.Retry.conflicts(
                        ModelConflictResolver.retryIfAllowed(), 1,
                        (conflict, current) -> {
                    reloads.incrementAndGet();
                    return CompletableFuture.completedFuture(reloadedEvaluation);
                }), null, -1).join();

        assertTrue(result.orElseThrow().isAccepted());
        assertEquals(2, commits.get());
        assertEquals(1, reloads.get());
        ArgumentCaptor<CommitModels> updates =
                ArgumentCaptor.forClass(
                        CommitModels.class);
        verify(eventStoreClient, times(2))
                .commitModels(
                        updates.capture());
        var update =
                updates.getAllValues().getLast()
                        .getSubsteps().getFirst()
                        .getTargets().getFirst()
                        .getDocument().getDocument();
        assertEquals(reloaded, serializer.fromDocument(
                update, Order.class));
    }

    @Test
    void silentRetryIsBoundedAndCanBeMappedToAnApplicationError() throws Exception {
        OrderId orderId = new OrderId("1");
        Order order = new Order(orderId, null, "pending", Instant.parse("2026-01-01T00:00:00Z"));
        Order updated = new Order(orderId, null, "updated", Instant.parse("2026-01-02T00:00:00Z"));
        var evaluation = evaluation(
                List.of(orderId.toString()),
                substep(
                        new UpdateOrder(orderId),
                        transition(orderId, Order.class, order, updated, UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), updated));
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels request = invocation.getArgument(0);
            return CompletableFuture.completedFuture(conflict(request, true));
        });
        AtomicInteger reloads = new AtomicInteger();

        CompletionException bounded = assertThrows(CompletionException.class, () -> ModelPipeline.commit(
                protocol, "commit-1", evaluation,
                ModelConflictPolicy.RETRY,
                ModelPipeline.Retry.conflicts(
                        ModelConflictResolver.retryIfAllowed(), 1,
                        (conflict, current) -> {
                    reloads.incrementAndGet();
                    return CompletableFuture.completedFuture(evaluation);
                }), null, -1).join());

        assertInstanceOf(ModelCommitConflictException.class, bounded.getCause());
        assertEquals(1, reloads.get());
        verify(eventStoreClient, times(2)).commitModels(any());

        IllegalStateException applicationError = new IllegalStateException("try again later");
        CompletionException mapped = assertThrows(CompletionException.class, () -> ModelPipeline.commit(
                protocol, "commit-2", evaluation, ModelConflictPolicy.FAIL,
                ModelPipeline.Retry.conflicts(
                        ignored -> {
                            throw applicationError;
                        }, 0,
                        (conflict, current) ->
                                CompletableFuture.completedFuture(evaluation)),
                null, -1).join());
        assertEquals(applicationError, mapped.getCause());
    }

    @Test
    void combinesPerTargetPublicationWithoutDuplicatingTheOriginalEvent() throws Exception {
        StoredOnlyId storedId = new StoredOnlyId("1");
        PublishedOnlyId publishedId = new PublishedOnlyId("1");
        StoredOnly stored = new StoredOnly(storedId);
        PublishedOnly published = new PublishedOnly(publishedId);
        var evaluation = evaluation(
                List.of(storedId.toString(), publishedId.toString()),
                substep(
                        new MixedUpdate(storedId, publishedId),
                        transition(
                                storedId, StoredOnly.class, stored, stored,
                                MixedUpdate.class, "apply", StoredOnly.class),
                        transition(
                                publishedId, PublishedOnly.class, published, published,
                                MixedUpdate.class, "apply", PublishedOnly.class)),
                Map.of(storedId.toString(), stored, publishedId.toString(), published));
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));

        protocol.commit("mixed-commit", evaluation).join();

        ArgumentCaptor<CommitModels> captor = ArgumentCaptor.forClass(CommitModels.class);
        verify(eventStoreClient).commitModels(captor.capture());
        var substep = captor.getValue().getSubsteps().getFirst();
        assertTrue(substep.isPublishEvent());
        assertEquals(MixedUpdate.class.getName(), substep.getEvent().getType());
        assertEquals(2, substep.getTargets().size());
        assertTrue(substep.getTargets().getFirst().isStoreEvent());
        assertFalse(substep.getTargets().getLast().isStoreEvent());
        assertFalse(
                substep.getTargets().getLast()
                        .isUpdateState());
    }

    @Test
    void rejectsCreateUpdateAndDeleteForPublishOnlyEventSourcedModelsBeforeCommit()
            throws Exception {
        UnsafePublishedId id =
                new UnsafePublishedId("1");
        UnsafePublished before =
                new UnsafePublished(id, "before");
        UnsafePublished after =
                new UnsafePublished(id, "after");
        Object[][] stateChanges = {
                {null, after},
                {before, after},
                {before, null}
        };
        for (int i = 0; i < stateChanges.length; i++) {
            String commitId =
                    "unsafe-publish-only-" + i;
            Object next = stateChanges[i][1];
            var evaluation =
                    evaluation(
                            List.of(id.toString()),
                            substep(
                                    new UpdateUnsafePublished(
                                            id),
                                    transition(
                                            id,
                                            UnsafePublished.class,
                                            stateChanges[i][0],
                                            next,
                                            UpdateUnsafePublished.class,
                                            "apply",
                                            UnsafePublished.class)),
                            java.util.Collections.singletonMap(
                                    id.toString(), next));

            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> protocol.commit(
                                    commitId,
                                    evaluation));

            assertTrue(
                    failure.getMessage()
                            .contains(
                                    "without storing its reconstructing event"));
        }
        verify(eventStoreClient, never())
                .commitModels(any());
    }

    @Test
    void successfulCommitWaitsForTheAuthoritativeStore() throws Exception {
        OrderId id = new OrderId("1");
        Order after = new Order(
                id, null, "confirmed", Instant.parse("2026-01-02T00:00:00Z"));
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new UpdateOrder(id), transition(
                        id, Order.class, null, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(id.toString(), after));
        CompletableFuture<CommitModelsResult> stored =
                new CompletableFuture<>();
        when(eventStoreClient.commitModels(any()))
                .thenReturn(stored);

        var completion = protocol.commit("commit-1", evaluation);

        assertFalse(completion.isDone());
        ArgumentCaptor<CommitModels> request =
                ArgumentCaptor.forClass(
                        CommitModels.class);
        verify(eventStoreClient)
                .commitModels(request.capture());
        stored.complete(
                result(request.getValue()));
        assertTrue(completion.join().isPresent());
    }

    @Test
    void batchedCommitCompletionAppliesOneRequestOwnedPostCommitGroup() throws Exception {
        OrderId id = new OrderId("batch-completion");
        Order after = new Order(
                id, null, "confirmed", Instant.parse("2026-01-02T00:00:00Z"));
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new UpdateOrder(id), transition(
                        id, Order.class, null, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(id.toString(), after));
        Commit batchProtocol = repository.new Commit(
                eventStoreClient, serializer, serializer,
                DispatchInterceptor.noOp, "client-1", serializer,
                GraphProjectionCompletion.ASYNC);
        Commit.Outcome prepared =
                batchProtocol.prepare("batch-completion", evaluation);
        CompletableFuture<CommitModelsResult> transport = new CompletableFuture<>();
        ModelCommitBatchingClient.ModelCommitBatch batch = new ModelCommitBatchingClient.ModelCommitBatch() {
            @Override
            public CompletableFuture<CommitModelsResult> add(int slot, CommitModels commit) {
                throw new AssertionError("Missing request-owned completion context");
            }

            @Override
            public CompletableFuture<CommitModelsResult> add(
                    int slot,
                    CommitModels commit,
                    ModelCommitBatchingClient.ModelCommitCompletion completion) {
                return transport.thenCompose(result -> completion.processor()
                        .process(List.of(result), List.of(completion.value()))
                        .thenApply(ignored -> result));
            }

            @Override
            public void skip(int slot) {
            }

            @Override
            public void flush() {
            }

            @Override
            public void fail(Throwable failure) {
                transport.completeExceptionally(failure);
            }
        };

        CompletableFuture<Optional<CommitModelsResult>> completion =
                batchProtocol.commitPrepared(prepared, batch, 0);
        CommitModelsResult result = result(prepared.commit());
        transport.complete(result);

        assertSame(result, completion.join().orElseThrow());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Commit.Outcome>> outcomes =
                ArgumentCaptor.forClass(List.class);
        verify(repository).updateAfterCommit(outcomes.capture());
        assertEquals(1, outcomes.getValue().size());
        assertSame(prepared.commit(), outcomes.getValue().getFirst().commit());
        assertSame(result, outcomes.getValue().getFirst().result());
    }

    @Test
    void alwaysPublishedStoredTransitionDoesNotCompareModelState()
            throws Exception {
        EqualsProbeModel.equalsCalls.set(0);
        EqualsProbeId id = new EqualsProbeId("always");
        EqualsProbeModel before =
                new EqualsProbeModel(id, "before");
        EqualsProbeModel after =
                new EqualsProbeModel(id, "after");
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new UpdateEqualsProbe(id), transition(
                        id, EqualsProbeModel.class,
                        before, after,
                        UpdateEqualsProbe.class, "apply",
                        EqualsProbeModel.class)),
                Map.of(id.toString(), after));
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation ->
                                    CompletableFuture.completedFuture(
                                            result(invocation.getArgument(0))));

        assertTrue(protocol.commit("always", evaluation)
                           .join().isPresent());
        assertEquals(0, EqualsProbeModel.equalsCalls.get());
    }

    @Test
    void conditionalPublicationStillComparesModelState()
            throws Exception {
        EqualsProbeModel.equalsCalls.set(0);
        EqualsProbeId id = new EqualsProbeId("conditional");
        EqualsProbeModel before =
                new EqualsProbeModel(id, "same");
        EqualsProbeModel after =
                new EqualsProbeModel(id, "same");
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new TouchEqualsProbe(id), transition(
                        id, EqualsProbeModel.class,
                        before, after,
                        TouchEqualsProbe.class, "apply",
                        EqualsProbeModel.class)),
                Map.of(id.toString(), after));

        assertTrue(protocol.commit("conditional", evaluation)
                           .join().isEmpty());
        assertEquals(1, EqualsProbeModel.equalsCalls.get());
        verify(eventStoreClient, never()).commitModels(any());
    }

    @Test
    void acceptKeepsTheOriginalEventAndRebasesItsDerivedDocument() throws Exception {
        OrderId id = new OrderId("rebase");
        Order stale = new Order(
                id, null, "stale",
                Instant.parse("2026-01-01T00:00:00Z"));
        Order merged = new Order(
                id, null, "merged",
                Instant.parse("2026-01-03T00:00:00Z"));
        UpdateOrder event = new UpdateOrder(id);
        var original = evaluation(
                List.of(id.toString()),
                substep(event, transition(
                        id, Order.class, null, stale,
                        UpdateOrder.class, "apply",
                        Order.class)),
                Map.of(id.toString(), stale));
        var rebased = evaluation(
                51L, List.of(id.toString()),
                Map.of(id.toString(), Order.class),
                List.of(substep(event, transition(
                        id, Order.class, stale, merged,
                        UpdateOrder.class, "apply",
                        Order.class))),
                Map.of(id.toString(), merged));
        AtomicInteger commits = new AtomicInteger();
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation -> {
                    CommitModels request =
                            invocation.getArgument(0);
                    if (commits.getAndIncrement() == 0) {
                        return CompletableFuture.completedFuture(
                                CommitModelsResult.rebase(
                                        request.getRequestId(),
                                        request.getCommitId(),
                                        List.of(
                                                new ModelCommitConflict(
                                                        id.toString(),
                                                        51L, -1L)),
                                        51L));
                    }
                    return CompletableFuture.completedFuture(
                            result(request));
                });

        var accepted = ModelPipeline.commit(
                protocol, "commit-rebase", original, ModelConflictPolicy.ACCEPT,
                ModelPipeline.Retry.accepting((result, current) -> {
                    assertEquals(51L, result.getRebaseStateIndex());
                    assertEquals(1, current.steps().size());
                    return CompletableFuture.completedFuture(
                            rebased);
                }), null, -1).join();

        assertTrue(accepted.orElseThrow().isAccepted());
        ArgumentCaptor<CommitModels> requests =
                ArgumentCaptor.forClass(
                        CommitModels.class);
        verify(eventStoreClient, times(2))
                .commitModels(requests.capture());
        CommitModels initial =
                requests.getAllValues().getFirst();
        CommitModels retried =
                requests.getAllValues().getLast();
        assertEquals(
                initial.getSubsteps().getFirst().getEvent()
                        .getData(),
                retried.getSubsteps().getFirst().getEvent()
                        .getData());
        assertEquals(51L, retried.getReadStateIndex());
        assertEquals(
                merged,
                serializer.fromDocument(
                        retried.getSubsteps().getFirst()
                                .getTargets().getFirst()
                                .getDocument().getDocument(),
                        Order.class));
    }

    @Test
    void acceptRebaseDoesNotLoadModelsOnTheCommitResultCallback() throws Exception {
        OrderId id = new OrderId("callback-rebase");
        Order stale = new Order(
                id, null, "stale",
                Instant.parse("2026-01-01T00:00:00Z"));
        Order merged = new Order(
                id, null, "merged",
                Instant.parse("2026-01-03T00:00:00Z"));
        UpdateOrder event = new UpdateOrder(id);
        var original = evaluation(
                List.of(id.toString()),
                substep(event, transition(
                        id, Order.class, null, stale,
                        UpdateOrder.class, "apply",
                        Order.class)),
                Map.of(id.toString(), stale));
        var rebased = evaluation(
                51L, List.of(id.toString()),
                Map.of(id.toString(), Order.class),
                List.of(substep(event, transition(
                        id, Order.class, stale, merged,
                        UpdateOrder.class, "apply",
                        Order.class))),
                Map.of(id.toString(), merged));
        CompletableFuture<CommitModelsResult> firstCommit =
                new CompletableFuture<>();
        AtomicReference<CommitModels> firstRequest =
                new AtomicReference<>();
        AtomicInteger commits = new AtomicInteger();
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation -> {
                    CommitModels request =
                            invocation.getArgument(0);
                    if (commits.getAndIncrement() == 0) {
                        firstRequest.set(request);
                        return firstCommit;
                    }
                    return CompletableFuture.completedFuture(
                            result(request));
                });
        AtomicReference<String> completionThread =
                new AtomicReference<>();
        AtomicReference<String> rebaseThread =
                new AtomicReference<>();
        Fluxzero expectedContext =
                mock(Fluxzero.class);

        CompletableFuture<Optional<CommitModelsResult>> completion;
        Fluxzero.instance.set(
                expectedContext);
        try {
            completion = ModelPipeline.commit(
                            protocol, "callback-rebase", original,
                            ModelConflictPolicy.ACCEPT,
                            ModelPipeline.Retry.accepting((result, current) -> {
                                assertSame(
                                        expectedContext,
                                        Fluxzero.instance.get());
                                rebaseThread.set(
                                        Thread.currentThread()
                                                .getName());
                                return CompletableFuture.completedFuture(
                                        rebased);
                            }), null, -1);
        } finally {
            Fluxzero.instance.remove();
        }
        Thread transportCallback =
                new Thread(() -> {
                    completionThread.set(
                            Thread.currentThread()
                                    .getName());
                    CommitModels request =
                            firstRequest.get();
                    firstCommit.complete(
                            CommitModelsResult.rebase(
                                    request.getRequestId(),
                                    request.getCommitId(),
                                    List.of(
                                            new ModelCommitConflict(
                                                    id.toString(),
                                                    51L, -1L)),
                                    51L));
                }, "serialized-transport-result");
        transportCallback.start();
        transportCallback.join();

        assertTrue(completion.orTimeout(
                5, java.util.concurrent.TimeUnit.SECONDS)
                           .join().orElseThrow()
                           .isAccepted());
        assertNotEquals(
                completionThread.get(),
                rebaseThread.get());
    }

    @Test
    void nullApplyStillStoresEventAndDeletesDirectDocument() throws Exception {
        OrderId id = new OrderId("1");
        Order before = new Order(id, null, "pending", Instant.EPOCH);
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new DeleteOrder(id), transition(
                        id, Order.class, before, null, DeleteOrder.class, "apply", Order.class)),
                java.util.Collections.singletonMap(id.toString(), null));
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        protocol.commit("commit-1", evaluation).join();

        ArgumentCaptor<CommitModels> captor = ArgumentCaptor.forClass(CommitModels.class);
        verify(eventStoreClient).commitModels(captor.capture());
        var substep = captor.getValue().getSubsteps().getFirst();
        assertTrue(substep.isPublishEvent());
        assertTrue(substep.getTargets().getFirst().isStoreEvent());
        assertTrue(substep.getTargets().getFirst().isDelete());
        assertTrue(substep.getTargets().getFirst().isUpdateRelationships());
        assertNotNull(substep.getTargets().getFirst().getDocument());
        assertNull(substep.getTargets().getFirst().getDocument().getDocument());
        assertTrue(substep.getTargets().getFirst().getRelationships().isEmpty());
        assertEquals(
                "orders",
                substep.getTargets().getFirst()
                        .getDocument().getCollection());
        assertNull(
                substep.getTargets().getFirst()
                        .getDocument().getDocument());
    }

    @Test
    void neverPublicationUpdatesStateAndDirectDocumentWithoutCreatingEvent() throws Exception {
        PrivateDocumentId id = new PrivateDocumentId("1");
        PrivateDocument after = new PrivateDocument(id, "secret");
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new UpdatePrivateDocument(id), transition(
                        id, PrivateDocument.class, null, after,
                        UpdatePrivateDocument.class, "apply", PrivateDocument.class)),
                Map.of(id.toString(), after));
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        protocol.commit("commit-1", evaluation).join();

        ArgumentCaptor<CommitModels> captor = ArgumentCaptor.forClass(CommitModels.class);
        verify(eventStoreClient).commitModels(captor.capture());
        var substep = captor.getValue().getSubsteps().getFirst();
        assertNull(substep.getEvent());
        assertFalse(substep.isPublishEvent());
        assertFalse(substep.getTargets().getFirst().isStoreEvent());
        assertTrue(substep.getTargets().getFirst().isUpdateState());
        var update =
                substep.getTargets().getFirst()
                        .getDocument();
        assertEquals(after, serializer.fromDocument(
                update.getDocument(),
                PrivateDocument.class));
        assertEquals(
                "privateDocuments",
                update.getCollection());
    }

    @Test
    void ifModifiedNoOpDoesNotCreateStateIndexOrRewriteDocument() throws Exception {
        ConditionalId id = new ConditionalId("1");
        ConditionalModel value = new ConditionalModel(id, "same");
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new TouchConditional(id), transition(
                        id, ConditionalModel.class, value, value,
                        TouchConditional.class, "apply", ConditionalModel.class)),
                Map.of(id.toString(), value));

        var result = protocol.commit("commit-1", evaluation).join();

        assertTrue(result.isEmpty());
        verify(eventStoreClient, never()).commitModels(any());
    }

    @Test
    void documentSerializationFailureHappensBeforeTheAuthoritativeCommit() throws Exception {
        OrderId id = new OrderId("invalid-document");
        Order after = new Order(id, null, "confirmed", Instant.EPOCH);
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new UpdateOrder(id), transition(
                        id, Order.class, null, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(id.toString(), after));
        DocumentSerializer failingSerializer = mock(DocumentSerializer.class);
        when(failingSerializer.toDocument(any(), any(), any(), any(), any(), any()))
                .thenThrow(new MockSearchFailure());
        Commit failingProtocol = repository.new Commit(
                eventStoreClient, serializer, failingSerializer,
                DispatchInterceptor.noOp, "client-1", serializer,
                GraphProjectionCompletion.ASYNC);

        assertThrows(MockSearchFailure.class, () -> failingProtocol.commit("commit-1", evaluation));

        verify(eventStoreClient, never()).commitModels(any());
    }

    @Test
    void embedsSnapshotOnlyWhenAssignedSequenceIsDue() throws Exception {
        SnapshotId id = new SnapshotId("due");
        SnapshotModel after = new SnapshotModel(
                id, "second");
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(
                        new UpdateSnapshot(id),
                        transition(
                                id, SnapshotModel.class,
                                0L,
                                new SnapshotModel(id, "first"),
                                after,
                                UpdateSnapshot.class, "apply",
                                SnapshotModel.class)),
                Map.of(id.toString(), after));

        CommitModels commit = protocol.prepare(
                "commit-snapshot", evaluation).commit();

        var snapshot = commit.getSubsteps().getFirst()
                .getTargets().getFirst().getSnapshot();
        assertNotNull(snapshot);
        assertEquals(2, snapshot.getSnapshotPeriod());
        assertEquals(3, snapshot.getMaxSnapshotCount());
        assertEquals(after, serializer.deserialize(
                snapshot.getValue()));
        assertEquals(1, commit.toMetric().getSnapshotCount());

        var nextEvaluation = evaluation(
                List.of(id.toString()),
                substep(
                        new UpdateSnapshot(id),
                        transition(
                                id, SnapshotModel.class,
                                1L, after,
                                new SnapshotModel(id, "third"),
                                UpdateSnapshot.class, "apply",
                                SnapshotModel.class)),
                Map.of(id.toString(),
                       new SnapshotModel(id, "third")));
        assertNull(protocol.prepare(
                        "commit-no-snapshot",
                        nextEvaluation)
                           .commit().getSubsteps().getFirst()
                           .getTargets().getFirst().getSnapshot());
    }

    private static CommitAttempt evaluation(
            List<String> readModelIds,
            TestStep substep,
            Map<String, Object> finalValues) {
        return evaluation(readModelIds, List.of(substep), finalValues);
    }

    private static CommitAttempt evaluation(
            List<String> readModelIds,
            List<TestStep> substeps,
            Map<String, Object> finalValues) {
        return evaluation(
                41L, readModelIds,
                finalValues.entrySet().stream()
                        .filter(entry ->
                                        entry.getValue() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue()
                                        .getClass())),
                substeps, finalValues);
    }

    private static CommitAttempt evaluation(
            long readStateIndex,
            List<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            List<TestStep> substeps,
            Map<String, Object> finalValues) {
        return CommitAttempt.fromSteps(
                readStateIndex, readModelIds, readModelTypes,
                substeps.stream().map(step -> new CommitAttempt.Step(
                        step.message(), step.transitions())).toList());
    }

    private static TestStep substep(
            Object event, Change... transitions) {
        return substep(Metadata.empty(), event, transitions);
    }

    private static TestStep substep(
            Metadata metadata, Object event, Change... transitions) {
        return new TestStep(
                new DeserializingMessage(new Message(event, metadata), MessageType.EVENT, null),
                List.of(transitions));
    }

    private record TestStep(
            DeserializingMessage message,
            List<Change> transitions) {
    }

    private static Change transition(
            Object id, Class<?> modelType, Object before, Object after,
            Class<?> handlerType, String methodName, Class<?> parameterType) throws Exception {
        return transition(
                id, modelType, -1L, before, after,
                handlerType, methodName, parameterType);
    }

    private static Change transition(
            Object id, Class<?> modelType, long beforeSequenceNumber,
            Object before, Object after,
            Class<?> handlerType, String methodName,
            Class<?> parameterType) throws Exception {
        Method handler = handlerType.getDeclaredMethod(methodName, parameterType);
        return Change.applied(
                id.toString(), modelType, beforeSequenceNumber,
                null, before, after, handler,
                null, false);
    }

    private static CommitModelsResult result(CommitModels request) {
        List<ModelUpdate> updates = new java.util.ArrayList<>();
        for (int i = 0; i < request.getSubsteps().size(); i++) {
            var substep = request.getSubsteps().get(i);
            updates.add(new ModelUpdate(
                    ModelUpdateKind.COMMIT, request.getCommitId(), i,
                    42L, substep.isPublishEvent() ? 100L : null,
                    substep.getTargets().stream().map(target ->
                            new ModelCommitTargetResult(
                                    target.getModelId(), target.isStoreEvent() ? 0L : -1L,
                                    target.isStoreEvent())).toList()));
        }
        return CommitModelsResult.accepted(
                request.getRequestId(), request.getCommitId(), updates);
    }

    private static CommitModelsResult conflict(
            CommitModels request, boolean retryAllowed) {
        return CommitModelsResult.conflict(
                request.getRequestId(), request.getCommitId(),
                List.of(new ModelCommitConflict(
                        request.getReadModelIds().getFirst(),
                        request.getReadStateIndex() + 1L,
                        request.getReadStateIndex())),
                retryAllowed);
    }

    @Revision(7)
    @Model(eventSourced = false, searchable = true,
            searchProjection = @Searchable(collection = "orders", timestampPath = "changedAt"))
    private record Order(
            @EntityId OrderId orderId,
            @Parent(value = Customer.class, path = "orders") CustomerId customerId,
            String status,
            Instant changedAt) {
    }

    private static class OrderId extends Id<Order> {
        OrderId(String id) {
            super(id, "order-");
        }
    }

    @Model
    private record Customer(@EntityId CustomerId customerId) {
    }

    private static class CustomerId extends Id<Customer> {
        CustomerId(String id) {
            super(id, "customer-");
        }
    }

    @Model
    private record AlternateCustomer(@EntityId AlternateCustomerId customerId) {
    }

    private static class AlternateCustomerId extends Id<AlternateCustomer> {
        AlternateCustomerId(String id) {
            super(id, "alternate-customer-");
        }
    }

    @Model(eventSourced = false)
    private record PolymorphicContact(
            @EntityId String id,
            @Parent(types = {Customer.class, AlternateCustomer.class}, path = "contacts") Id<?> parentId) {
    }

    private record CreatePolymorphicContact() {
    }

    private record UpdateCustomer(
            CustomerId customerId) {
        @Apply
        Customer apply(Customer current) {
            return current;
        }
    }

    @Model
    private record GraphOnlyChild(
            @EntityId GraphOnlyChildId childId,
            @Parent(path = "children")
            CustomerId customerId,
            String value) {
    }

    private static class GraphOnlyChildId
            extends Id<GraphOnlyChild> {
        GraphOnlyChildId(String id) {
            super(id, "graph-child-");
        }
    }

    private record UpdateGraphOnlyChild(
            GraphOnlyChildId childId) {
        @Apply
        GraphOnlyChild apply(
                GraphOnlyChild current) {
            return current;
        }
    }

    private record UpdateOrder(OrderId orderId) {
        @Apply
        Order apply(Order current) {
            return current;
        }
    }

    private record DeleteOrder(OrderId orderId) {
        @Apply
        Order apply(Order current) {
            return null;
        }
    }

    @Model(
            eventSourced = false,
            searchable = true,
            searchProjection = @Searchable(collection = "privateDocuments"),
            eventPublication = EventPublication.NEVER)
    private record PrivateDocument(@EntityId PrivateDocumentId documentId, String value) {
    }

    private static class PrivateDocumentId extends Id<PrivateDocument> {
        PrivateDocumentId(String id) {
            super(id, "private-");
        }
    }

    private record UpdatePrivateDocument(PrivateDocumentId documentId) {
        @Apply
        PrivateDocument apply(PrivateDocument current) {
            return current;
        }
    }

    @Model(eventSourced = false, searchable = true)
    private record ConditionalModel(@EntityId ConditionalId conditionalId, String value) {
    }

    private static class ConditionalId extends Id<ConditionalModel> {
        ConditionalId(String id) {
            super(id, "conditional-");
        }
    }

    private record TouchConditional(ConditionalId conditionalId) {
        @Apply
        ConditionalModel apply(ConditionalModel current) {
            return current;
        }
    }

    @Model
    private static final class EqualsProbeModel {
        private static final AtomicInteger equalsCalls =
                new AtomicInteger();

        @EntityId
        private final EqualsProbeId id;
        private final String value;

        private EqualsProbeModel(
                EqualsProbeId id, String value) {
            this.id = id;
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            equalsCalls.incrementAndGet();
            return other instanceof EqualsProbeModel that
                   && id.equals(that.id)
                   && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return 31 * id.hashCode() + value.hashCode();
        }
    }

    private static class EqualsProbeId
            extends Id<EqualsProbeModel> {
        EqualsProbeId(String id) {
            super(id, "equals-probe-");
        }
    }

    private record UpdateEqualsProbe(EqualsProbeId id) {
        @Apply(eventPublication = EventPublication.ALWAYS)
        EqualsProbeModel apply(EqualsProbeModel current) {
            return current;
        }
    }

    private record TouchEqualsProbe(EqualsProbeId id) {
        @Apply
        EqualsProbeModel apply(EqualsProbeModel current) {
            return current;
        }
    }

    @Model(snapshotPeriod = 2, maxSnapshotCount = 3)
    private record SnapshotModel(
            @EntityId SnapshotId id, String value) {
    }

    private static class SnapshotId
            extends Id<SnapshotModel> {
        SnapshotId(String id) {
            super(id, "snapshot-");
        }
    }

    private record UpdateSnapshot(SnapshotId id) {
        @Apply
        SnapshotModel apply(
                SnapshotModel current) {
            return current;
        }
    }

    @Model
    private record AliasedModel(
            @EntityId AliasedId id,
            @Alias(prefix = "code-") String code,
            @Alias(prefix = "alternative-") List<String> alternatives) {
    }

    private static class AliasedId extends Id<AliasedModel> {
        AliasedId(String id) {
            super(id, "aliased-");
        }
    }

    private record UpdateAliased(AliasedId id) {
        @Apply
        AliasedModel apply(AliasedModel current) {
            return current;
        }
    }

    @Model(publicationStrategy = EventPublicationStrategy.STORE_ONLY)
    private record StoredOnly(@EntityId StoredOnlyId id) {
    }

    private static class StoredOnlyId extends Id<StoredOnly> {
        StoredOnlyId(String id) {
            super(id, "stored-");
        }
    }

    @Model(publicationStrategy = EventPublicationStrategy.PUBLISH_ONLY)
    private record PublishedOnly(@EntityId PublishedOnlyId id) {
    }

    @Model(publicationStrategy = EventPublicationStrategy.PUBLISH_ONLY)
    private record UnsafePublished(
            @EntityId UnsafePublishedId id,
            String value) {
    }

    private static class UnsafePublishedId
            extends Id<UnsafePublished> {
        UnsafePublishedId(String id) {
            super(id, "unsafe-published-");
        }
    }

    private record UpdateUnsafePublished(
            UnsafePublishedId id) {
        @Apply
        UnsafePublished apply(
                UnsafePublished current) {
            return current;
        }
    }

    private static class PublishedOnlyId extends Id<PublishedOnly> {
        PublishedOnlyId(String id) {
            super(id, "published-");
        }
    }

    private record MixedUpdate(StoredOnlyId storedId, PublishedOnlyId publishedId) {
        @Apply(eventPublication = EventPublication.ALWAYS)
        StoredOnly apply(StoredOnly current) {
            return current;
        }

        @Apply(eventPublication = EventPublication.ALWAYS)
        PublishedOnly apply(PublishedOnly current) {
            return current;
        }
    }

    private record RemoveStoredOnly(StoredOnlyId id) {
    }

    private static class MockSearchFailure extends RuntimeException {
    }
}
