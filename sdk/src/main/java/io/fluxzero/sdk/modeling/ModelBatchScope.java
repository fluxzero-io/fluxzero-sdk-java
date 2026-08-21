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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.tracking.handling.Invocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One message-batch-local model scope for read-your-writes, exact dependencies and commit release.
 * Pending values stay visible only at their namespace, routing segment and message position. Reading one registers its
 * producing entry as a predecessor; successful or failed entries immediately yield to authoritative storage.
 */
public final class ModelBatchScope {
    private static final Object RESOURCE_KEY = ModelBatchScope.class;
    private static final String APPLICATION_NAMESPACE = "\u0000";
    private static final ThreadLocal<CommitCoordination> currentDependency =
            ThreadLocalContext.create();

    private final ConcurrentHashMap<ModelKey, ConcurrentLinkedDeque<PendingValue>> values =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Batch> batches = new ConcurrentHashMap<>();

    private ModelBatchScope() {
    }

    static void stage(
            String namespace,
            CommitAttempt evaluation,
            CommitCoordination producer) {
        int position = DeserializingMessage.getMessageBatchIndex();
        if (position < 0 || evaluation.transitions().isEmpty()) {
            return;
        }
        ModelBatchScope scope = DeserializingMessage.computeForMessageBatchIfAbsent(
                RESOURCE_KEY, ignored -> new ModelBatchScope());
        if (scope == null) {
            return;
        }
        if (producer != null) {
            Dependency dependency = new Dependency(producer);
            evaluation.steps().forEach(step ->
                    step.message().putContext(Dependency.class, dependency));
        }
        Map<String, Change> first = new HashMap<>();
        Map<String, Change> last = new LinkedHashMap<>();
        evaluation.transitions().forEach(transition -> {
            first.putIfAbsent(transition.modelId(), transition);
            last.put(transition.modelId(), transition);
        });
        int segment = currentSegment();
        String effectiveNamespace = normalize(namespace);
        last.forEach((modelId, transition) -> {
            Change initial = first.get(modelId);
            Object value = transition.after();
            Object previous = initial.before();
            Class<?> type = value != null ? value.getClass()
                    : previous != null ? previous.getClass()
                            : transition.modelType();
            scope.stageModel(
                    producer,
                    effectiveNamespace, modelId, type, previous, value,
                    initial.beforeSequenceNumber(), position, segment);
        });
    }

    static void stage(
            String namespace,
            CommitAttempt evaluation) {
        stage(namespace, evaluation, null);
    }

    static void stage(String namespace, CommitCoordination producer) {
        stage(namespace, producer.attempt(), producer);
    }

    static <T> T withDependency(CommitCoordination dependency, Supplier<T> action) {
        CommitCoordination previous = currentDependency.get();
        try {
            if (dependency == null) {
                currentDependency.remove();
            } else {
                currentDependency.set(dependency);
            }
            return action.get();
        } finally {
            if (previous == null) {
                currentDependency.remove();
            } else {
                currentDependency.set(previous);
            }
        }
    }

    static <T> T withMessageDependency(
            DeserializingMessage message,
            Supplier<T> action) {
        return withDependency(
                message.getContext(Dependency.class)
                        .map(Dependency::entry).orElse(null), action);
    }

    static CommitCoordination register(
            Object key, DeserializingMessage message,
            ModelCommitPolicy policy, BatchLifecycle lifecycle) {
        if (policy == null || DeserializingMessage.getCurrent() == null
            || !policy.commitAfterBatch() && !policy.awaitAfterBatch()) {
            return CommitCoordination.direct();
        }
        ModelBatchScope scope = DeserializingMessage.computeForMessageBatchIfAbsent(
                RESOURCE_KEY, ignored -> new ModelBatchScope());
        if (scope == null) {
            return CommitCoordination.direct();
        }
        Batch batch = scope.batches.computeIfAbsent(key, ignored -> {
            Batch created = new Batch(lifecycle);
            DeserializingMessage.whenBatchCompletes(created::close);
            return created;
        });
        return batch.register(message, policy);
    }

