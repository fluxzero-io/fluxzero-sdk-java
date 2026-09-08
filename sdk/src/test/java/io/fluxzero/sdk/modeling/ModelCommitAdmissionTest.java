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

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient.ModelCommitBatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommitAdmissionTest {

    @Test
    void ordersOverlappingWritersBeforeTheirFirstPhysicalAttempt() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Scope scope = write("model");
        ModelCommitAdmission.Session first = subject.open();
        ModelCommitAdmission.Session second = subject.open();
        CompletableFuture<Optional<CommitModelsResult>> firstPhysical = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean();

        CompletableFuture<Optional<CommitModelsResult>> firstResult = first.submit(
                () -> scope, null, -1, (batch, slot) -> firstPhysical);
        CompletableFuture<Optional<CommitModelsResult>> secondResult = second.submit(
                () -> scope, null, -1, (batch, slot) -> {
                    secondStarted.set(true);
                    return CompletableFuture.completedFuture(Optional.of(accepted("second")));
                });

        assertFalse(secondStarted.get());
        firstPhysical.complete(Optional.of(accepted("first")));
        assertFalse(secondStarted.get(), "physical completion alone must not release local state publication");
        subject.release(first);
        assertTrue(firstResult.join().orElseThrow().isAccepted());
        assertTrue(secondResult.join().orElseThrow().isAccepted());
        assertTrue(secondStarted.get());
        subject.release(second);
        assertEquals(0, subject.activeScopes());
    }

    @Test
    void activeReadersStayParallelAndFenceAWritingFollower() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Scope read = read("parent");
        ModelCommitAdmission.Scope write = write("parent");
        ModelCommitAdmission.Session firstReader = start(subject, read);

        ModelCommitAdmission.Session secondReader = subject.open();
        AtomicBoolean secondStarted = new AtomicBoolean();
        secondReader.submit(() -> read, null, -1, (batch, slot) -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(Optional.of(accepted("reader")));
        }).join();
        assertTrue(secondStarted.get());

        ModelCommitAdmission.Session writer = subject.open();
        AtomicBoolean writerStarted = new AtomicBoolean();
        CompletableFuture<Optional<CommitModelsResult>> queued = writer.submit(
                () -> write, null, -1, (batch, slot) -> {
                    writerStarted.set(true);
                    return CompletableFuture.completedFuture(Optional.of(accepted("writer")));
                });
        assertFalse(writerStarted.get());
        subject.release(firstReader);
        assertFalse(writerStarted.get());
        subject.release(secondReader);
        assertTrue(queued.join().orElseThrow().isAccepted());
        subject.release(writer);
        assertEquals(0, subject.activeScopes());
    }

    @Test
    void writerFencesALaterReader() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Session writer = start(subject, write("model"));
        ModelCommitAdmission.Session reader = subject.open();
        AtomicBoolean readerStarted = new AtomicBoolean();

        CompletableFuture<Optional<CommitModelsResult>> queued = reader.submit(
                () -> read("model"), null, -1, (batch, slot) -> {
                    readerStarted.set(true);
                    return CompletableFuture.completedFuture(Optional.of(accepted("reader")));
                });

        assertFalse(readerStarted.get());
        subject.release(writer);
        queued.join();
        assertTrue(readerStarted.get());
        subject.release(reader);
    }

    @Test
    void retryRetainsItsTurnAheadOfAFollower() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Scope scope = write("model");
        ModelCommitAdmission.Session first = subject.open();
        ModelCommitAdmission.Session follower = subject.open();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean followerStarted = new AtomicBoolean();

        first.submit(() -> scope, null, -1, (batch, slot) -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(accepted("first")));
        }).join();
        CompletableFuture<Optional<CommitModelsResult>> queued = follower.submit(
                () -> scope, null, -1, (batch, slot) -> {
                    followerStarted.set(true);
                    return CompletableFuture.completedFuture(Optional.of(accepted("follower")));
                });

        assertFalse(followerStarted.get());
        first.submit(() -> scope, null, -1, (batch, slot) -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(accepted("retry")));
        }).join();
        assertEquals(2, attempts.get());
        assertFalse(followerStarted.get());

        subject.release(first);
        queued.join();
        assertTrue(followerStarted.get());
        subject.release(follower);
    }

    @Test
    void oppositeMultiModelOrderCannotDeadlock() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Session first = start(
                subject, scope("namespace", List.of("left", "right")));
        ModelCommitAdmission.Session second = subject.open();
        AtomicBoolean secondStarted = new AtomicBoolean();

        CompletableFuture<Optional<CommitModelsResult>> queued = second.submit(
                () -> scope("namespace", List.of("right", "left")), null, -1,
                (batch, slot) -> {
                    secondStarted.set(true);
                    return CompletableFuture.completedFuture(Optional.of(accepted("second")));
                });

        assertFalse(secondStarted.get());
        subject.release(first);
        queued.join();
        assertTrue(secondStarted.get());
        subject.release(second);
        assertEquals(0, subject.activeScopes());
    }

    @Test
    void expandingScopeReleasesAndReacquiresBehindExistingFollowers() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Session expanding = start(subject, write("left"));
        ModelCommitAdmission.Session follower = subject.open();
        AtomicBoolean followerStarted = new AtomicBoolean();
        CompletableFuture<Optional<CommitModelsResult>> followerPhysical = new CompletableFuture<>();
        CompletableFuture<Optional<CommitModelsResult>> queuedFollower = follower.submit(
                () -> write("left"), null, -1, (batch, slot) -> {
                    followerStarted.set(true);
                    return followerPhysical;
                });

        AtomicBoolean expandedStarted = new AtomicBoolean();
        CompletableFuture<Optional<CommitModelsResult>> expanded = expanding.submit(
                () -> scope("namespace", List.of("left", "right")), null, -1,
                (batch, slot) -> {
                    expandedStarted.set(true);
                    return CompletableFuture.completedFuture(Optional.of(accepted("expanded")));
                });

        assertTrue(followerStarted.get());
        assertFalse(expandedStarted.get());
        followerPhysical.complete(Optional.of(accepted("follower")));
        assertFalse(expandedStarted.get(), "publication must still release the follower's turn");
        subject.release(follower);
        queuedFollower.join();
        expanded.join();
        assertTrue(expandedStarted.get());
        subject.release(expanding);
        assertEquals(0, subject.activeScopes());
    }

    @Test
    void independentModelsAndNamespacesRemainParallel() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Session active = start(subject, write("active"));
        ModelCommitAdmission.Session independent = subject.open();
        ModelCommitAdmission.Session otherNamespace = subject.open();

        assertTrue(independent.submit(
                () -> write("independent"), null, -1,
                (batch, slot) -> CompletableFuture.completedFuture(Optional.of(accepted("independent"))))
                .isDone());
        assertTrue(otherNamespace.submit(
                () -> scope("other", "active", true), null, -1,
                (batch, slot) -> CompletableFuture.completedFuture(Optional.of(accepted("namespace"))))
                .isDone());

        subject.release(independent);
        subject.release(otherNamespace);
        subject.release(active);
        assertEquals(0, subject.activeScopes());
    }

    @Test
    void waitingCommitFlushesItsPredecessorAndDetachesItsOwnTransportSlot() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Scope scope = write("model");
        FakeBatch firstBatch = new FakeBatch();
        FakeBatch secondBatch = new FakeBatch();
        ModelCommitAdmission.Session first = subject.open();
        ModelCommitAdmission.Session second = subject.open();
        CompletableFuture<Optional<CommitModelsResult>> firstPhysical = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean();

        first.submit(() -> scope, firstBatch, 0, (batch, slot) -> firstPhysical);
        CompletableFuture<Optional<CommitModelsResult>> queued = second.submit(
                () -> scope, secondBatch, 1, (batch, slot) -> {
                    secondStarted.set(true);
                    assertEquals(null, batch);
                    assertEquals(-1, slot);
                    return CompletableFuture.completedFuture(Optional.of(accepted("second")));
                });

        assertTrue(firstBatch.flushes.get() > 0);
        assertEquals(List.of(1), secondBatch.skipped);
        firstPhysical.complete(Optional.of(accepted("first")));
        assertFalse(secondStarted.get());
        subject.release(first);
        queued.join();
        assertTrue(secondStarted.get());
        subject.release(second);
    }

    @Test
    void closingRejectsNewSessionsAndQueuedWork() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Session active = start(subject, write("model"));
        ModelCommitAdmission.Session queued = subject.open();
        CompletableFuture<Optional<CommitModelsResult>> queuedResult = queued.submit(
                () -> write("model"), null, -1,
                (batch, slot) -> CompletableFuture.completedFuture(Optional.of(accepted("queued"))));

        subject.close();
        assertTrue(active.submit(
                () -> write("model"), null, -1,
                (batch, slot) -> CompletableFuture.completedFuture(Optional.of(accepted("retry"))))
                .join().orElseThrow().isAccepted());
        subject.release(active);

        CompletionException failure = assertThrows(CompletionException.class, queuedResult::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        ModelCommitAdmission.Session rejected = subject.open();
        CompletableFuture<Optional<CommitModelsResult>> rejectedResult = rejected.submit(
                () -> write("other"), null, -1,
                (batch, slot) -> CompletableFuture.completedFuture(Optional.of(accepted("rejected"))));
        assertTrue(rejectedResult.isCompletedExceptionally());
        subject.release(queued);
    }

    @Test
    void failureOrCancellationDoesNotReleaseBeforePublicationCleanup() {
        ModelCommitAdmission subject = new ModelCommitAdmission();
        ModelCommitAdmission.Scope scope = write("model");
        ModelCommitAdmission.Session failed = subject.open();
        ModelCommitAdmission.Session follower = subject.open();
        CompletableFuture<Optional<CommitModelsResult>> physical = new CompletableFuture<>();
        CompletableFuture<Optional<CommitModelsResult>> failedResult = failed.submit(
                () -> scope, null, -1, (batch, slot) -> physical);
        AtomicInteger followerStarts = new AtomicInteger();
        CompletableFuture<Optional<CommitModelsResult>> queued = follower.submit(
                () -> scope, null, -1, (batch, slot) -> {
                    followerStarts.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.of(accepted("follower")));
                });

        physical.completeExceptionally(new IllegalStateException("failed"));
        assertThrows(CompletionException.class, failedResult::join);
        assertEquals(0, followerStarts.get());
        subject.release(failed);
        queued.join();
        assertEquals(1, followerStarts.get());
        subject.release(follower);

        ModelCommitAdmission.Session cancelled = subject.open();
        ModelCommitAdmission.Session afterCancellation = subject.open();
        CompletableFuture<Optional<CommitModelsResult>> cancelledPhysical = new CompletableFuture<>();
        CompletableFuture<Optional<CommitModelsResult>> cancelledResult = cancelled.submit(
                () -> scope, null, -1, (batch, slot) -> cancelledPhysical);
        CompletableFuture<Optional<CommitModelsResult>> afterCancellationResult = afterCancellation.submit(
                () -> scope, null, -1,
                (batch, slot) -> CompletableFuture.completedFuture(Optional.of(accepted("after"))));

        cancelledPhysical.cancel(false);
        CompletionException cancellation = assertThrows(CompletionException.class, cancelledResult::join);
        assertInstanceOf(CancellationException.class, cancellation.getCause());
        assertFalse(afterCancellationResult.isDone());
        subject.release(cancelled);
        afterCancellationResult.join();
        subject.release(afterCancellation);
        assertEquals(0, subject.activeScopes());
    }

    private static ModelCommitAdmission.Session start(
            ModelCommitAdmission subject,
            ModelCommitAdmission.Scope scope) {
        ModelCommitAdmission.Session session = subject.open();
        session.submit(() -> scope, null, -1,
                (batch, slot) -> CompletableFuture.completedFuture(Optional.of(accepted("active")))).join();
        return session;
    }

    private static ModelCommitAdmission.Scope read(String id) {
        return scope("namespace", id, false);
    }

    private static ModelCommitAdmission.Scope write(String id) {
        return scope("namespace", id, true);
    }

    private static ModelCommitAdmission.Scope scope(String namespace, String id, boolean write) {
        return ModelCommitAdmission.Scope.of(List.of(new ModelCommitAdmission.Access(
                new ModelCommitAdmission.Key(namespace, id, false), write)));
    }

    private static ModelCommitAdmission.Scope scope(String namespace, List<String> ids) {
        return ModelCommitAdmission.Scope.of(ids.stream()
                .map(id -> new ModelCommitAdmission.Access(
                        new ModelCommitAdmission.Key(namespace, id, false), true))
                .toList());
    }

    private static CommitModelsResult accepted(String id) {
        return CommitModelsResult.accepted(1L, id, List.of());
    }

    private static final class FakeBatch implements ModelCommitBatch {
        private final AtomicInteger flushes = new AtomicInteger();
        private final List<Integer> skipped = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<CommitModelsResult> add(int slot, CommitModels commit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void skip(int slot) {
            skipped.add(slot);
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }

        @Override
        public void fail(Throwable failure) {
        }
    }
}
