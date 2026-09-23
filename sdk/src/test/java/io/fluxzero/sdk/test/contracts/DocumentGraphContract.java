/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.test.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EventPublication;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.modeling.ModelConflictResolver;
import io.fluxzero.sdk.modeling.ModelCommitConflictException;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Shared public-API contract, inherited by LocalClient and PostgreSQL/WebSocket suites. */
@Timeout(30)
public abstract class DocumentGraphContract {
    /** Supplies separate application clients sharing one isolated namespace. */
    protected abstract Client[] clients(String namespace);

    @ParameterizedTest
    @CsvSource({"FAIL,false,delete", "RETRY,true,delete", "ACCEPT,true,delete", "DEFAULT,true,delete",
                "FAIL,false,replace", "RETRY,true,replace", "ACCEPT,true,replace", "DEFAULT,true,replace",
                "FAIL,false,recreate", "RETRY,true,recreate", "ACCEPT,true,recreate", "DEFAULT,true,recreate"})
    void staleDocumentDeleteIsAPublicNonRetryingConflict(
            ModelConflictPolicy policy, boolean pinGraph, String concurrentChange) {
        try (var h = new Harness(builder -> {
            if (policy != ModelConflictPolicy.DEFAULT) {
                builder.configureModelConflictHandling(policy, ModelConflictResolver.retryIfAllowed(), 3);
            }
        })) {
            write(h.writer, new SeedDocumentReceipt("receipt"));
            h.set(1);
            AtomicInteger invocations = new AtomicInteger();
            AtomicReference<Runnable> deletion = new AtomicReference<>(() -> {
                if (!concurrentChange.equals("replace")) {
                    write(h.writer, new DeleteDocumentReceipt("receipt"));
                }
                if (concurrentChange.equals("recreate")) {
                    write(h.writer, new SeedDocumentReceipt("receipt"));
                } else if (concurrentChange.equals("replace")) {
                    write(h.writer, new SetDocumentReceipt("receipt", 2));
                }
            });
            Message message = new Message(new PausedDocumentDelete(
                    "receipt", pinGraph, () -> h.run(deletion), invocations));
            var failure = assertThrows(Exception.class,
                    () -> h.reader.apply(fc -> fc.executeModelCommit(message).join()));
            var conflict = assertInstanceOf(ModelCommitConflictException.class,
                    io.fluxzero.common.ObjectUtils.unwrapException(failure), failure.toString());
            assertAll(
                    () -> assertNull(conflict.getResult(), "Preparation must not invent a storage response"),
                    () -> assertEquals(message.getMessageId(), conflict.getReadConflict().commitId()),
                    () -> assertEquals("receipt", conflict.getReadConflict().modelId()),
                    () -> assertTrue(conflict.getReadConflict().readStateIndex() >= 0),
                    () -> assertEquals(1, invocations.get(), "A pinned stale mutation must not be reevaluated"),
                    () -> assertEquals(1, h.gates.get()),
                    () -> assertEquals(0, h.commits.get(), "The incomplete cascade must not reach storage"),
                    () -> assertEquals(concurrentChange.equals("delete") ? null
                                               : new DocumentReceipt("receipt", concurrentChange.equals("replace") ? 2 : 0),
                                       h.writer.apply(fc -> Fluxzero.loadModel("receipt", DocumentReceipt.class).get())));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"delete", "replace", "recreate"})
    void cascadeConflictIdentifiesTheUnavailableDescendant(String change) {
        try (var h = new Harness()) {
            write(h.writer, new SetRoot("root", 1));
            write(h.writer, new SetDocument("one", "root", "alias", 1));
            // Register the shared child contract in the reader before untyped cascade traversal.
            h.reader.apply(fc -> Fluxzero.loadModel("one", Document.class).get());
            var invocations = new AtomicInteger();
            var gate = new AtomicReference<Runnable>(() -> {
                write(h.writer, new SetDocument("one", "root", "alias", change.equals("replace") ? 2 : null));
                if (change.equals("recreate")) {
                    write(h.writer, new SetDocument("one", "root", "alias", 2));
                }
            });
            var message = new Message(new PausedRootDelete("root", () -> h.run(gate), invocations));
            var failure = assertThrows(Exception.class,
                    () -> h.reader.apply(fc -> fc.executeModelCommit(message).join()));
            var conflict = assertInstanceOf(ModelCommitConflictException.class,
                    io.fluxzero.common.ObjectUtils.unwrapException(failure), failure.toString());
            assertEquals("doc-one", conflict.getReadConflict().modelId());
            assertEquals(message.getMessageId(), conflict.getReadConflict().commitId());
            assertEquals(1, invocations.get());
            assertEquals(0, h.commits.get());
            assertEquals(new Root("root", 1), h.writer.apply(fc -> Fluxzero.loadModel("root", Root.class).get()));
            assertEquals(change.equals("delete") ? null : new Document("one", "root", "alias", 2),
                         h.writer.apply(fc -> Fluxzero.loadCurrentGraph("one", Document.class).get()));
        }
    }

