/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.test;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Consumer;

import static io.fluxzero.common.MessageType.DOCUMENT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFixtureDocumentRevisionTest {
    private final TestFixture fixture = TestFixture.create().async();
    private final TestFixture.GivenWhenThenInterceptor interceptor =
            new TestFixture.GivenWhenThenInterceptor(fixture);
    private final Tracker tracker = new Tracker("tracker", DOCUMENT, "documents",
            ConsumerConfiguration.builder().name("documents").build(), null);

    @AfterEach
    void close() {
        fixture.getFluxzero().close();
    }

    @Test
    void localStoredRevisionsHaveDistinctPositionsEvenWithIdenticalContent() {
        var document = document("same");
        var first = batch(document, 1);
        var second = batch(document, 2);
        var consume = interceptor.intercept(b -> {}, tracker);
        fixture.whenExecuting(f -> {
            interceptor.interceptClientDispatch(DOCUMENT, "documents", f.client().namespace(), first.getMessages());
            interceptor.interceptClientDispatch(DOCUMENT, "documents", f.client().namespace(), second.getMessages());
            consume.accept(first);
            assertFalse(fixture.checkConsumers());
            consume.accept(second);
            assertTrue(fixture.checkConsumers());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "second"})
    void completingOldBatchPreservesRevisionPublishedDuringHandling(String value) {
        var first = document(value);
        var second = document("second");
        Consumer<MessageBatch> consume = interceptor.intercept(batch -> {
            interceptor.monitorDocumentDispatch(second);
            assertFalse(fixture.checkConsumers());
        }, tracker);
        interceptor.monitorDocumentDispatch(first);
        consume.accept(batch(first, 1));
        assertFalse(fixture.checkConsumers());
        interceptor.intercept(b -> {}, tracker).accept(batch(second, 2));
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void customClientWithoutStoredIndicesMatchesDocumentContent() {
        var first = batch(document("first"), 1);
        var second = batch(document("second"), 2);
        first.getMessages().getFirst().setIndex(null);
        second.getMessages().getFirst().setIndex(null);
        var consume = interceptor.intercept(b -> {}, tracker);
        fixture.whenExecuting(f -> {
            interceptor.interceptClientDispatch(DOCUMENT, "documents", f.client().namespace(), first.getMessages());
            interceptor.interceptClientDispatch(DOCUMENT, "documents", f.client().namespace(), second.getMessages());
            consume.accept(first);
            assertFalse(fixture.checkConsumers());
            consume.accept(second);
            assertTrue(fixture.checkConsumers());
        }).expectNoErrors();
    }

    @Test
    void completingOldBatchPreservesAlreadyQueuedNewRevision() {
        var first = document("first");
        var second = document("second");
        var consume = interceptor.intercept(b -> {}, tracker);
        interceptor.monitorDocumentDispatch(first);
        interceptor.monitorDocumentDispatch(second);
        consume.accept(batch(first, 1));
        assertFalse(fixture.checkConsumers());
        consume.accept(batch(second, 2));
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void newestRevisionCompletesCoalescedOlderRevisions() {
        var first = document("first");
        var second = document("second");
        var consume = interceptor.intercept(b -> {}, tracker);
        interceptor.monitorDocumentDispatch(first);
        interceptor.monitorDocumentDispatch(second);
        consume.accept(batch(second, 2));
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void failedBatchLeavesRevisionPendingForRetry() {
        var document = document("first");
        var consume = interceptor.intercept(b -> { throw new IllegalStateException("retry"); }, tracker);
        interceptor.monitorDocumentDispatch(document);
        assertThrows(IllegalStateException.class, () -> consume.accept(batch(document, 1)));
        assertFalse(fixture.checkConsumers());
        interceptor.intercept(b -> {}, tracker).accept(batch(document, 1));
        assertTrue(fixture.checkConsumers());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentUpdateChainCompletesBeforeAssertions(boolean async) {
        TestFixture chain = TestFixture.create(new Object() {
            @HandleDocument
            void handle(Revision document) {
                if (document.value() < 5) {
                    Fluxzero.index(new Revision(document.value() + 1), "same-id", Revision.class).join();
                } else {
                    Fluxzero.publishEvent("completed");
                }
            }
        });
        if (async) {
            chain = chain.async();
        }
        try {
            chain.whenExecuting(f -> Fluxzero.index(new Revision(0), "same-id", Revision.class).join())
                    .expectOnlyEvents("completed").expectNoErrors();
        } finally {
            chain.getFluxzero().close();
        }
    }

    private record Revision(int value) {
    }

    @Test
    void outOfBoundsCompletionPreservesNewerRevisionForOtherConsumer() {
        var bounded = new Tracker("bounded", DOCUMENT, "documents", tracker.getConfiguration().toBuilder()
                .name("bounded").minIndex(2L).build(), null);
        var consume = interceptor.intercept(b -> {}, tracker);
        var consumeBounded = interceptor.intercept(b -> {}, bounded);
        var first = document("first");
        var second = document("second");
        interceptor.monitorDocumentDispatch(first);
        interceptor.monitorDocumentDispatch(second);
        consume.accept(batch(first, 1));
        consume.accept(batch(second, 2));
        assertFalse(fixture.checkConsumers());
        consumeBounded.accept(batch(second, 2));
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void cancellingFailedWritePreservesOtherRevisionEvenWithIdenticalContent() {
        var failed = document("same");
        var accepted = document("same");
        var consume = interceptor.intercept(b -> {}, tracker);
        interceptor.monitorDocumentDispatch(failed);
        interceptor.monitorDocumentDispatch(accepted);
        interceptor.cancelDocumentDispatch(List.of(failed));
        assertFalse(fixture.checkConsumers());
        consume.accept(batch(accepted, 2));
        assertTrue(fixture.checkConsumers());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void implicitAndExplicitApplicationNamespaceShareOutOfBoundsCompletion(boolean implicitReader) {
        var client = io.fluxzero.sdk.configuration.client.LocalClient.newInstance().forNamespace("tenant");
        var scoped = TestFixture.createAsync(io.fluxzero.sdk.configuration.DefaultFluxzero.builder(), client);
        try {
            var monitor = new TestFixture.GivenWhenThenInterceptor(scoped);
            var implicit = new Tracker("implicit", DOCUMENT, "documents",
                    ConsumerConfiguration.builder().name("implicit")
                            .namespace(implicitReader ? null : "tenant").build(), null);
            var explicit = new Tracker("explicit", DOCUMENT, "documents",
                    ConsumerConfiguration.builder().name("explicit").namespace(implicitReader ? "tenant" : null)
                            .minIndex(2L).build(), null);
            var consume = monitor.intercept(b -> {}, implicit);
            monitor.intercept(b -> {}, explicit);
            var first = document("first");
            monitor.monitorDocumentDispatch(first);
            assertFalse(scoped.checkConsumers());
            consume.accept(batch(first, 1));
            assertTrue(scoped.checkConsumers(), "Both consumers address tenant; the bounded consumer cannot receive index 1");
        } finally {
            scoped.getFluxzero().close();
        }
    }

    @Test
    void differentNamespaceDoesNotShareOutOfBoundsCompletion() {
        var other = new Tracker("other", DOCUMENT, "documents", tracker.getConfiguration().toBuilder()
                .name("other").namespace("other").minIndex(2L).build(), null);
        var consume = interceptor.intercept(b -> {}, tracker);
        interceptor.intercept(b -> {}, other);
        var first = batch(document("first"), 1);
        fixture.whenExecuting(f -> {
            interceptor.interceptClientDispatch(DOCUMENT, "documents", f.client().namespace(), first.getMessages());
            interceptor.interceptClientDispatch(DOCUMENT, "documents", "other", first.getMessages());
            consume.accept(first);
            assertFalse(fixture.checkConsumers(), "Completing the default namespace must not clear other");
            interceptor.shutdown(other);
            assertTrue(fixture.checkConsumers());
        }).expectNoErrors();
    }

    private SerializedDocument document(String value) {
        return new SerializedDocument("id", 0L, 0L, "documents",
                fixture.getFluxzero().serializer().serialize(value), null, null, null);
    }

    private MessageBatch batch(SerializedDocument document, long index) {
        var message = new SerializedMessage(document.getDocument(),
                Metadata.of("$start", document.getTimestamp(), "$end", document.getEnd()), "id", 0L);
        message.setIndex(index);
        return new MessageBatch(new int[]{0, 128}, List.of(message), index, Position.newPosition(), true);
    }
}
