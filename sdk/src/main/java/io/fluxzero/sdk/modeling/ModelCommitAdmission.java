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

import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient.ModelCommitBatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Orders overlapping process-local ACCEPT commits before their first physical attempt.
 *
 * <p>Registration is atomic for a commit's complete scope, so multi-model commits cannot deadlock. Readers that do
 * not modify the relationship view may proceed together, while a writer waits for all preceding readers and writers.
 * Independent scopes remain parallel. Runtime conflict validation remains authoritative for external writers.</p>
 */
final class ModelCommitAdmission implements AutoCloseable {
    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);

    private final Object monitor = new Object();
    private final Map<Key, State> states = new HashMap<>();
    private final Set<Ticket> tickets = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    Session open() {
        return new Session(this);
    }

    CompletableFuture<Optional<CommitModelsResult>> submit(
            Session session,
            Supplier<Scope> scopeSupplier,
            ModelCommitBatch batch,
            int slot,
            BiFunction<ModelCommitBatch, Integer,
                    CompletableFuture<Optional<CommitModelsResult>>> operation) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(scopeSupplier, "scopeSupplier");
        Objects.requireNonNull(operation, "operation");
        if (session.owner != this) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Model admission session belongs to another pipeline"));
        }

        Scope scope;
        try {
            scope = Objects.requireNonNull(scopeSupplier.get(), "Model commit admission scope returned null");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return submit(session, scope, batch, slot, operation);
    }

    private CompletableFuture<Optional<CommitModelsResult>> submit(
            Session session,
            Scope scope,
            ModelCommitBatch batch,
            int slot,
            BiFunction<ModelCommitBatch, Integer,
                    CompletableFuture<Optional<CommitModelsResult>>> operation) {
        Ticket ticket;
        CompletableFuture<Void> released = null;
        synchronized (monitor) {
            if (closed && session.ticket == null) {
                return CompletableFuture.failedFuture(closedFailure());
            }
            ticket = session.ticket;
            if (ticket == null) {
                ticket = register(scope);
                session.ticket = ticket;
                session.scope = scope;
            } else if (!session.scope.covers(scope)) {
                Scope expanded = session.scope.merge(scope);
                boolean started = ticket.started;
                released = releaseLocked(ticket);
                ticket = register(expanded);
                ticket.started = started;
                session.ticket = ticket;
                session.scope = expanded;
            }
        }
        complete(released);
        return run(ticket, batch, slot, operation);
    }

    private CompletableFuture<Optional<CommitModelsResult>> run(
            Ticket ticket,
            ModelCommitBatch batch,
            int slot,
            BiFunction<ModelCommitBatch, Integer,
                    CompletableFuture<Optional<CommitModelsResult>>> operation) {
        boolean waits = !ticket.grant.isDone();
        if (waits && batch != null && slot >= 0) {
            batch.skip(slot);
        }
        ticket.requestProgress();
        ModelCommitBatch effectiveBatch = waits ? null : batch;
        int effectiveSlot = waits ? -1 : slot;
        return ticket.grant.thenCompose(ignored -> {
            synchronized (monitor) {
                if (ticket.cancelled) {
                    return CompletableFuture.failedFuture(closedFailure());
                }
                ticket.started = true;
                ticket.transport = effectiveBatch;
            }
            CompletableFuture<Optional<CommitModelsResult>> result;
            try {
                result = Objects.requireNonNull(
                        operation.apply(effectiveBatch, effectiveSlot),
                        "Model commit operation returned null");
            } catch (Throwable failure) {
                completeFlight(ticket);
                return CompletableFuture.failedFuture(failure);
            }
            return result.whenComplete((value, failure) -> completeFlight(ticket));
        });
    }

    private void completeFlight(Ticket ticket) {
        synchronized (monitor) {
            ticket.transport = null;
        }
    }

    private Ticket register(Scope scope) {
        LinkedHashSet<Ticket> predecessors = null;
        LinkedHashSet<CompletableFuture<Void>> barriers = null;
        Ticket ticket = new Ticket(scope);
        for (Access access : scope.accesses()) {
            State state = states.computeIfAbsent(access.key(), ignored -> new State());
            if (state.writer != null) {
                if (predecessors == null) {
                    predecessors = new LinkedHashSet<>();
                    barriers = new LinkedHashSet<>();
                }
                predecessors.add(state.writer);
                barriers.add(state.writer.releaseFuture());
            }
            if (access.write()) {
                if (state.readers != null) {
                    if (predecessors == null && !state.readers.isEmpty()) {
                        predecessors = new LinkedHashSet<>();
                        barriers = new LinkedHashSet<>();
                    }
                    for (Ticket reader : state.readers) {
                        predecessors.add(reader);
                        barriers.add(reader.releaseFuture());
                    }
                    state.readers.clear();
                }
                state.writer = ticket;
            } else {
                state.addReader(ticket);
            }
        }
        if (predecessors != null) {
            ticket.predecessors = List.copyOf(predecessors);
            ticket.grant = barriers.size() == 1
                    ? barriers.getFirst()
                    : CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new));
        }
        tickets.add(ticket);
        return ticket;
    }

    void release(Session session) {
        if (session == null || session.owner != this) {
            return;
        }
        CompletableFuture<Void> released;
        synchronized (monitor) {
            Ticket ticket = session.ticket;
            if (ticket == null || ticket.released) {
                return;
            }
            session.ticket = null;
            released = releaseLocked(ticket);
        }
        complete(released);
    }

    private CompletableFuture<Void> releaseLocked(Ticket ticket) {
        ticket.released = true;
        tickets.remove(ticket);
        for (Access access : ticket.scope.accesses()) {
            State state = states.get(access.key());
            if (state == null) {
                continue;
            }
            if (state.writer == ticket) {
                state.writer = null;
            }
            if (state.readers != null) {
                state.readers.remove(ticket);
            }
            if (state.writer == null && (state.readers == null || state.readers.isEmpty())) {
                states.remove(access.key());
            }
        }
        return ticket.release;
    }

    private static void complete(CompletableFuture<Void> completion) {
        if (completion != null) {
            completion.complete(null);
        }
    }

    int activeScopes() {
        synchronized (monitor) {
            return tickets.size();
        }
    }

    @Override
    public void close() {
        List<Ticket> pending;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            pending = tickets.stream().filter(ticket -> !ticket.started).toList();
            pending.forEach(ticket -> ticket.cancelled = true);
        }
        pending.forEach(Ticket::requestProgress);
    }

    private static IllegalStateException closedFailure() {
        return new IllegalStateException("Model commit admission is closed");
    }

    record Key(String namespace, String modelId, boolean relationships) {
        Key {
            namespace = namespace == null ? "" : namespace;
            Objects.requireNonNull(modelId, "modelId");
        }
    }

    record Access(Key key, boolean write) {
        Access {
            Objects.requireNonNull(key, "key");
        }
    }

    static final class Scope {
        private final List<Access> accesses;

        private Scope(List<Access> accesses) {
            this.accesses = accesses;
        }

        static Scope of(Collection<Access> accesses) {
            LinkedHashMap<Key, Boolean> merged = new LinkedHashMap<>();
            accesses.forEach(access -> {
                Objects.requireNonNull(access, "access");
                merged.merge(access.key(), access.write(), Boolean::logicalOr);
            });
            return of(merged);
        }

        static Scope of(Map<Key, Boolean> accesses) {
            List<Access> normalized = new ArrayList<>(accesses.size());
            accesses.forEach((key, write) -> normalized.add(new Access(key, write)));
            return new Scope(List.copyOf(normalized));
        }

        static Builder builder() {
            return new Builder();
        }

        List<Access> accesses() {
            return accesses;
        }

        boolean covers(Scope other) {
            if (accesses.size() < other.accesses.size()) {
                return false;
            }
            Map<Key, Boolean> current = accessMap();
            return other.accesses.stream().allMatch(access -> {
                Boolean existing = current.get(access.key());
                return existing != null && (existing || !access.write());
            });
        }

        Scope merge(Scope other) {
            if (covers(other)) {
                return this;
            }
            LinkedHashMap<Key, Boolean> result = new LinkedHashMap<>(accessMap());
            other.accesses.forEach(access -> result.merge(access.key(), access.write(), Boolean::logicalOr));
            return of(result);
        }

        private Map<Key, Boolean> accessMap() {
            LinkedHashMap<Key, Boolean> result = LinkedHashMap.newLinkedHashMap(accesses.size());
            accesses.forEach(access -> result.put(access.key(), access.write()));
            return result;
        }

        static final class Builder {
            private Key first;
            private boolean firstWrite;
            private LinkedHashMap<Key, Boolean> multiple;

            void add(Key key, boolean write) {
                Objects.requireNonNull(key, "key");
                if (first == null) {
                    first = key;
                    firstWrite = write;
                    return;
                }
                if (multiple == null && first.equals(key)) {
                    firstWrite |= write;
                    return;
                }
                if (multiple == null) {
                    multiple = new LinkedHashMap<>();
                    multiple.put(first, firstWrite);
                }
                multiple.merge(key, write, Boolean::logicalOr);
            }

            Scope build() {
                if (multiple != null) {
                    return Scope.of(multiple);
                }
                return new Scope(first == null
                        ? List.of()
                        : List.of(new Access(first, firstWrite)));
            }
        }
    }

    static final class Session {
        private final ModelCommitAdmission owner;
        private Ticket ticket;
        private Scope scope;

        private Session(ModelCommitAdmission owner) {
            this.owner = owner;
        }

        CompletableFuture<Optional<CommitModelsResult>> submit(
                Supplier<Scope> scopeSupplier,
                ModelCommitBatch batch,
                int slot,
                BiFunction<ModelCommitBatch, Integer,
                        CompletableFuture<Optional<CommitModelsResult>>> operation) {
            return owner.submit(this, scopeSupplier, batch, slot, operation);
        }
    }

    private static final class State {
        private Ticket writer;
        private Set<Ticket> readers;

        private void addReader(Ticket ticket) {
            if (readers == null) {
                readers = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            readers.add(ticket);
        }
    }

    private static final class Ticket {
        private final Scope scope;
        private CompletableFuture<Void> release;
        private List<Ticket> predecessors = List.of();
        private CompletableFuture<Void> grant = COMPLETED;
        private ModelCommitBatch transport;
        private boolean started;
        private boolean cancelled;
        private boolean released;

        private Ticket(Scope scope) {
            this.scope = scope;
        }

        private CompletableFuture<Void> releaseFuture() {
            if (release == null) {
                release = new CompletableFuture<>();
            }
            return release;
        }

        private void requestProgress() {
            predecessors.forEach(Ticket::flush);
        }

        private void flush() {
            ModelCommitBatch batch = transport;
            if (batch != null) {
                batch.flush();
            }
        }
    }

}