    @Test
    void staleDocumentDeleteOnUninstrumentedClientIsPublic() {
        try (var h = new Harness()) {
            write(h.reader, new SeedDocumentReceipt("receipt"));
            h.set(1);
            var invocations = new AtomicInteger();
            var gate = new AtomicReference<Runnable>(() -> write(h.reader, new DeleteDocumentReceipt("receipt")));
            var failure = assertThrows(Exception.class, () -> write(h.writer,
                    new PausedDocumentDelete("receipt", true, () -> h.run(gate), invocations)));
            assertInstanceOf(ModelCommitConflictException.class, io.fluxzero.common.ObjectUtils.unwrapException(failure));
            assertEquals(1, invocations.get());
            assertEquals(1, h.gates.get());
            assertNull(h.writer.apply(fc -> Fluxzero.loadCurrentGraph("receipt", DocumentReceipt.class).get()));
        }
    }

    @Test
    void staleDocumentConflictDoesNotWaitForOrReevaluateAfterProvisionalProducer() {
        try (var h = new Harness()) {
            write(h.writer, new SeedDocumentReceipt("receipt"));
            h.set(1);
            var invocations = new AtomicInteger();
            var deletion = new AtomicReference<Runnable>(() -> write(h.writer, new DeleteDocumentReceipt("receipt")));
            var gate = new CompletableFuture<Void>();
            var producer = new AtomicReference<CompletableFuture<Void>>();
            var consumer = new AtomicReference<CompletableFuture<Void>>();
            h.commitGate = gate;
            try {
                h.reader.apply(fc -> {
                    var messages = List.of("producer", "consumer").stream().map(payload ->
                            new DeserializingMessage(new Message(payload), MessageType.COMMAND, fc.serializer())).toList();
                    DeserializingMessage.forEachInBatch(messages, message -> {
                        if (DeserializingMessage.getMessageBatchIndex() == 0) {
                            producer.set(fc.executeModelCommit(new Message(new SetDocument("one", null, "pending-alias", 1))));
                            assertFalse(producer.get().isDone());
                        } else {
                            consumer.set(fc.executeModelCommit(new Message(new PausedDocumentDelete(
                                    "receipt", true, () -> {
                                        assertEquals("pending-alias", graph(false, "one").get().alias(),
                                                     "The consumer must actually read its provisional producer");
                                        h.run(deletion);
                                    }, invocations))));
                            assertTrue(consumer.get().isCompletedExceptionally(),
                                       "Pinned document loss must fail before the pending producer settles");
                            h.commitGate = null;
                            gate.complete(null);
                        }
                    });
                    return null;
                });
            } finally {
                h.commitGate = null;
                gate.complete(null);
            }
            producer.get().orTimeout(5, TimeUnit.SECONDS).join();
            var failure = assertThrows(Exception.class, () -> consumer.get().join());
            assertInstanceOf(ModelCommitConflictException.class, io.fluxzero.common.ObjectUtils.unwrapException(failure));
            assertEquals(1, invocations.get());
            assertEquals(1, h.commits.get(), "Only the producer submits a commit");
            assertNull(h.writer.apply(fc -> Fluxzero.loadModel("receipt", DocumentReceipt.class).get()));
        }
    }

