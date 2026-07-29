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
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.ModelActionConflict;
import io.fluxzero.common.api.modeling.ModelActionSubstepResult;
import io.fluxzero.common.api.modeling.ModelActionTargetResult;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
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

class ModelActionCommitterTest {

    private final EventStoreClient eventStoreClient = mock(EventStoreClient.class);
    private final JacksonSerializer serializer = new JacksonSerializer();
    private final ModelActionCommitter committer = new ModelActionCommitter(
            eventStoreClient, serializer, serializer,
            DispatchInterceptor.noOp, "client-1");

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
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        var result = committer.commit("action-1", evaluation).join();

        assertTrue(result.isPresent());
        ArgumentCaptor<CommitModelAction> captor = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
        CommitModelAction action = captor.getValue();
        assertEquals("action-1", action.getActionId());
        assertEquals(List.of(orderId.toString(), customerId.toString()), action.getReadModelIds());
        assertEquals(1, action.getSubsteps().size());
        assertEquals(UpdateOrder.class.getName(), action.getSubsteps().getFirst().getEvent().getType());
        assertEquals("client-1", action.getSubsteps().getFirst().getEvent().getSource());
        assertTrue(action.getSubsteps().getFirst().isPublishEvent());
        var target = action.getSubsteps().getFirst().getTargets().getFirst();
        assertEquals(orderId.toString(), target.getModelId());
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
        when(eventStoreClient.commitModelAction(
                any())).thenAnswer(invocation ->
                                           CompletableFuture.completedFuture(
                                                   result(
                                                           invocation.getArgument(
                                                                   0))));

        committer.commit(
                        "graph-component",
                        evaluation)
                .join();

        ArgumentCaptor<CommitModelAction> action =
                ArgumentCaptor.forClass(
                        CommitModelAction.class);
        verify(eventStoreClient)
                .commitModelAction(
                        action.capture());
        var document =
                action.getValue()
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
        when(eventStoreClient.commitModelAction(
                any())).thenAnswer(invocation ->
                                           CompletableFuture.completedFuture(
                                                   result(
                                                           invocation.getArgument(
                                                                   0))));

        committer.commit(
                        "document-free",
                        evaluation)
                .join();

        ArgumentCaptor<CommitModelAction> action =
                ArgumentCaptor.forClass(
                        CommitModelAction.class);
        verify(eventStoreClient)
                .commitModelAction(
                        action.capture());
        assertNull(
                action.getValue()
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
        when(eventStoreClient.commitModelAction(any()))
                .thenAnswer(invocation ->
                                    CompletableFuture.completedFuture(
                                            result(invocation.getArgument(0))));
        committer.commit("move-order", evaluation).join();

        ArgumentCaptor<CommitModelAction> captor =
                ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
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
    void rejectedActionDoesNotWriteDirectSearchDocuments() throws Exception {
        OrderId orderId = new OrderId("1");
        Order before = new Order(orderId, null, "pending", Instant.parse("2026-01-01T00:00:00Z"));
        Order after = new Order(orderId, null, "confirmed", Instant.parse("2026-01-02T00:00:00Z"));
        var evaluation = evaluation(
                List.of(orderId.toString()),
                substep(
                        new UpdateOrder(orderId),
                        transition(orderId, Order.class, before, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), after));
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation -> {
            CommitModelAction request = invocation.getArgument(0);
            return CompletableFuture.completedFuture(conflict(request, false));
        });

        var result = committer.commit(
                "action-1", evaluation, ModelConflictPolicy.FAIL).join();

        assertFalse(result.orElseThrow().isAccepted());
        ArgumentCaptor<CommitModelAction> captor = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
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
        var reloadedEvaluation = new ModelActionEngine.ActionEvaluation(
                42L, firstEvaluation.readModelIds(),
                firstEvaluation.readModelTypes(),
                List.of(substep(
                        new UpdateOrder(orderId),
                        transition(
                                orderId, Order.class, before, reloaded,
                                UpdateOrder.class, "apply", Order.class))),
                Map.of(orderId.toString(), reloaded));
        AtomicInteger commits = new AtomicInteger();
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation -> {
            CommitModelAction request = invocation.getArgument(0);
            return CompletableFuture.completedFuture(
                    commits.getAndIncrement() == 0 ? conflict(request, true) : result(request));
        });
        AtomicInteger reloads = new AtomicInteger();

        var result = committer.commit(
                "action-1", firstEvaluation,
                ModelConflictPolicy.RETRY,
                ModelConflictResolver.retryIfAllowed(), 1,
                () -> {
                    reloads.incrementAndGet();
                    return CompletableFuture.completedFuture(reloadedEvaluation);
                }).join();

        assertTrue(result.orElseThrow().isAccepted());
        assertEquals(2, commits.get());
        assertEquals(1, reloads.get());
        ArgumentCaptor<CommitModelAction> updates =
                ArgumentCaptor.forClass(
                        CommitModelAction.class);
        verify(eventStoreClient, times(2))
                .commitModelAction(
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
        var evaluation = evaluation(
                List.of(orderId.toString()),
                substep(
                        new UpdateOrder(orderId),
                        transition(orderId, Order.class, order, order, UpdateOrder.class, "apply", Order.class)),
                Map.of(orderId.toString(), order));
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation -> {
            CommitModelAction request = invocation.getArgument(0);
            return CompletableFuture.completedFuture(conflict(request, true));
        });
        AtomicInteger reloads = new AtomicInteger();

