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
    private static final ThreadLocal<CommitAttempt> currentDependency =
            ThreadLocalContext.create();

    private final ConcurrentHashMap<ModelKey, ConcurrentLinkedDeque<CommitAttempt>> values =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Batch> batches = new ConcurrentHashMap<>();

    private ModelBatchScope() {
    }

    private static void stage(
            String namespace,
            CommitAttempt evaluation,
            boolean trackedCompletion) {
        int position = DeserializingMessage.getMessageBatchIndex();
        if (position < 0 || evaluation.finalValues().isEmpty()) {
            return;
        }
        ModelBatchScope scope = DeserializingMessage.computeForMessageBatchIfAbsent(
                RESOURCE_KEY, ignored -> new ModelBatchScope());
        if (scope == null) {
            return;
        }
        if (trackedCompletion) {
            evaluation.stepMessages().forEach(message ->
                    message.putContext(CommitDependency.class, evaluation));
        }
        Map<String, Object> before = new HashMap<>();
        Map<String, Long> sequences = new HashMap<>();
        evaluation.transitions().forEach(transition -> {
            before.putIfAbsent(transition.modelId(), transition.before());
            sequences.putIfAbsent(
                    transition.modelId(), transition.beforeSequenceNumber());
        });
        int segment = currentSegment();
        String effectiveNamespace = normalize(namespace);
        evaluation.stageAt(position, segment, trackedCompletion);
        evaluation.finalValues().forEach((modelId, value) -> {
            Object previous = before.get(modelId);
            Class<?> type = value != null ? value.getClass()
                    : previous != null ? previous.getClass()
                            : evaluation.readModelTypes().getOrDefault(modelId, Object.class);
            scope.stageModel(
                    evaluation,
                    effectiveNamespace, modelId, type, previous, value,
                    sequences.getOrDefault(modelId, -1L), position, segment);
        });
        evaluation.stagedKeys().forEach(key ->
                scope.candidates(effectiveNamespace, key).addFirst(evaluation));
    }

    static void stage(
            String namespace,
            CommitAttempt evaluation) {
        stage(namespace, evaluation, false);
    }

    static CompletableFuture<Object> stagePending(
            String namespace,
            CommitAttempt evaluation) {
        stage(namespace, evaluation, true);
        return evaluation.completion();
    }

    private static <T> T withDependency(CommitAttempt dependency, Supplier<T> action) {
        CommitAttempt previous = currentDependency.get();
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
                message.getContext(CommitDependency.class)
                        .map(CommitDependency::attempt).orElse(null), action);
    }

    static CompletableFuture<Object> execute(
            Object batchKey,
            DeserializingMessage message,
            ModelCommitPolicy policy,
            BatchLifecycle lifecycle,
            boolean asynchronousReevaluation,
            Evaluator evaluator,
            CommitStage commitStage) {
        CommitAttempt entry = policy == null || DeserializingMessage.getCurrent() == null
                      || !policy.commitAfterBatch() && !policy.awaitAfterBatch()
                ? new CommitAttempt()
                : register(batchKey, message, policy, lifecycle);
        ThreadLocalContext.Snapshot context = message.captureContext();
        CommitAttempt initial;
        try {
            initial = context.supply(() -> withDependency(
                    entry, () -> evaluator.evaluate(entry, false, entry.batched())));
            if (initial != entry) {
                throw new IllegalStateException("Model evaluation replaced its commit attempt");
            }
            stage(namespace(message), initial, true);
            entry.initialize(initial.readModelIds());
        } catch (Throwable failure) {
            entry.fail(failure);
            return entry.completion();
        }
        try {
            CompletableFuture<Object> submitted = submit(
                    entry, initial, context, asynchronousReevaluation,
                    evaluator, commitStage);
            entry.bind(submitted);
        } catch (Throwable failure) {
            entry.fail(failure);
        }
        return entry.completion();
    }

    private static CommitAttempt register(
            Object key, DeserializingMessage message,
            ModelCommitPolicy policy, BatchLifecycle lifecycle) {
        ModelBatchScope scope = DeserializingMessage.computeForMessageBatchIfAbsent(
                RESOURCE_KEY, ignored -> new ModelBatchScope());
        if (scope == null) {
            return new CommitAttempt();
        }
        Batch batch = scope.batches.computeIfAbsent(key, ignored -> {
            Batch created = new Batch(lifecycle);
            DeserializingMessage.whenBatchCompletes(created::close);
            return created;
        });
        return batch.register(message, policy);
    }

    private static CompletableFuture<Object> submit(
            CommitAttempt entry,
            CommitAttempt initial,
            ThreadLocalContext.Snapshot context,
            boolean asynchronousReevaluation,
            Evaluator evaluator,
            CommitStage commitStage) {
        if (entry.hasDependencies()) {
            if (entry.batched() && !entry.policy().commitAfterBatch()
                && entry.transportBatch() != null) {
                entry.flushTransport();
            }
            entry.detachTransport();
        }
        return entry.executeAfterRelease(dependent -> {
            CompletableFuture<CommitAttempt> ready = dependent
                    ? reevaluate(entry, context, asynchronousReevaluation, evaluator)
                    : CompletableFuture.completedFuture(initial);
            return ready.thenCompose(context.wrap(evaluation -> Objects.requireNonNull(
                    commitStage.commit(evaluation, entry.transportBatch(), entry.transportSlot()),
                    "Model pipeline commit stage returned null")));
        });
    }

    private static CompletableFuture<CommitAttempt> reevaluate(
            CommitAttempt entry,
            ThreadLocalContext.Snapshot context,
            boolean asynchronous,
            Evaluator evaluator) {
        int dependencyCount = entry.dependencyCount();
        Supplier<CommitAttempt> evaluation = () ->
                context.supply(() -> withDependency(
                        entry, () -> evaluator.evaluate(entry, true, entry.batched())));
        CompletableFuture<CommitAttempt> result = entry.batched() && asynchronous
                ? entry.dependencyCompletion().thenCompose(ignored ->
                        CompletableFuture.supplyAsync(context.wrap(evaluation)))
                : entry.dependencyCompletion().thenApply(ignored -> evaluation.get());
        return result.thenCompose(value -> entry.dependencyCount() == dependencyCount
                ? CompletableFuture.completedFuture(value)
                : reevaluate(entry, context, asynchronous, evaluator));
    }

    private static String namespace(DeserializingMessage message) {
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
        CommitAttempt lookup = scope == null ? null : scope.lookup(namespace, requestedId, false);
        if (lookup == null
            || !lookup.exact(requestedId) && durable.isPresent()
               && requestedId.equals(String.valueOf(durable.id()))) {
            return durable;
        }
        Object stagedValue = lookup.stagedValue(requestedId);
        Class<?> actualType = stagedValue != null ? stagedValue.getClass()
                : durable.isPresent() ? durable.type() : lookup.stagedType(requestedId);
        if (!Object.class.equals(requestedType)
            && !requestedType.isAssignableFrom(actualType)) {
            return durable;
        }
        String id = lookup.stagedAvailable(requestedId)
                ? lookup.stagedModelId(requestedId) : requestedId;
        if (durable instanceof ImmutableEntity<?> immutable) {
            return (Entity<T>) immutable.toBuilder()
                    .id(id).type((Class) actualType)
                    .idProperty(EntityMetadata.of(actualType).entityIdName())
                    .value(lookup.stagedAvailable(requestedId) ? stagedValue : null).build();
        }
        return (Entity<T>) ImmutableModelRoot.builder()
                .id(id).type((Class) actualType)
                .idProperty(EntityMetadata.of(actualType).entityIdName())
                .value(lookup.stagedAvailable(requestedId) ? stagedValue : null).build();
    }

    /** Overlays pending exact values without changing the durable context's pinned state boundary. */
    public static CommitAttempt overlayCurrent(
            String namespace,
            CommitAttempt durable) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return durable;
        }
        LinkedHashMap<String, Object> overlays = new LinkedHashMap<>();
        durable.modelIds().forEach(modelId -> {
            CommitAttempt value = scope.lookup(
                    namespace, modelId, true);
            if (value != null) {
                overlays.put(modelId, value.stagedValue(modelId));
            }
        });
        return overlays.isEmpty() ? durable : durable.withValues(overlays);
    }

    /** Returns pending exact values visible to the current message, in message order. */
    public static Map<String, Entity<?>> currentValues(String namespace) {
        ModelBatchScope scope = current();
        int position = DeserializingMessage.getMessageBatchIndex();
        if (scope == null || position < 0) {
            return Map.of();
        }
        String effectiveNamespace = normalize(namespace);
        List<Map.Entry<ModelKey, CommitAttempt>> visible = new ArrayList<>();
        scope.values.forEach((key, candidates) -> {
            CommitAttempt candidate = key.namespace().equals(effectiveNamespace)
                    ? visible(candidates, key.modelId(), position, true) : null;
            if (candidate != null && status(candidate) == Status.PENDING) {
                visible.add(Map.entry(key, candidate));
            }
        });
        visible.sort(Comparator
                .comparingInt((Map.Entry<ModelKey, CommitAttempt> entry) ->
                        entry.getValue().batchPosition())
                .thenComparing(entry -> entry.getKey().modelId()));
        LinkedHashMap<String, Entity<?>> result = new LinkedHashMap<>();
        visible.forEach(entry -> {
            CommitAttempt candidate = entry.getValue();
            dependOn(candidate);
            result.put(entry.getKey().modelId(),
                       stagedEntity(candidate, entry.getKey().modelId()));
        });
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    /** Returns one pending exact value; aliases are deliberately not resolved. */
    public static Entity<?> currentValue(String namespace, String modelId) {
        ModelBatchScope scope = current();
        CommitAttempt value = scope == null ? null : scope.lookup(namespace, modelId, true);
        return value == null ? null : stagedEntity(value, modelId);
    }

    static Map<String, Object> currentValues(
            String namespace,
            ModelDefinition.Resolution resolution) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        int position = DeserializingMessage.getMessageBatchIndex();
        String effectiveNamespace = normalize(namespace);
        if (resolution.hasAncestorDependencies()) {
            scope.values.forEach((key, candidates) -> {
                CommitAttempt candidate = key.namespace().equals(effectiveNamespace)
                        ? visible(candidates, key.modelId(), position, true) : null;
                if (candidate != null && status(candidate) != Status.FAILURE
                    && resolution.ancestorDependencies().stream().anyMatch(dependency ->
                            compatible(dependency.modelType(),
                                       candidate.stagedType(key.modelId())))) {
                    dependOn(candidate);
                    result.put(key.modelId(), candidate.stagedValue(key.modelId()));
                }
            });
        }
        List<String> pending = new ArrayList<>();
        resolution.models().forEach(target -> pending.add(target.modelId()));
        for (int index = 0; index < pending.size(); index++) {
            CommitAttempt value = scope.lookup(namespace, pending.get(index), true);
            String modelId = pending.get(index);
            if (value == null || result.containsKey(modelId)) {
                continue;
            }
            Object stagedValue = value.stagedValue(modelId);
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
            CommitAttempt context) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        context.modelIds().forEach(modelId -> {
            CommitAttempt value = scope.lookup(namespace, modelId, true);
            if (value != null) {
                result.put(modelId, value.stagedValue(modelId));
            }
        });
        return immutable(result);
    }

    private void stageModel(
            CommitAttempt attempt,
            String namespace,
            String modelId,
            Class<?> modelType,
            Object before,
            Object after,
            long sequenceNumber,
            int position,
            int segment) {
        ConcurrentLinkedDeque<CommitAttempt> exact = candidates(namespace, modelId);
        Set<String> beforeAliases = aliases(before, modelType);
        Set<String> afterAliases = aliases(after, modelType);
        attempt.stageModel(
                modelId, modelType, after,
                existedBefore(exact, modelId, before, position, segment),
                sequenceNumber, beforeAliases, afterAliases);
    }

    private ConcurrentLinkedDeque<CommitAttempt> candidates(
            String namespace,
            String modelId) {
        return values.computeIfAbsent(
                new ModelKey(namespace, modelId),
                ignored -> new ConcurrentLinkedDeque<>());
    }

    private CommitAttempt lookup(String namespace, String id, boolean exactOnly) {
        int position = DeserializingMessage.getMessageBatchIndex();
        if (position < 0) {
            return null;
        }
        ConcurrentLinkedDeque<CommitAttempt> candidates = values.get(
                new ModelKey(normalize(namespace), id));
        CommitAttempt candidate = visible(candidates, id, position, true);
        if (candidate == null && !exactOnly) {
            candidate = visible(candidates, id, position, false);
        }
        if (candidate == null || status(candidate) == Status.SUCCESS) {
            return null;
        }
        dependOn(candidate);
        return candidate;
    }

    private static CommitAttempt visible(
            Collection<CommitAttempt> candidates,
            String requestedId,
            int position,
            boolean exactOnly) {
        if (candidates == null) {
            return null;
        }
        CommitAttempt consumer = currentDependency.get();
        int segment = currentSegment();
        CommitAttempt result = null;
        for (CommitAttempt candidate : candidates) {
            if (candidate.batchPosition() > position
                || exactOnly && !candidate.exact(requestedId)
                || candidate.batchSegment() != segment
                || status(candidate) == Status.FAILURE
                || consumer == candidate) {
                continue;
            }
            if (result == null || candidate.batchPosition() > result.batchPosition()) {
                result = candidate;
            }
        }
        return result;
    }

    private static void dependOn(CommitAttempt producer) {
        if (producer == null || !producer.trackedCompletion()) {
            return;
        }
        CommitAttempt consumer = currentDependency.get();
        if (consumer != null) {
            if (consumer != producer) {
                consumer.dependsOn(producer);
            }
        } else if (DeserializingMessage.getCurrent() != null) {
            Invocation.awaitBeforeResultPublication(
                    DeserializingMessage.getCurrent(), producer.completion());
        }
    }

    private static Status status(CommitAttempt candidate) {
        if (!candidate.trackedCompletion() || !candidate.completion().isDone()) {
            return Status.PENDING;
        }
        CompletableFuture<?> completion = candidate.completion();
        return completion.isCompletedExceptionally() || completion.isCancelled()
                ? Status.FAILURE : Status.SUCCESS;
    }

    private static boolean existedBefore(
            Collection<CommitAttempt> candidates,
            String modelId,
            Object before,
            int position,
            int segment) {
        CommitAttempt previous = visible(candidates, modelId, position, true);
        return previous != null && previous.batchSegment() == segment
               && status(previous) == Status.PENDING
                ? previous.stagedExistedBefore(modelId) : before != null;
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
            CommitAttempt attempt, String modelId) {
        Class<?> type = attempt.stagedType(modelId);
        return ImmutableModelRoot.<Object>builder()
                .id(modelId).type((Class<Object>) type)
                .idProperty(EntityMetadata.validate(type).entityId().orElseThrow().name())
                .value(attempt.stagedValue(modelId))
                .sequenceNumber(attempt.stagedExistedBefore(modelId)
                                        ? Math.max(0L, attempt.stagedSequence(modelId)) : -1L)
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

    @FunctionalInterface
    interface Evaluator {
        CommitAttempt evaluate(CommitAttempt attempt, boolean retry, boolean batched);
    }

    @FunctionalInterface
    interface CommitStage {
        CompletableFuture<Object> commit(
                CommitAttempt evaluation,
                ModelCommitBatchingClient.ModelCommitBatch batch,
                int slot);
    }

    private static final class Batch {
        private final BatchLifecycle lifecycle;
        private final List<CommitAttempt> entries = new ArrayList<>();
        private ModelCommitBatchingClient.ModelCommitBatch readyTransport;
        private boolean transportSettled;
        private boolean closed;

        private Batch(BatchLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        private synchronized CommitAttempt register(
                DeserializingMessage message,
                ModelCommitPolicy policy) {
            if (closed) {
                return CommitAttempt.batched(policy, true, () -> settleTransport(null));
            }
            CommitAttempt entry = CommitAttempt.batched(
                    policy, !policy.commitAfterBatch(), () -> settleTransport(null));
            if (!policy.commitAfterBatch()) {
                if (readyTransport == null) {
                    readyTransport = lifecycle.readyBatch().get();
                }
                entry.transport(readyTransport, entries.size());
                if (lifecycle.awaitBeforeResultPublication().getAsBoolean()) {
                    Invocation.awaitBeforeResultPublication(message, entry.completion());
                }
            }
            entries.add(entry);
            return entry;
        }

        private void close(Throwable failure) {
            List<CommitAttempt> snapshot;
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
            List<CommitAttempt> deferred = snapshot.stream()
                    .filter(entry -> entry.policy().commitAfterBatch()).toList();
            if (!deferred.isEmpty()) {
                CompletableFuture.allOf(deferred.stream()
                                .map(CommitAttempt::initialization)
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
                    snapshot.stream().map(CommitAttempt::completion)
                            .toArray(CompletableFuture[]::new)));
        }

        private void release(
                List<CommitAttempt> all,
                List<CommitAttempt> deferred) {
            boolean sequential = all.stream().anyMatch(entry -> !entry.policy().async());
            Map<String, CommitAttempt> tails = new HashMap<>();
            CommitAttempt previous = null;
            for (CommitAttempt entry : all) {
                if (sequential && previous != null) {
                    entry.dependsOn(previous);
                }
                previous = entry;
                if (entry.resolvedModelIds() != null) {
                    entry.resolvedModelIds().forEach(modelId -> {
                        CommitAttempt predecessor = tails.put(modelId, entry);
                        if (predecessor != null) {
                            entry.dependsOn(predecessor);
                        }
                    });
                }
            }
            ModelCommitBatchingClient.ModelCommitBatch transport = deferred.stream()
                    .allMatch(entry -> entry.policy().async())
                    ? lifecycle.batch().apply(deferred.size()) : null;
            for (int index = 0; index < deferred.size(); index++) {
                deferred.get(index).transport(transport, index);
            }
            deferred.forEach(CommitAttempt::release);
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

    private record ModelKey(String namespace, String modelId) {
        private ModelKey {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(modelId, "modelId");
        }
    }

}
