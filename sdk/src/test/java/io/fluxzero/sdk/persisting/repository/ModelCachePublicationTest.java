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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.*;
import io.fluxzero.common.api.search.*;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.caching.SoftReferenceCache;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ModelCachePublicationTest {
    @Model(persistence = ModelPersistence.DOCUMENT, document = @DocumentProjection(collection = "probeDocuments"))
    public record Doc(@EntityId String id, String value) {}

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"headed", "absent", "headless", "recreated"})
    void oldForegroundReadCannotBorrowNewerCacheProof(String mode) throws Exception {
        long newBoundary = "recreated".equals(mode) ? 13L : 12L;
        Client client = mock(Client.class);
        EventStoreClient eventStore = mock(EventStoreClient.class);
        SearchClient search = mock(SearchClient.class);
        DocumentStore documents = mock(DocumentStore.class);
        JacksonSerializer serializer = new JacksonSerializer();
        List<Runnable> shutdown = new CopyOnWriteArrayList<>();
        when(client.namespace()).thenReturn("public");
        when(client.getEventStoreClient()).thenReturn(eventStore);
        when(client.getSearchClient()).thenReturn(search);
        when(documents.getSerializer()).thenReturn(serializer);
        doAnswer(invocation -> { shutdown.add(invocation.getArgument(0)); return (io.fluxzero.common.Registration) () -> {}; })
                .when(client).beforeShutdown(any());
        BlockingQueue<CompletableFuture<TrackModelUpdatesResult>> polls = new LinkedBlockingQueue<>();
        when(eventStore.trackModelUpdates(any())).thenAnswer(invocation -> {
            TrackModelUpdates request = invocation.getArgument(0);
            if (request.getMaxWaitMillis() == 0) {
                return CompletableFuture.completedFuture(new TrackModelUpdatesResult(
                        request.getRequestId(), request.getLastStateIndex(), 10L, 10L, List.of()));
            }
            CompletableFuture<TrackModelUpdatesResult> result = new CompletableFuture<>();
            polls.add(result);
            return result;
        });
        CountDownLatch oldReadCaptured = new CountDownLatch(1), returnOldRead = new CountDownLatch(1);
        CountDownLatch oldValueInstalled = new CountDownLatch(1), finishOldPublication = new CountDownLatch(1);
        AtomicBoolean pauseOldPut = new AtomicBoolean();
        AtomicInteger documentReads = new AtomicInteger();
        when(search.fetchModelDocument(any())).thenAnswer(invocation -> {
            GetDocument request = invocation.getArgument(0);
            if ("seed".equals(request.getId())) return response(request, serializer, 10, "seed");
            if (documentReads.incrementAndGet() == 1) {
                GetDocumentResult old = response(request, serializer, 10, "old");
                oldReadCaptured.countDown();
                await(returnOldRead);
                return old;
            }
            if ("absent".equals(mode)) return new GetDocumentResult(request.getRequestId(), null, null);
            if ("headless".equals(mode)) {
                return new GetDocumentResult(request.getRequestId(), serializer.toDocument(
                        new Doc(request.getId(), "new"), request.getId(), request.getCollection(),
                        null, null, Metadata.empty()), null);
            }
            if ("recreated".equals(mode)) {
                return new GetDocumentResult(request.getRequestId(), serializer.toDocument(
                        new Doc(request.getId(), "new"), request.getId(), request.getCollection(),
                        null, null, Metadata.empty()), new ModelHeadState(request.getId(), Doc.class.getSimpleName(),
                        0L, 13L, false, false));
            }
            return response(request, serializer, newBoundary, "new");
        });
        SoftReferenceCache cache = new SoftReferenceCache(100, Runnable::run, null) {
            @Override public Object put(Object id, Object value) {
                Object previous = super.put(id, value);
                if (value instanceof ModelRoot<?> model && model.stateIndex() == 10
                    && model.get() instanceof Doc doc && "target".equals(doc.id())
                    && pauseOldPut.compareAndSet(true, false)) {
                    oldValueInstalled.countDown();
                    await(finishOldPublication);
                }
                return previous;
            }
        };
        try {
            DefaultModelRepository repository = new DefaultModelRepository(client, documents, serializer,
                    new DefaultEntityHelper(List.of(), false), null, cache, List.of());
            repository.configureModelTypes(() -> List.of(Doc.class));
            repository.load("seed", Doc.class);
            CompletableFuture<TrackModelUpdatesResult> firstPoll = next(polls);
            CompletableFuture<Entity<Doc>> oldLoad = CompletableFuture.supplyAsync(
                    () -> repository.load("target", Doc.class), task -> Thread.ofVirtual().start(task));
            check(oldReadCaptured.await(5, TimeUnit.SECONDS), "old read did not start");
            List<ModelUpdate> changes = new ArrayList<>();
            if ("absent".equals(mode) || "recreated".equals(mode)) {
                changes.add(new ModelUpdate(ModelUpdateKind.HARD_DELETE, "delete", 0, 12L, null, List.of()));
            }
            if (!"absent".equals(mode)) changes.add(new ModelUpdate(ModelUpdateKind.COMMIT, "new-write", 0,
                    newBoundary, null, List.of(new ModelCommitTargetResult("target", 0L, false))));
            firstPoll.complete(new TrackModelUpdatesResult(1L, newBoundary, newBoundary, newBoundary, changes));
            next(polls); // public client callback barrier: the tracker processed state12 before the new load.
            Entity<Doc> newLoad = repository.load("target", Doc.class);
            check("absent".equals(mode) ? newLoad.isEmpty() : "new".equals(newLoad.get().value()),
                    "new foreground load did not return current value");
            AtomicLong proof = new AtomicLong(-1), modelState = new AtomicLong(-1);
            check(repository.supplyCurrentModel("target", Doc.class,
                    (entity, through, state) -> {proof.set(through); modelState.set(state);}), "new proof absent");

            pauseOldPut.set(true);
            returnOldRead.countDown();
            oldLoad.get(5, TimeUnit.SECONDS);
            check(oldValueInstalled.getCount() == 1, "superseded read reached physical cache publication");
            int readsBefore = documentReads.get();
            Entity<Doc> contaminated = repository.load("target", Doc.class);
            check(repository.supplyCurrentModel("target", Doc.class,
                    (entity, through, state) -> {proof.set(through); modelState.set(state);}), "contaminated proof absent");
            check("absent".equals(mode) ? contaminated.isEmpty() : "new".equals(contaminated.get().value()),
                    "superseded document became current");
            check(proof.get() == newBoundary && documentReads.get() == readsBefore, "new cache proof was lost");
            finishOldPublication.countDown();
            oldLoad.get(5, TimeUnit.SECONDS);

        } finally {
            returnOldRead.countDown(); finishOldPublication.countDown();
            shutdown.forEach(Runnable::run); cache.close();
        }
    }
    static GetDocumentResult response(GetDocument request, JacksonSerializer serializer, long state, String value) {
        return new GetDocumentResult(request.getRequestId(), serializer.toDocument(new Doc(request.getId(), value),
                request.getId(), request.getCollection(), null, null, Metadata.empty()),
                new ModelHeadState(request.getId(), Doc.class.getSimpleName(), state == 10 ? 0 : 1, state, false, false));
    }
    static CompletableFuture<TrackModelUpdatesResult> next(BlockingQueue<CompletableFuture<TrackModelUpdatesResult>> polls)
            throws Exception { var result=polls.poll(5,TimeUnit.SECONDS); check(result != null,"tracker poll missing"); return result; }
    static void await(CountDownLatch latch) { try { check(latch.await(5,TimeUnit.SECONDS),"latch timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }
    static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