        CompletionException bounded = assertThrows(CompletionException.class, () -> committer.commit(
                "action-1", evaluation,
                ModelConflictPolicy.RETRY,
                ModelConflictResolver.retryIfAllowed(), 1,
                () -> {
                    reloads.incrementAndGet();
                    return CompletableFuture.completedFuture(evaluation);
                }).join());

        assertInstanceOf(ModelActionConflictException.class, bounded.getCause());
        assertEquals(1, reloads.get());
        verify(eventStoreClient, times(2)).commitModelAction(any());

        IllegalStateException applicationError = new IllegalStateException("try again later");
        CompletionException mapped = assertThrows(CompletionException.class, () -> committer.commit(
                "action-2", evaluation, ModelConflictPolicy.FAIL,
                ignored -> {
                    throw applicationError;
                },
                0, () -> CompletableFuture.completedFuture(evaluation)).join());
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
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));

        committer.commit("mixed-action", evaluation).join();

        ArgumentCaptor<CommitModelAction> captor = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
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
            String actionId =
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
                            () -> committer.commit(
                                    actionId,
                                    evaluation));

            assertTrue(
                    failure.getMessage()
                            .contains(
                                    "without storing its reconstructing event"));
        }
        verify(eventStoreClient, never())
                .commitModelAction(any());
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
        CompletableFuture<CommitModelActionResult> stored =
                new CompletableFuture<>();
        when(eventStoreClient.commitModelAction(any()))
                .thenReturn(stored);

        var completion = committer.commit("action-1", evaluation);

        assertFalse(completion.isDone());
        ArgumentCaptor<CommitModelAction> request =
                ArgumentCaptor.forClass(
                        CommitModelAction.class);
        verify(eventStoreClient)
                .commitModelAction(request.capture());
        stored.complete(
                result(request.getValue()));
        assertTrue(completion.join().isPresent());
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
        var rebased = new ModelActionEngine.ActionEvaluation(
                51L, List.of(id.toString()),
                Map.of(id.toString(), Order.class),
                List.of(substep(event, transition(
                        id, Order.class, stale, merged,
                        UpdateOrder.class, "apply",
                        Order.class))),
                Map.of(id.toString(), merged));
        AtomicInteger commits = new AtomicInteger();
        when(eventStoreClient.commitModelAction(any()))
                .thenAnswer(invocation -> {
                    CommitModelAction request =
                            invocation.getArgument(0);
                    if (commits.getAndIncrement() == 0) {
                        return CompletableFuture.completedFuture(
                                CommitModelActionResult.rebase(
                                        request.getRequestId(),
                                        request.getActionId(),
                                        List.of(
                                                new ModelActionConflict(
                                                        id.toString(),
                                                        51L, -1L)),
                                        51L));
                    }
                    return CompletableFuture.completedFuture(
                            result(request));
                });

        var accepted = committer.commitAcceptingRebase(
                "action-rebase", original,
                (messages, boundary) -> {
                    assertEquals(51L, boundary);
                    assertEquals(1, messages.size());
                    return CompletableFuture.completedFuture(
                            rebased);
                }).join();

        assertTrue(accepted.orElseThrow().isAccepted());
        ArgumentCaptor<CommitModelAction> requests =
                ArgumentCaptor.forClass(
                        CommitModelAction.class);
        verify(eventStoreClient, times(2))
                .commitModelAction(requests.capture());
        CommitModelAction initial =
                requests.getAllValues().getFirst();
        CommitModelAction retried =
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
        var rebased = new ModelActionEngine.ActionEvaluation(
                51L, List.of(id.toString()),
                Map.of(id.toString(), Order.class),
                List.of(substep(event, transition(
                        id, Order.class, stale, merged,
                        UpdateOrder.class, "apply",
                        Order.class))),
                Map.of(id.toString(), merged));
        CompletableFuture<CommitModelActionResult> firstCommit =
                new CompletableFuture<>();
        AtomicReference<CommitModelAction> firstRequest =
                new AtomicReference<>();
        AtomicInteger commits = new AtomicInteger();
        when(eventStoreClient.commitModelAction(any()))
                .thenAnswer(invocation -> {
                    CommitModelAction request =
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

        CompletableFuture<Optional<CommitModelActionResult>> completion;
        Fluxzero.instance.set(
                expectedContext);
        try {
            completion =
                    committer.commitAcceptingRebase(
                            "callback-rebase",
                            original,
                            (messages, boundary) -> {
                                assertSame(
                                        expectedContext,
                                        Fluxzero.instance.get());
                                rebaseThread.set(
                                        Thread.currentThread()
                                                .getName());
                                return CompletableFuture.completedFuture(
                                        rebased);
                            });
        } finally {
            Fluxzero.instance.remove();
        }
        Thread transportCallback =
                new Thread(() -> {
                    completionThread.set(
                            Thread.currentThread()
                                    .getName());
                    CommitModelAction request =
                            firstRequest.get();
                    firstCommit.complete(
                            CommitModelActionResult.rebase(
                                    request.getRequestId(),
                                    request.getActionId(),
                                    List.of(
                                            new ModelActionConflict(
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
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        committer.commit("action-1", evaluation).join();

        ArgumentCaptor<CommitModelAction> captor = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
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
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        committer.commit("action-1", evaluation).join();

        ArgumentCaptor<CommitModelAction> captor = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
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

        var result = committer.commit("action-1", evaluation).join();

        assertTrue(result.isEmpty());
        verify(eventStoreClient, never()).commitModelAction(any());
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
        ModelActionCommitter failingCommitter = new ModelActionCommitter(
                eventStoreClient, serializer, failingSerializer,
                DispatchInterceptor.noOp, "client-1");

        assertThrows(MockSearchFailure.class, () -> failingCommitter.commit("action-1", evaluation));

        verify(eventStoreClient, never()).commitModelAction(any());
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

        CommitModelAction action = committer.prepare(
                "action-snapshot", evaluation).action();

        var snapshot = action.getSubsteps().getFirst()
                .getTargets().getFirst().getSnapshot();
        assertNotNull(snapshot);
        assertEquals(2, snapshot.getSnapshotPeriod());
        assertEquals(3, snapshot.getMaxSnapshotCount());
        assertEquals(after, serializer.deserialize(
                snapshot.getValue()));
        assertEquals(1, action.toMetric().getSnapshotCount());

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
        assertNull(committer.prepare(
                        "action-no-snapshot",
                        nextEvaluation)
                           .action().getSubsteps().getFirst()
                           .getTargets().getFirst().getSnapshot());
    }

    private static ModelActionEngine.ActionEvaluation evaluation(
            List<String> readModelIds,
            ModelActionEngine.AppliedSubstep substep,
            Map<String, Object> finalValues) {
        return new ModelActionEngine.ActionEvaluation(
                41L, readModelIds,
                finalValues.entrySet().stream()
                        .filter(entry ->
                                        entry.getValue() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue()
                                        .getClass())),
                List.of(substep), finalValues);
    }

    private static ModelActionEngine.AppliedSubstep substep(
            Object event, ModelActionEngine.Transition... transitions) {
        return substep(Metadata.empty(), event, transitions);
    }

    private static ModelActionEngine.AppliedSubstep substep(
            Metadata metadata, Object event, ModelActionEngine.Transition... transitions) {
        return new ModelActionEngine.AppliedSubstep(
                new DeserializingMessage(new Message(event, metadata), MessageType.EVENT, null),
                List.of(transitions));
    }

    private static ModelActionEngine.Transition transition(
            Object id, Class<?> modelType, Object before, Object after,
            Class<?> handlerType, String methodName, Class<?> parameterType) throws Exception {
        return transition(
                id, modelType, -1L, before, after,
                handlerType, methodName, parameterType);
    }

    private static ModelActionEngine.Transition transition(
            Object id, Class<?> modelType, long beforeSequenceNumber,
            Object before, Object after,
            Class<?> handlerType, String methodName,
            Class<?> parameterType) throws Exception {
        Method handler = handlerType.getDeclaredMethod(methodName, parameterType);
        return new ModelActionEngine.Transition(
                id.toString(), modelType, beforeSequenceNumber,
                before, after, handler);
    }

    private static CommitModelActionResult result(CommitModelAction request) {
        List<ModelActionSubstepResult> substeps = request.getSubsteps().stream()
                .map(substep -> new ModelActionSubstepResult(
                        42L, substep.isPublishEvent() ? 100L : null,
                        substep.getTargets().stream().map(target ->
                                new ModelActionTargetResult(
                                        target.getModelId(), target.isStoreEvent() ? 0L : -1L,
                                        target.isStoreEvent())).toList()))
                .toList();
        return CommitModelActionResult.accepted(
                request.getRequestId(), request.getActionId(), substeps);
    }

    private static CommitModelActionResult conflict(
            CommitModelAction request, boolean retryAllowed) {
        return CommitModelActionResult.conflict(
                request.getRequestId(), request.getActionId(),
                List.of(new ModelActionConflict(
                        request.getReadModelIds().getFirst(),
                        request.getReadStateIndex() + 1L,
                        request.getReadStateIndex())),
                retryAllowed);
    }

    @Revision(7)
    @Model(eventSourced = false, searchable = true, collection = "orders", timestampPath = "changedAt")
    private record Order(
            @EntityId OrderId orderId,
            @ParentId(value = Customer.class, path = "orders") CustomerId customerId,
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
            @ParentId(path = "children")
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
            collection = "privateDocuments",
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

    @Model(
            eventSourced = false,
            searchable = true,
            eventPublication = EventPublication.IF_MODIFIED)
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
        @Apply
        StoredOnly apply(StoredOnly current) {
            return current;
        }

        @Apply
        PublishedOnly apply(PublishedOnly current) {
            return current;
        }
    }

    private static class MockSearchFailure extends RuntimeException {
    }
}