    static String namespace(DeserializingMessage message) {
        DeserializingMessage current = DeserializingMessage.getCurrent();
        return io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace(current == null ? message : current);
    }

    /** Overlays a durable direct load with the newest pending model or alias visible to the current message. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> Entity<T> overlayCurrent(
            String namespace,
            String requestedId,
            Class<T> requestedType,
            Entity<T> durable) {
        ModelBatchScope scope = current();
        PendingValue lookup = scope == null ? null : scope.lookup(namespace, requestedId, false);
        if (lookup == null
            || !lookup.modelId().equals(requestedId) && durable.isPresent()
               && requestedId.equals(String.valueOf(durable.id()))) {
            return durable;
        }
        Object stagedValue = lookup.value();
        Class<?> actualType = stagedValue != null ? stagedValue.getClass()
                : durable.isPresent() ? durable.type() : lookup.type();
        if (!Object.class.equals(requestedType)
            && !requestedType.isAssignableFrom(actualType)) {
            return durable;
        }
        String id = lookup.removed() ? requestedId : lookup.modelId();
        if (durable instanceof ImmutableEntity<?> immutable) {
            return (Entity<T>) immutable.toBuilder()
                    .id(id).type((Class) actualType)
                    .idProperty(EntityMetadata.of(actualType).entityIdName())
                    .value(lookup.removed() ? null : stagedValue).build();
        }
        return (Entity<T>) ImmutableModelRoot.builder()
                .id(id).type((Class) actualType)
                .idProperty(EntityMetadata.of(actualType).entityIdName())
                .value(lookup.removed() ? null : stagedValue).build();
    }

    /** Overlays pending exact values without changing the durable context's pinned state boundary. */
    public static CommitAttempt overlayCurrent(
            String namespace,
            CommitAttempt durable) {
        return durable.withValues(currentValues(namespace, durable.modelIds()));
    }

    /** Returns pending exact values visible to the current message, in message order. */
    public static Map<String, Entity<?>> currentValues(String namespace) {
        ModelBatchScope scope = current();
        int position = DeserializingMessage.getMessageBatchIndex();
        if (scope == null || position < 0) {
            return Map.of();
        }
        String effectiveNamespace = normalize(namespace);
        List<Map.Entry<ModelKey, PendingValue>> visible = new ArrayList<>();
        scope.values.forEach((key, candidates) -> {
            PendingValue candidate = key.namespace().equals(effectiveNamespace)
                    ? visible(candidates, key.modelId(), position, true) : null;
            if (candidate != null && status(candidate) == Status.PENDING) {
                visible.add(Map.entry(key, candidate));
            }
        });
        visible.sort(Comparator
                .comparingInt((Map.Entry<ModelKey, PendingValue> entry) ->
                        entry.getValue().position())
                .thenComparing(entry -> entry.getKey().modelId()));
        LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
        visible.forEach(entry -> {
            PendingValue candidate = entry.getValue();
            dependOn(candidate);
            result.put(entry.getKey().modelId(), stagedEntity(candidate));
        });
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    /** Returns one pending exact value; aliases are deliberately not resolved. */
    public static Entity<?> currentValue(String namespace, String modelId) {
        ModelBatchScope scope = current();
        PendingValue value = scope == null ? null : scope.lookup(namespace, modelId, true);
        return value == null ? null : stagedEntity(value);
    }

    static Map<String, Object> currentValues(
            String namespace,
            MutationPlan.Resolution resolution) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        int position = DeserializingMessage.getMessageBatchIndex();
        String effectiveNamespace = normalize(namespace);
        if (resolution.hasAncestorDependencies()) {
            scope.values.forEach((key, candidates) -> {
                PendingValue candidate = key.namespace().equals(effectiveNamespace)
                        ? visible(candidates, key.modelId(), position, true) : null;
                if (candidate != null && status(candidate) != Status.FAILURE
                    && resolution.ancestorDependencies().stream().anyMatch(dependency ->
                            compatible(dependency.modelType(),
                                       candidate.type()))) {
                    dependOn(candidate);
                    result.put(key.modelId(), candidate.value());
                }
            });
        }
        List<String> pending = new ArrayList<>();
        resolution.models().forEach(target -> pending.add(target.modelId()));
        for (int index = 0; index < pending.size(); index++) {
            PendingValue value = scope.lookup(namespace, pending.get(index), true);
            String modelId = pending.get(index);
            if (value == null || result.containsKey(modelId)) {
                continue;
            }
            Object stagedValue = value.value();
            result.put(modelId, stagedValue);
            if (stagedValue != null) {
                EntityMetadata.validate(stagedValue.getClass()).parentReferences().forEach(parent -> {
                    Object parentId = parent.read(stagedValue);
                    if (parentId != null) {
                        pending.add(parent.repositoryId(parentId));
                    }
                });
            }
        }
        return immutable(result);
    }