    @ParameterizedTest
    @CsvSource({"none,false", "scalar,false", "before,false", "apply,false", "after,false", "helper,false", "interceptHelper,false",
                "none,true", "apply,true"})
    void documentBoundaryIsAcquiredOnlyWhenUsed(String phase, boolean direct) {
        try (var h = new Harness()) {
            write(h.writer, new SeedDocumentReceipt("receipt"));
            h.set(1);
            AtomicInteger invocations = new AtomicInteger();
            write(h.reader, direct ? new DirectReceiptWrite(new DocumentReceiptId("receipt"), phase.equals("apply"), () -> {}, invocations)
                    : new LazyReceiptWrite("receipt", phase, () -> {}, invocations, false));
            assertEquals(phase.equals("none") || phase.equals("scalar") ? 0 : 1, h.receiptHeads.get());
            assertEquals(1, invocations.get());
            assertEquals(1, h.commits.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,apply", "false,true,apply", "true,false,apply", "false,false,applyHelper"})
    void lazyVerificationRetriesTheCompleteDecision(boolean direct, boolean swallow, String phase) {
        try (var h = new Harness()) {
            write(h.writer, new SeedDocumentReceipt("receipt"));
            h.set(1);
            AtomicInteger invocations = new AtomicInteger();
            AtomicReference<Runnable> change = new AtomicReference<>(
                    () -> write(h.writer, new SetDocumentReceipt("receipt", 20)));
            Runnable gate = () -> h.run(change);
            write(h.reader, direct ? new DirectReceiptWrite(new DocumentReceiptId("receipt"), true, gate, invocations)
                    : new LazyReceiptWrite("receipt", phase, gate, invocations, swallow));
            assertEquals(2, invocations.get(), "The stale decision, including assertions, must be reevaluated");
            assertEquals(1, h.commits.get(), "The failed preparation must not submit a commit");
            assertEquals(new DocumentReceipt("receipt", 21),
                    h.writer.apply(fc -> Fluxzero.loadModel("receipt", DocumentReceipt.class).get()));
        }
    }

    @Test
    void customConflictResolverRetainsEagerPreparation() {
        try (var h = new Harness(builder -> builder.configureModelConflictHandling(
                ModelConflictPolicy.RETRY, ModelConflictResolver.fail(), 3))) {
            write(h.writer, new SeedDocumentReceipt("receipt"));
            write(h.reader, new LazyReceiptWrite("receipt", "none", () -> {}, new AtomicInteger(), false));
            assertEquals(1, h.receiptHeads.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"create", "delete", "recreate"})
    void deferredProofIncludesAbsenceTombstonesAndNewLifecycles(String change) {
        try (var h = new Harness()) {
            if (!change.equals("create")) { write(h.writer, new SeedDocumentReceipt("receipt")); }
            h.set(1);
            AtomicInteger invocations = new AtomicInteger();
            AtomicReference<Runnable> update = new AtomicReference<>(() -> {
                if (!change.equals("create")) { write(h.writer, new DeleteDocumentReceipt("receipt")); }
                if (!change.equals("delete")) { write(h.writer, new SeedDocumentReceipt("receipt")); }
            });
            write(h.reader, new NullableLazyReceiptWrite("receipt", () -> h.run(update), invocations));
            assertEquals(2, invocations.get());
            assertEquals(1, h.commits.get());
            assertEquals(new DocumentReceipt("receipt", 1),
                    h.writer.apply(fc -> Fluxzero.loadModel("receipt", DocumentReceipt.class).get()));
        }
    }

    @Test
    void lazyPreparationConsumesTheSameRetryBudgetAsCommitConflicts() {
        try (var h = new Harness(builder -> builder.configureModelConflictHandling(
                ModelConflictPolicy.RETRY, ModelConflictResolver.retryIfAllowed(), 1))) {
            write(h.writer, new SeedDocumentReceipt("receipt"));
            h.set(1);
            AtomicReference<Runnable> change = new AtomicReference<>(
                    () -> write(h.writer, new SetDocumentReceipt("receipt", 20)));
            h.beforeCommit.set(() -> write(h.writer, new SetDocumentReceipt("receipt", 30)));
            AtomicInteger invocations = new AtomicInteger();
            var failure = assertThrows(Exception.class, () -> write(h.reader,
                    new LazyReceiptWrite("receipt", "apply", () -> h.run(change), invocations, false)));
            assertTrue(hasCause(failure, ModelCommitConflictException.class), failure.toString());
            assertEquals(2, invocations.get());
            assertEquals(1, h.commits.get());
            assertEquals(30, h.writer.apply(fc -> Fluxzero.loadModel("receipt", DocumentReceipt.class).get().observed()).intValue());
        }
    }

    @ParameterizedTest
    @CsvSource({"FAIL", "ACCEPT"})
    void applyPolicyOverrideRetainsEagerPreparation(ModelConflictPolicy policy) {
        try (var h = new Harness()) {
            write(h.writer, new SeedDocumentReceipt("receipt"));
            write(h.reader, policy == ModelConflictPolicy.FAIL ? new FailingReceiptWrite("receipt")
                    : new AcceptingReceiptWrite("receipt"));
            assertEquals(1, h.receiptHeads.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,create", "true,create", "false,replace", "true,replace", "false,delete", "true,delete"})
    void unpinnedCurrentRootResolvesCoherently(boolean current, String change) {
        try (var h = new Harness()) {
            if (!change.equals("create")) { h.set(1); }
            h.afterHead.set(() -> h.set(change.equals("delete") ? null : 2));
            var graph = h.reader.apply(fc -> graph(current, "one"));
            Document expected = change.equals("delete") || current && change.equals("create")
                    ? null : new Document("one", null, "alias", 2);
            assertEquals(expected, graph.get());
            assertEquals(1, h.gates.get(), "The concurrent writer must run between the two storage reads");
            assertEquals(expected, graph.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void documentChangeDuringMutationReadNeverAdvancesOnlyTheGraph(boolean assertion, boolean current) {
        try (var h = new Harness()) {
            h.set(1);
            h.beforeDocument.set(() -> h.set(2));
            var failure = assertThrows(Exception.class,
                    () -> write(h.reader, new CheckedWrite("receipt", "one", assertion, current)));
            assertTrue(hasCause(failure, EventSourcingException.class), failure.toString());
            assertEquals(1, h.gates.get(), "The mutation's namespaced document client must hit the gate");
            assertEquals(0, h.commits.get(), "No write may commit from a moved pinned document boundary");
            assertNull(h.writer.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get()));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void documentChangeAfterEvaluationRetriesTheWholeMutation(boolean assertion, boolean current) {
        try (var h = new Harness()) {
            h.set(1);
            h.beforeCommit.set(() -> h.set(2));
            write(h.reader, new CheckedWrite("receipt", "one", assertion, current));
            assertEquals(1, h.gates.get());
            assertEquals(2, h.commits.get());
            assertEquals(new Receipt("receipt", 2),
                    h.writer.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get()));
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void retryRerunsTheAssertionAndRejectsTheChangedDecision(boolean current) {
        try (var h = new Harness()) {
            h.set(1);
            h.beforeCommit.set(() -> h.set(2));
            var failure = assertThrows(Exception.class,
                    () -> write(h.reader, new RequireOriginalDocument("receipt", current)));
            assertTrue(hasCause(failure, IllegalCommandException.class), failure.toString());
            assertEquals(1, h.gates.get());
            assertEquals(1, h.commits.get(), "Only the first, conflicted commit may reach storage");
            assertNull(h.writer.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get()));
        }
    }

    @Test
    void currentDocumentMutationDoesNotReplayPublishedHistory() {
        try (var h = new Harness()) {
            write(h.writer, new UpdatePublishedDocument("published"));
            h.forbidReplay = true;
            write(h.reader, new UpdatePublishedDocument("published"));
            assertEquals(new PublishedDocument("published", 2),
                    h.writer.apply(fc -> Fluxzero.loadCurrentModelState("published", PublishedDocument.class).get()));
            assertEquals(1, h.documentReads.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"create", "replace", "delete"})
    void documentPreparationRetriesBeforeAnyApply(String change) {
        try (var h = new Harness()) {
            if (!change.equals("create")) { h.set(1); }
            h.afterHead.set(() -> h.set(change.equals("delete") ? null : 2));
            var invocations = new AtomicInteger();
            write(h.reader, new IncrementDocument("one", invocations));
            assertEquals(1, h.gates.get());
            assertEquals(1, invocations.get(), "Preparation retries must not execute user applies");
            assertEquals(1, h.commits.get());
            assertEquals(change.equals("delete") ? 10 : 12,
                    h.writer.apply(fc -> Fluxzero.loadCurrentGraph("one", Document.class).get().version()).intValue());
        }
    }

    @Test
    void secondDocumentRaceRestartsTheWholePreparation() {
        try (var h = new Harness()) {
            h.set(1);
            write(h.writer, new SeedDocumentReceipt("receipt"));
            h.beforeSecondDocument.set(() -> {
                h.set(2);
                write(h.writer, new SetDocumentReceipt("receipt", 2));
            });
            var invocations = new AtomicInteger();
            write(h.reader, new UpdatePair("one", "receipt", invocations));
            assertEquals(1, h.gates.get());
            assertEquals(2, invocations.get(), "Each apply runs once, after the complete context is coherent");
            assertEquals(1, h.commits.get());
            assertEquals(12, h.writer.apply(fc -> Fluxzero.loadCurrentGraph("one", Document.class).get().version()).intValue());
            assertEquals(12, h.writer.apply(fc -> Fluxzero.loadCurrentGraph("receipt", DocumentReceipt.class).get().observed()).intValue());
        }
    }

    @Test
    void unstableDocumentPreparationStopsBeforeApplyingOrCommitting() {
        try (var h = new Harness()) {
            h.set(1);
            h.afterHead.set(new Runnable() {
                int version = 1;
                @Override public void run() { h.set(++version); h.afterHead.set(this); }
            });
            var invocations = new AtomicInteger();
            var failure = assertThrows(Exception.class,
                    () -> write(h.reader, new IncrementDocument("one", invocations)));
            assertTrue(hasCause(failure, EventSourcingException.class));
            assertTrue(failure.getMessage().contains("8 attempts"), failure.toString());
            assertEquals(8, h.gates.get());
            assertEquals(0, invocations.get());
            assertEquals(0, h.commits.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,replace", "true,replace", "false,delete", "true,delete", "false,recreate", "true,recreate"})
    void lazyDocumentDescendantNeverMixesLaterStateIntoPinnedGraph(boolean current, String change) {
        try (var h = new Harness()) {
            write(h.writer, new SetRoot("root", 1));
            write(h.writer, new SetDocument("one", "root", "alias", 1));
            Graph<Root> graph = h.reader.apply(fc -> current ? Fluxzero.loadCurrentGraph("root", Root.class)
                    : Fluxzero.loadGraph("root", Root.class));
            assertEquals(new Root("root", 1), graph.get());
            var child = graph.children("children", Document.class).getFirst();
            long boundary = graph.stateIndex();
            assertEquals(0, h.documentReads.get(), "Selecting children must not materialize their documents");
            if (!change.equals("replace")) { write(h.writer, new SetDocument("one", "root", "alias", null)); }
            if (!change.equals("delete")) { write(h.writer, new SetDocument("one", "root", "alias", 2)); }
            write(h.writer, new SetRoot("root", 2));
            assertThrows(EventSourcingException.class, child::get);
            assertEquals(boundary, graph.stateIndex());
            assertEquals(new Root("root", 1), graph.get());
            assertEquals(1, graph.children("children", Document.class).size());
            var fresh = graph.current();
            assertEquals(new Root("root", 2), fresh.get());
            if (change.equals("delete")) { assertTrue(fresh.children("children", Document.class).isEmpty()); }
            else { assertEquals(2, fresh.children("children", Document.class).getFirst().get().version()); }
        }
    }

    @ParameterizedTest
    @CsvSource({"canonical,replace", "alias,replace", "canonical,delete", "alias,delete", "alias,move"})
    void currentRootUsesRealPendingBatchState(String lookup, String change) {
        try (var h = new Harness()) {
            h.set(1);
            var pending = new SetDocument("one", null, change.equals("move") ? "new-alias" : "alias",
                    change.equals("delete") ? null : 2);
            String requested = lookup.equals("canonical") ? "one" : change.equals("move") ? "new-alias" : "alias";
            var completion = new AtomicReference<CompletableFuture<Void>>();
            var retained = new AtomicReference<Graph<Document>>();
            var gate = new CompletableFuture<Void>();
            h.commitGate = gate;
            try {
                h.reader.apply(fc -> {
                    var messages = List.of("producer", "reader").stream().map(payload -> {
                        var message = new DeserializingMessage(new Message(payload), MessageType.COMMAND, fc.serializer());
                        message.getSerializedObject().setSegment(17);
                        return message;
                    }).toList();
                    DeserializingMessage.forEachInBatch(messages, message -> {
                        if (DeserializingMessage.getMessageBatchIndex() == 0) {
                            completion.set(fc.executeModelCommit(new Message(pending)));
                            assertFalse(completion.get().isDone());
                        } else {
                            // Reset after producer evaluation: only the consumer's unnecessary body reads are forbidden.
                            h.documentReads.set(0);
                            Graph<Document> current = Fluxzero.loadCurrentGraph(requested, Document.class);
                            retained.set(current);
                            assertEquals(change.equals("delete") ? null : new Document("one", null, pending.alias(), 2),
                                    current.get());
                            assertEquals(0, h.documentReads.get(), "Winning pending state must avoid durable body reads");
                            if (change.equals("move")) {
                                assertNull(Fluxzero.loadCurrentGraph("alias", Document.class).get());
                            }
                            // Foreign application must retain the durable value despite the caller's active batch.
                            assertEquals(1, h.writer.apply(other -> Fluxzero.loadCurrentGraph("one", Document.class).get().version()).intValue());
                            h.commitGate = null;
                            gate.complete(null);
                        }
                    });
                    return null;
                });
            } finally { h.commitGate = null; gate.complete(null); }
            completion.get().orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(change.equals("delete") ? null : new Document("one", null, pending.alias(), 2),
                    h.writer.apply(fc -> Fluxzero.loadCurrentGraph("one", Document.class).get()));
            assertEquals(change.equals("delete") ? null : new Document("one", null, pending.alias(), 2), retained.get().get());
        }
    }

    protected final class Harness implements AutoCloseable {
        final AtomicReference<Runnable> afterHead = new AtomicReference<>();
        final AtomicReference<Runnable> beforeDocument = new AtomicReference<>();
        final AtomicReference<Runnable> beforeCommit = new AtomicReference<>();
        final AtomicReference<Runnable> beforeSecondDocument = new AtomicReference<>();
        final AtomicInteger gates = new AtomicInteger(), commits = new AtomicInteger(), documentReads = new AtomicInteger();
        final AtomicInteger receiptHeads = new AtomicInteger();
        volatile CompletableFuture<Void> commitGate;
        boolean forbidReplay;
        final Fluxzero writer, reader;

        Harness() {
            this(builder -> {});
        }

        Harness(Consumer<FluxzeroBuilder> configure) {
            Client[] clients = clients("document-contract-" + UUID.randomUUID());
            writer = app(clients[0]);
            var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook();
            configure.accept(builder);
            reader = builder.build(instrument(clients[1]));
        }

        Client instrument(Client delegate) {
            return proxy(Client.class, delegate, (method, args, invoke) -> switch (method) {
                case "forNamespace" -> instrument((Client) invoke.get());
                case "getEventStoreClient" -> proxy(EventStoreClient.class, (EventStoreClient) invoke.get(),
                        (operation, parameters, call) -> {
                            if (operation.equals("getModelEvents") && ((GetModelEvents) parameters[0]).getRequests()
                                    .stream().anyMatch(request -> request.getModelId().equals("receipt"))) {
                                receiptHeads.incrementAndGet();
                            }
                            if (forbidReplay && operation.equals("getModelEvents")) {
                                assertTrue(((GetModelEvents) parameters[0]).getRequests().stream()
                                        .allMatch(request -> request.getMaxSize() == 0), "Current DOCUMENT writes must not replay");
                            }
                            if (operation.equals("commitModels")) {
                                commits.incrementAndGet(); run(beforeCommit);
                                CompletableFuture<Void> gate = commitGate;
                                if (gate != null) {
                                    var request = (CommitModels) parameters[0];
                                    var store = delegate.getEventStoreClient();
                                    return gate.thenCompose(ignored -> store.commitModels(request));
                                }
                            }
                            Object response = call.get();
                            if (operation.equals("getModelEvents") && ((GetModelEvents) parameters[0]).getRequests()
                                    .stream().anyMatch(request -> request.getModelId().equals("doc-one"))) { run(afterHead); }
                            return response;
                        });
                case "getSearchClient" -> proxy(SearchClient.class, (SearchClient) invoke.get(),
                        (operation, parameters, call) -> {
                            if (operation.equals("fetchModelDocument")) {
                                if (documentReads.incrementAndGet() == 2) { run(beforeSecondDocument); }
                                if (((GetDocument) parameters[0]).getId().equals("doc-one")) { run(beforeDocument); }
                            }
                            return call.get();
                        });
                default -> invoke.get();
            });
        }

        void run(AtomicReference<Runnable> gate) {
            Runnable action = gate.getAndSet(null);
            if (action != null) {
                gates.incrementAndGet();
                CompletableFuture.runAsync(action, task -> Thread.ofVirtual().name("document-contract-writer").start(task))
                        .orTimeout(5, TimeUnit.SECONDS).join();
            }
        }

        void set(Integer version) { write(writer, new SetDocument("one", null, "alias", version)); }
        @Override public void close() { reader.close(); writer.close(); }
    }

    @FunctionalInterface interface Call { Object get() throws Throwable; }
    @FunctionalInterface interface Intercept { Object invoke(String name, Object[] args, Call delegate) throws Throwable; }
    private static <T> T proxy(Class<T> type, T target, Intercept interceptor) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
                interceptor.invoke(method.getName(), args, () -> {
                    try { return method.invoke(target, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                })));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) { if (type.isInstance(cause)) return true; }
        return false;
    }
    private static Fluxzero app(Client client) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
    }
    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }
    private static Graph<Document> graph(boolean current, String id) {
        return current ? Fluxzero.loadCurrentGraph(id, Document.class) : Fluxzero.loadGraph(id, Document.class);
    }

    @Model(name = "DocumentGraphContractRoot") public record Root(@EntityId String rootId, int version) {}
    public record SetRoot(String rootId, int version) {
        @Apply Root apply(@Nullable Root previous) { return new Root(rootId, version); }
    }
    @Model(name = "DocumentGraphContractDocument", persistence = ModelPersistence.DOCUMENT,
           eventPublication = EventPublication.NEVER)
    public record Document(@EntityId(prefix = "doc-") String id,
                           @Parent(value = Root.class, pathInParent = "children") String rootId,
                           @Alias String alias, int version) {}
    public record SetDocument(String id, String rootId, String alias, Integer version) {
        @Apply Document apply(@Nullable Document previous) {
            return version == null ? null : new Document(id, rootId, alias, version);
        }
    }
    @Model(name = "DocumentGraphContractReceipt") public record Receipt(@EntityId String receiptId, int observed) {}

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void documentOnlyWriteCanReadAnExistingDocumentGraphWithoutConcurrency(boolean existing, boolean warm) {
        try (var h = new Harness()) {
            if (existing) { write(h.writer, new SeedDocumentReceipt("receipt")); }
            if (warm) { h.reader.apply(fc -> Fluxzero.loadModel("receipt", DocumentReceipt.class).get()); }
            h.set(1);
            write(h.reader, new CreateDocumentReceipt("receipt"));
            assertEquals(new DocumentReceipt("receipt", 1),
                    h.writer.apply(fc -> Fluxzero.loadModel("receipt", DocumentReceipt.class).get()));
        }
    }
    @Model(name = "DocumentGraphContractDocumentReceipt", persistence = ModelPersistence.DOCUMENT,
           eventPublication = EventPublication.NEVER)
    public record DocumentReceipt(@EntityId String receiptId, int observed) {
        @Apply DocumentReceipt update(DirectReceiptWrite command) {
            command.invocations.incrementAndGet();
            command.gate.run();
            if (command.read) { checkDocument(); }
            return new DocumentReceipt(receiptId, observed + 1);
        }
    }
    public static final class DocumentReceiptId extends Id<DocumentReceipt> {
        public DocumentReceiptId(String id) { super(id); }
    }
    public record DirectReceiptWrite(DocumentReceiptId receiptId, boolean read, @JsonIgnore Runnable gate,
                                     @JsonIgnore AtomicInteger invocations) {}
    public record LazyReceiptWrite(String receiptId, String phase, @JsonIgnore Runnable gate,
                                   @JsonIgnore AtomicInteger invocations, boolean swallow) {
        @AssertLegal void before() {
            if (phase.equals("before")) { checkDocument(); }
            if (phase.equals("helper")) { Fluxzero.assertLegal(new DocumentCheck()); }
            if (phase.equals("interceptHelper")) { Fluxzero.assertLegal(new InterceptedDocumentCheck()); }
            if (phase.equals("scalar")) { Fluxzero.assertLegal(new ScalarCheck()); }
        }
        @AssertLegal(afterHandler = true) void after() { if (phase.equals("after")) { checkDocument(); } }
        @Apply DocumentReceipt apply(DocumentReceipt previous) {
            invocations.incrementAndGet();
            gate.run();
            try {
                if (phase.equals("apply")) { checkDocument(); }
                if (phase.equals("applyHelper")) { Fluxzero.assertLegal(new DocumentCheck()); }
            }
            catch (RuntimeException failure) { if (!swallow) { throw failure; } }
            return new DocumentReceipt(receiptId, previous.observed() + 1);
        }
    }
    public record DocumentCheck() { @AssertLegal void check() { checkDocument(); } }
    public record InterceptedDocumentCheck() {
        @InterceptApply DocumentCheck intercept() { checkDocument(); return new DocumentCheck(); }
    }
    public record ScalarCheck() { @AssertLegal void check() { assertEquals(2, 1 + 1); } }
    public record NullableLazyReceiptWrite(String receiptId, @JsonIgnore Runnable gate,
                                          @JsonIgnore AtomicInteger invocations) {
        @Apply DocumentReceipt apply(@Nullable DocumentReceipt previous) {
            invocations.incrementAndGet();
            gate.run();
            checkDocument();
            return new DocumentReceipt(receiptId, previous == null ? 1 : previous.observed() + 1);
        }
    }
    public record DeleteDocumentReceipt(String receiptId) {
        @Apply DocumentReceipt apply(DocumentReceipt previous) { return null; }
    }
    public record PausedDocumentDelete(String receiptId, boolean pinGraph, @JsonIgnore Runnable gate,
                                       @JsonIgnore AtomicInteger invocations) {
        @Apply DocumentReceipt apply(DocumentReceipt previous) {
            invocations.incrementAndGet();
            assertNotNull(previous);
            if (pinGraph) { checkDocument(); }
            gate.run();
            return null;
        }
    }

    public record PausedRootDelete(String rootId, @JsonIgnore Runnable gate,
                                   @JsonIgnore AtomicInteger invocations) {
        @Apply Root apply(Root previous) {
            invocations.incrementAndGet();
            assertEquals(previous, Fluxzero.loadGraph(rootId, Root.class).get());
            gate.run();
            return null;
        }
    }
    public record FailingReceiptWrite(String receiptId) {
        @Apply(conflictPolicy = ModelConflictPolicy.FAIL)
        DocumentReceipt apply(DocumentReceipt previous) { return new DocumentReceipt(receiptId, previous.observed() + 1); }
    }
    public record AcceptingReceiptWrite(String receiptId) {
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        DocumentReceipt apply(DocumentReceipt previous) { return new DocumentReceipt(receiptId, previous.observed() + 1); }
    }
    private static void checkDocument() {
        Graph<Document> first = graph(false, "one");
        assertEquals(1, first.get().version());
        Graph<Document> second = graph(true, "one");
        assertEquals(first.stateIndex(), second.stateIndex());
        assertEquals(first.get(), second.get());
        assertEquals(first.get(), Fluxzero.loadGraph("doc-one").get());
        assertEquals(first.stateIndex(), first.current().stateIndex());
    }
    public record CreateDocumentReceipt(String receiptId) {
        @Apply DocumentReceipt apply(@Nullable DocumentReceipt previous) {
            return new DocumentReceipt(receiptId, graph(false, "one").get().version());
        }
    }
    public record SeedDocumentReceipt(String receiptId) {
        @Apply DocumentReceipt apply() { return new DocumentReceipt(receiptId, 0); }
    }
    public record SetDocumentReceipt(String receiptId, int observed) {
        @Apply DocumentReceipt apply(DocumentReceipt previous) { return new DocumentReceipt(receiptId, observed); }
    }
    public record IncrementDocument(String id, @JsonIgnore AtomicInteger invocations) {
        @Apply Document apply(@Nullable Document previous) {
            invocations.incrementAndGet();
            return new Document(id, null, "alias", previous == null ? 10 : previous.version() + 10);
        }
    }
    public record UpdatePair(String id, String receiptId, @JsonIgnore AtomicInteger invocations) {
        @Apply Document document(Document previous) {
            invocations.incrementAndGet();
            return new Document(id, null, "alias", previous.version() + 10);
        }
        @Apply DocumentReceipt receipt(DocumentReceipt previous) {
            invocations.incrementAndGet();
            return new DocumentReceipt(receiptId, previous.observed() + 10);
        }
    }
    @Model(name = "DocumentGraphContractPublishedDocument", persistence = ModelPersistence.DOCUMENT, cached = false)
    public record PublishedDocument(@EntityId String id, int value) {}
    public record UpdatePublishedDocument(String id) {
        @Apply PublishedDocument apply(@Nullable PublishedDocument previous) {
            return new PublishedDocument(id, previous == null ? 1 : previous.value() + 1);
        }
    }
    public record CheckedWrite(String receiptId, String documentId, boolean assertion, boolean current) {
        @AssertLegal void check() {
            if (assertion) { assertTrue(graph(current, documentId).get().version() > 0); }
        }
        @Apply Receipt apply() { return new Receipt(receiptId, graph(current, documentId).get().version()); }
    }
    public record RequireOriginalDocument(String receiptId, boolean current) {
        @AssertLegal void check() {
            if (graph(current, "one").get().version() != 1) { throw new IllegalCommandException("Changed decision"); }
        }
        @Apply Receipt apply() { return new Receipt(receiptId, 1); }
    }
}
