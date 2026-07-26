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
import io.fluxzero.common.api.search.BulkUpdate;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.bulkupdate.DeleteDocument;
import io.fluxzero.common.api.search.bulkupdate.IndexDocument;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ModelActionCommitterTest {

    private final EventStoreClient eventStoreClient = mock(EventStoreClient.class);
    private final DocumentStore documentStore = mock(DocumentStore.class);
    private final JacksonSerializer serializer = new JacksonSerializer();
    private final ModelActionCommitter committer = new ModelActionCommitter(
            eventStoreClient, documentStore, serializer, serializer,
            DispatchInterceptor.noOp, "client-1");

    @Test
    void commitsOriginalEventOnceWithTargetMembershipAndChildOwnedRelationship() throws Exception {
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
        when(documentStore.bulkUpdate(anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(null));

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
        assertEquals(1, target.getRelationships().size());
        assertEquals(customerId.toString(), target.getRelationships().getFirst().getParentId());
        assertEquals(Customer.class.getName(), target.getRelationships().getFirst().getParentType());
        assertEquals("orders", target.getRelationships().getFirst().getPath());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<java.util.Collection> updates = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(documentStore).bulkUpdate(updates.capture());
        IndexDocument update = (IndexDocument) updates.getValue().iterator().next();
        SerializedDocument document = (SerializedDocument) update.getObject();
        assertEquals(after, serializer.fromDocument(document, Order.class));
        assertEquals(orderId.toString(), update.getId());
        assertEquals("orders", update.getCollection());
        assertEquals(after.changedAt(), update.getTimestamp());
        assertEquals(after.changedAt(), update.getEnd());
        assertEquals(7, document.getDocument().getRevision());
        assertEquals("north", document.getMetadata().get("tenant"));
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
        verify(documentStore, never()).bulkUpdate(anyCollection());
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
        when(documentStore.bulkUpdate(anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(null));
        AtomicInteger reloads = new AtomicInteger();

        var result = committer.commit(
                "action-1", firstEvaluation,
                ModelConflictPolicy.RETRY_IF_RELATIONS_UNCHANGED,
                ModelConflictResolver.retryIfAllowed(), 1,
                () -> {
                    reloads.incrementAndGet();
                    return CompletableFuture.completedFuture(reloadedEvaluation);
                }).join();

        assertTrue(result.orElseThrow().isAccepted());
        assertEquals(2, commits.get());
        assertEquals(1, reloads.get());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<java.util.Collection> updates = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(documentStore, times(1)).bulkUpdate(updates.capture());
        IndexDocument update = (IndexDocument) updates.getValue().iterator().next();
        assertEquals(reloaded, serializer.fromDocument(
                (SerializedDocument) update.getObject(), Order.class));
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
                ModelConflictPolicy.RETRY_IF_RELATIONS_UNCHANGED,
                ModelConflictResolver.retryIfAllowed(), 1,
                () -> {
                    reloads.incrementAndGet();
                    return CompletableFuture.completedFuture(evaluation);
                }).join());

        assertInstanceOf(ModelActionConflictException.class, bounded.getCause());
        assertEquals(1, reloads.get());
        verify(eventStoreClient, times(2)).commitModelAction(any());
        verify(documentStore, never()).bulkUpdate(anyCollection());

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
        verify(documentStore, never()).bulkUpdate(anyCollection());
    }

    @Test
    void successfulCommitWaitsForDirectDocumentIndexing() throws Exception {
        OrderId id = new OrderId("1");
        Order after = new Order(
                id, null, "confirmed", Instant.parse("2026-01-02T00:00:00Z"));
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new UpdateOrder(id), transition(
                        id, Order.class, null, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(id.toString(), after));
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        CompletableFuture<Void> indexing = new CompletableFuture<>();
        when(documentStore.bulkUpdate(anyCollection())).thenReturn(indexing);

        var completion = committer.commit("action-1", evaluation);

        assertFalse(completion.isDone());
        indexing.complete(null);
        assertTrue(completion.join().isPresent());
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
        when(documentStore.bulkUpdate(anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(null));

        committer.commit("action-1", evaluation).join();

        ArgumentCaptor<CommitModelAction> captor = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
        var substep = captor.getValue().getSubsteps().getFirst();
        assertTrue(substep.isPublishEvent());
        assertTrue(substep.getTargets().getFirst().isStoreEvent());
        assertTrue(substep.getTargets().getFirst().isDelete());
        assertTrue(substep.getTargets().getFirst().getRelationships().isEmpty());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<java.util.Collection> updates = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(documentStore).bulkUpdate(updates.capture());
        DeleteDocument update = (DeleteDocument) updates.getValue().iterator().next();
        assertEquals(id.toString(), update.getId());
        assertEquals("orders", update.getCollection());
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
        when(documentStore.bulkUpdate(anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(null));

        committer.commit("action-1", evaluation).join();

        ArgumentCaptor<CommitModelAction> captor = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient).commitModelAction(captor.capture());
        var substep = captor.getValue().getSubsteps().getFirst();
        assertNull(substep.getEvent());
        assertFalse(substep.isPublishEvent());
        assertFalse(substep.getTargets().getFirst().isStoreEvent());
        assertTrue(substep.getTargets().getFirst().isUpdateState());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<java.util.Collection> updates = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(documentStore).bulkUpdate(updates.capture());
        IndexDocument update = (IndexDocument) updates.getValue().iterator().next();
        assertEquals(after, serializer.fromDocument(
                (SerializedDocument) update.getObject(), PrivateDocument.class));
        assertEquals(id.toString(), update.getId());
        assertEquals("privateDocuments", update.getCollection());
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
        verify(documentStore, never()).bulkUpdate(anyCollection());
    }

    @Test
    void searchFailureRetainsOriginalRepairStateWhenSameActionIsReevaluated() throws Exception {
        OrderId id = new OrderId("retry");
        Order after = new Order(id, null, "confirmed", Instant.EPOCH);
        var evaluation = evaluation(
                List.of(id.toString()),
                substep(new UpdateOrder(id), transition(
                        id, Order.class, null, after, UpdateOrder.class, "apply", Order.class)),
                Map.of(id.toString(), after));
        when(eventStoreClient.commitModelAction(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(result(invocation.getArgument(0))));
        when(documentStore.bulkUpdate(anyCollection()))
                .thenReturn(CompletableFuture.failedFuture(new MockSearchFailure()))
                .thenReturn(CompletableFuture.completedFuture(null));

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> committer.commit("action-retry", evaluation).join());
        assertInstanceOf(MockSearchFailure.class, failure.getCause());
        Order divergent = new Order(
                id, null, "divergent", Instant.EPOCH.plusSeconds(1));
        var reevaluated = evaluation(
                List.of(id.toString()),
                substep(new UpdateOrder(id), transition(
                        id, Order.class, after, divergent,
                        UpdateOrder.class, "apply", Order.class)),
                Map.of(id.toString(), divergent));
        assertTrue(committer.commit(
                "action-retry", reevaluated).join().isPresent());

        ArgumentCaptor<CommitModelAction> actions = ArgumentCaptor.forClass(CommitModelAction.class);
        verify(eventStoreClient, times(2)).commitModelAction(actions.capture());
        assertEquals(
                List.of("action-retry", "action-retry"),
                actions.getAllValues().stream().map(CommitModelAction::getActionId).toList());
        assertSame(
                actions.getAllValues().getFirst(),
                actions.getAllValues().getLast());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<java.util.Collection> updates =
                ArgumentCaptor.forClass(
                        java.util.Collection.class);
        verify(documentStore, times(2))
                .bulkUpdate(updates.capture());
        assertEquals(
                updates.getAllValues().getFirst(),
                updates.getAllValues().getLast());
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
                eventStoreClient, documentStore, serializer, failingSerializer,
                DispatchInterceptor.noOp, "client-1");

        assertThrows(MockSearchFailure.class, () -> failingCommitter.commit("action-1", evaluation));

        verify(eventStoreClient, never()).commitModelAction(any());
        verify(documentStore, never()).bulkUpdate(anyCollection());
    }

    private static ModelActionEngine.ActionEvaluation evaluation(
            List<String> readModelIds,
            ModelActionEngine.AppliedSubstep substep,
            Map<String, Object> finalValues) {
        return new ModelActionEngine.ActionEvaluation(
                41L, readModelIds, List.of(substep), finalValues);
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
        Method handler = handlerType.getDeclaredMethod(methodName, parameterType);
        return new ModelActionEngine.Transition(
                id.toString(), modelType, before, after, handler);
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