    static Map<String, Object> currentValues(
            String namespace,
            Collection<String> modelIds) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        modelIds.forEach(modelId -> {
            PendingValue value = scope.lookup(namespace, modelId, true);
            if (value != null) {
                result.put(modelId, value.value());
            }
        });
        return immutable(result);
    }

    private void stageModel(
            CommitCoordination producer,
            String namespace,
            String modelId,
            Class<?> modelType,
            Object before,
            Object after,
            long sequenceNumber,
            int position,
            int segment) {
        ConcurrentLinkedDeque<PendingValue> exact = candidates(namespace, modelId);
        Set<String> beforeAliases = aliases(before, modelType);
        Set<String> afterAliases = aliases(after, modelType);
        PendingValue value = new PendingValue(
                producer, modelId, modelType, after,
                existedBefore(exact, modelId, before, position, segment), sequenceNumber,
                position, segment, false);
        exact.addFirst(value);
        beforeAliases.stream().filter(alias -> !alias.equals(modelId) && !afterAliases.contains(alias))
                .forEach(alias -> candidates(namespace, alias).addFirst(value.removedAlias()));
        afterAliases.stream().filter(alias -> !alias.equals(modelId))
                .forEach(alias -> candidates(namespace, alias).addFirst(value));
    }

    private ConcurrentLinkedDeque<PendingValue> candidates(
            String namespace,
            String modelId) {
        return values.computeIfAbsent(
                new ModelKey(namespace, modelId),
                ignored -> new ConcurrentLinkedDeque<>());
    }

    private PendingValue lookup(String namespace, String id, boolean exactOnly) {
        int position = DeserializingMessage.getMessageBatchIndex();
        if (position < 0) {
            return null;
        }
        ConcurrentLinkedDeque<PendingValue> candidates = values.get(
                new ModelKey(normalize(namespace), id));
        PendingValue candidate = visible(candidates, id, position, true);
        if (candidate == null && !exactOnly) {
            candidate = visible(candidates, id, position, false);
        }
        if (candidate == null || status(candidate) == Status.SUCCESS) {
            return null;
        }
        dependOn(candidate);
        return candidate;
    }

    private static PendingValue visible(
            Collection<PendingValue> candidates,
            String requestedId,
            int position,
            boolean exactOnly) {
        if (candidates == null) {
            return null;
        }
        CommitCoordination consumer = currentDependency.get();
        int segment = currentSegment();
        PendingValue result = null;
        for (PendingValue candidate : candidates) {
            if (candidate.position() > position
                || exactOnly && !candidate.modelId().equals(requestedId)
                || candidate.segment() != segment
                || status(candidate) == Status.FAILURE
                || consumer != null && consumer == candidate.producer()) {
                continue;
            }
            if (result == null || candidate.position() > result.position()) {
                result = candidate;
            }
        }
        return result;
    }

    private static void dependOn(PendingValue producer) {
        if (producer == null || producer.producer() == null) {
            return;
        }
        CommitCoordination owner = producer.producer();
        CommitCoordination consumer = currentDependency.get();
        if (consumer != null) {
            if (consumer != owner) {
                consumer.dependsOn(owner);
            }
        } else if (DeserializingMessage.getCurrent() != null) {
            Invocation.awaitBeforeResultPublication(
                    DeserializingMessage.getCurrent(), owner.attempt().completion());
        }
    }

    private static Status status(PendingValue candidate) {
        if (candidate.producer() == null) {
            return Status.PENDING;
        }
        CompletableFuture<?> completion = candidate.producer().attempt().completion();
        if (!completion.isDone()) {
            return Status.PENDING;
        }
        return completion.isCompletedExceptionally() || completion.isCancelled()
                ? Status.FAILURE : Status.SUCCESS;
    }

    private static boolean existedBefore(
            Collection<PendingValue> candidates,
            String modelId,
            Object before,
            int position,
            int segment) {
        PendingValue previous = visible(candidates, modelId, position, true);
        return previous != null && previous.segment() == segment
               && status(previous) == Status.PENDING
                ? previous.existedBefore() : before != null;
    }

    private static Set<String> aliases(Object value, Class<?> type) {
        if (value == null || Object.class.equals(type)) {
            return Set.of();
        }
        List<String> aliases = EntityMetadata.validate(type).aliases(value);
        return aliases == null || aliases.isEmpty() ? Set.of() : Set.copyOf(aliases);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Entity<?> stagedEntity(
            PendingValue value) {
        Class<?> type = value.type();
        return ImmutableModelRoot.<Object>builder()
                .id(value.modelId()).type((Class<Object>) type)
                .idProperty(EntityMetadata.validate(type).entityId().orElseThrow().name())
                .value(value.value())
                .sequenceNumber(value.existedBefore()
                                        ? Math.max(0L, value.sequenceNumber()) : -1L)
                .build();
    }

    static boolean existedBefore(Entity<?> entity) {
        return entity instanceof PersistedRoot<?> root && root.sequenceNumber() >= 0L;
    }

    private static boolean compatible(Class<?> left, Class<?> right) {
        return left.isAssignableFrom(right) || right.isAssignableFrom(left);
    }

    private static Map<String, Object> immutable(LinkedHashMap<String, Object> values) {
        return values.isEmpty() ? Map.of() : Collections.unmodifiableMap(values);
    }

    private static ModelBatchScope current() {
        return DeserializingMessage.getMessageBatchResource(RESOURCE_KEY);
    }

    private static String normalize(String namespace) {
        return namespace == null ? APPLICATION_NAMESPACE : namespace;
    }

    private static int currentSegment() {
        if (DeserializingMessage.getMessageBatchIndex() >= 0) {
            return DeserializingMessage.getMessageBatchSegment();
        }
        DeserializingMessage message = DeserializingMessage.getCurrent();
        Integer segment = message == null ? null : message.getSerializedObject().getSegment();
        return segment == null ? -1 : segment;
    }

    record BatchLifecycle(
            Supplier<ModelCommitBatchingClient.ModelCommitBatch> readyBatch,
            Function<Integer, ModelCommitBatchingClient.ModelCommitBatch> batch,
            BooleanSupplier awaitBeforeResultPublication) {
    }

    /** Batch-owned coordination for one commit; mutation data and attempt status stay in {@link CommitAttempt}. */
    static final class CommitCoordination {
        private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);
        private final CommitAttempt attempt;
        final CompletableFuture<Void> initialized;
        private final CompletableFuture<Void> release;
        final ModelCommitPolicy policy;
        private final Runnable flushBatch;
        private volatile Set<CommitCoordination> dependencies;
        volatile Set<String> modelIds;
        volatile ModelCommitBatchingClient.ModelCommitBatch transport;
        volatile int slot = -1;

        private CommitCoordination(CommitAttempt attempt, ModelCommitPolicy policy,
                      CompletableFuture<Void> initialized,
                      CompletableFuture<Void> release, Runnable flushBatch) {
            this.attempt = Objects.requireNonNull(attempt, "attempt");
            this.policy = policy;
            this.initialized = initialized;
            this.release = release;
            this.flushBatch = flushBatch;
        }

        static CommitCoordination direct() {
            return direct(new CommitAttempt());
        }

        static CommitCoordination direct(CommitAttempt attempt) {
            return new CommitCoordination(attempt, null, COMPLETED, COMPLETED, null);
        }

        static CommitCoordination batched(ModelCommitPolicy policy, boolean released, Runnable flushBatch) {
            return new CommitCoordination(new CommitAttempt(), Objects.requireNonNull(policy),
                             new CompletableFuture<>(),
                             released ? COMPLETED : new CompletableFuture<>(),
                             Objects.requireNonNull(flushBatch));
        }

        CommitAttempt attempt() {
            return attempt;
        }

        boolean batched() {
            return policy != null;
        }

        void initialize(Collection<String> ids) {
            modelIds = batched() ? Set.copyOf(ids) : null;
            initialized.complete(null);
        }

        void dependsOn(CommitCoordination producer) {
            Set<CommitCoordination> current = dependencies;
            if (current == null) {
                synchronized (this) {
                    if ((current = dependencies) == null) {
                        dependencies = current = ConcurrentHashMap.newKeySet();
                    }
                }
            }
            current.add(producer);
        }

        boolean hasDependencies() {
            return dependencies != null && !dependencies.isEmpty();
        }

        int dependencyCount() {
            return dependencies == null ? 0 : dependencies.size();
        }

        CompletableFuture<Void> dependenciesComplete() {
            return !hasDependencies() ? COMPLETED : CompletableFuture.allOf(
                    dependencies.stream().map(CommitCoordination::attempt).map(CommitAttempt::completion)
                            .toArray(CompletableFuture[]::new));
        }

        void submit(Function<Boolean, CompletableFuture<Object>> action) {
            attempt.submit(() -> execute(action));
        }

        private CompletableFuture<Object> execute(Function<Boolean, CompletableFuture<Object>> action) {
            if (hasDependencies()) {
                if (batched() && !policy.commitAfterBatch() && transport != null) {
                    flushTransport();
                }
                detachTransport();
            }
            return release.thenCompose(ignored -> {
                boolean dependent = hasDependencies();
                if (dependent) {
                    detachTransport();
                }
                return dependenciesComplete().thenCompose(unused ->
                        Objects.requireNonNull(action.apply(dependent), "Model commit attempt returned null"));
            }).whenComplete((value, failure) -> settleTransport());
        }

        <T> CompletableFuture<T> afterDependencies(Supplier<T> action, boolean asynchronous) {
            int count = dependencyCount();
            CompletableFuture<T> result = asynchronous
                    ? dependenciesComplete().thenCompose(ignored -> CompletableFuture.supplyAsync(action))
                    : dependenciesComplete().thenApply(ignored -> action.get());
            return result.thenCompose(value -> dependencyCount() == count
                    ? CompletableFuture.completedFuture(value)
                    : afterDependencies(action, asynchronous));
        }

        void fail(Throwable failure) {
            initialized.completeExceptionally(failure);
            release.completeExceptionally(failure);
            settleTransport();
            attempt.fail(failure);
        }

        void release() {
            release.complete(null);
        }

        void transport(ModelCommitBatchingClient.ModelCommitBatch batch, int batchSlot) {
            transport = batch;
            slot = batchSlot;
        }

        synchronized void detachTransport() {
            settleTransport();
            transport = null;
            slot = -1;
        }

        void flushTransport() {
            if (flushBatch != null) {
                flushBatch.run();
            }
        }

        private void settleTransport() {
            if (transport != null) {
                transport.skip(slot);
            }
        }
    }

    private static final class Batch {
        private final BatchLifecycle lifecycle;
        private final List<CommitCoordination> entries = new ArrayList<>();
        private ModelCommitBatchingClient.ModelCommitBatch readyTransport;
        private boolean transportSettled;
        private boolean closed;

        private Batch(BatchLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        private synchronized CommitCoordination register(
                DeserializingMessage message,
                ModelCommitPolicy policy) {
            if (closed) {
                return CommitCoordination.batched(policy, true, () -> settleTransport(null));
            }
            CommitCoordination entry = CommitCoordination.batched(
                    policy, !policy.commitAfterBatch(), () -> settleTransport(null));
            if (!policy.commitAfterBatch()) {
                if (readyTransport == null) {
                    readyTransport = lifecycle.readyBatch().get();
                }
                entry.transport(readyTransport, entries.size());
                if (lifecycle.awaitBeforeResultPublication().getAsBoolean()) {
                    Invocation.awaitBeforeResultPublication(message, entry.attempt().completion());
                }
            }
            entries.add(entry);
            return entry;
        }

        private void close(Throwable failure) {
            List<CommitCoordination> snapshot;
            synchronized (this) {
                closed = true;
                snapshot = List.copyOf(entries);
            }
            if (failure != null) {
                settleTransport(failure);
                snapshot.forEach(entry -> entry.fail(failure));
                return;
            }
            settleTransport(null);
            if (snapshot.isEmpty()) {
                return;
            }
            List<CommitCoordination> deferred = snapshot.stream()
                    .filter(entry -> entry.policy.commitAfterBatch()).toList();
            if (!deferred.isEmpty()) {
                CompletableFuture.allOf(deferred.stream()
                                .map(entry -> entry.initialized)
                                .toArray(CompletableFuture[]::new))
                        .whenComplete((ignored, initializationFailure) -> {
                            if (initializationFailure == null) {
                                release(snapshot, deferred);
                            } else {
                                deferred.forEach(entry -> entry.fail(initializationFailure));
                            }
                        });
            }
            AsyncCompletionScope.register(CompletableFuture.allOf(
                    snapshot.stream().map(CommitCoordination::attempt).map(CommitAttempt::completion)
                            .toArray(CompletableFuture[]::new)));
        }

        private void release(
                List<CommitCoordination> all,
                List<CommitCoordination> deferred) {
            boolean sequential = all.stream().anyMatch(entry -> !entry.policy.async());
            Map<String, CommitCoordination> tails = new HashMap<>();
            CommitCoordination previous = null;
            for (CommitCoordination entry : all) {
                if (sequential && previous != null) {
                    entry.dependsOn(previous);
                }
                previous = entry;
                if (entry.modelIds != null) {
                    entry.modelIds.forEach(modelId -> {
                        CommitCoordination predecessor = tails.put(modelId, entry);
                        if (predecessor != null) {
                            entry.dependsOn(predecessor);
                        }
                    });
                }
            }
            ModelCommitBatchingClient.ModelCommitBatch transport = deferred.stream()
                    .allMatch(entry -> entry.policy.async())
                    ? lifecycle.batch().apply(deferred.size()) : null;
            for (int index = 0; index < deferred.size(); index++) {
                deferred.get(index).transport(transport, index);
            }
            deferred.forEach(CommitCoordination::release);
        }

        private synchronized void settleTransport(Throwable failure) {
            if (!transportSettled && readyTransport != null) {
                transportSettled = true;
                if (failure == null) {
                    readyTransport.flush();
                } else {
                    readyTransport.fail(failure);
                }
            }
        }
    }

    private enum Status { PENDING, SUCCESS, FAILURE }

    private record Dependency(CommitCoordination entry) {
    }

    private record PendingValue(
            CommitCoordination producer,
            String modelId,
            Class<?> type,
            Object value,
            boolean existedBefore,
            long sequenceNumber,
            int position,
            int segment,
            boolean removed) {

        private PendingValue removedAlias() {
            return new PendingValue(producer, modelId, type, null, existedBefore,
                                    sequenceNumber, position, segment, true);
        }
    }

    private record ModelKey(String namespace, String modelId) {
        private ModelKey {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(modelId, "modelId");
        }
    }

}
