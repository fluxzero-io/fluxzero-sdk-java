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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One message-batch-local model scope for read-your-writes, exact dependencies and commit release.
 * Pending values stay visible only at their namespace, routing segment and message position. Reading one registers its
 * producing operation as a predecessor; successful or failed operations immediately yield to authoritative storage.
 */
public final class ModelBatchScope {
    private static final Object RESOURCE_KEY = ModelBatchScope.class;
    private static final String APPLICATION_NAMESPACE = "\u0000";
    private static final ThreadLocal<Operation> currentDependency =
            ThreadLocalContext.create();

    private final ConcurrentHashMap<ModelKey, ConcurrentLinkedDeque<Candidate>> values =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Batch> batches = new ConcurrentHashMap<>();

    private ModelBatchScope() {
    }

    static void stage(
            String namespace,
            ModelExecutionPlan.CommitEvaluation evaluation,
            Operation producer) {
        int position = DeserializingMessage.getMessageBatchIndex();
        if (position < 0 || evaluation.finalValues().isEmpty()) {
            return;
        }
        ModelBatchScope scope = DeserializingMessage.computeForMessageBatchIfAbsent(
                RESOURCE_KEY, ignored -> new ModelBatchScope());
        if (scope == null) {
            return;
        }
        if (producer != null) {
            evaluation.substeps().forEach(substep ->
                    substep.message().putContext(Operation.class, producer));
        }
        Map<String, Object> before = new HashMap<>();
        evaluation.transitions().forEach(transition ->
                before.putIfAbsent(transition.modelId(), transition.before()));
        int segment = currentSegment();
        String effectiveNamespace = normalize(namespace);
        evaluation.finalValues().forEach((modelId, value) -> {
            Object previous = before.get(modelId);
            Class<?> type = value != null ? value.getClass()
                    : previous != null ? previous.getClass()
                            : evaluation.readModelTypes().getOrDefault(modelId, Object.class);
            scope.stageModel(
                    effectiveNamespace, modelId, type, previous, value,
                    position, segment, producer);
        });
    }

    static <T> T withDependency(Operation dependency, Supplier<T> action) {
        Operation previous = currentDependency.get();
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
                message.getContext(Operation.class).orElse(null), action);
    }

    static Operation register(
            Object key,
            DeserializingMessage message,
            ModelCommitPolicy policy,
            BatchLifecycle lifecycle) {
        ModelBatchScope scope = DeserializingMessage.computeForMessageBatchIfAbsent(
                RESOURCE_KEY, ignored -> new ModelBatchScope());
        if (scope == null) {
            return new Operation();
        }
        Batch batch = scope.batches.computeIfAbsent(key, ignored -> {
            Batch created = new Batch(lifecycle);
            DeserializingMessage.whenBatchCompletes(created::close);
            return created;
        });
        return batch.register(message, policy);
    }

    /** Overlays a durable direct load with the newest pending model or alias visible to the current message. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> Entity<T> overlayCurrent(
            String namespace,
            String requestedId,
            Class<T> requestedType,
            Entity<T> durable) {
        ModelBatchScope scope = current();
        Candidate lookup = scope == null ? null : scope.lookup(namespace, requestedId, false);
        if (lookup == null
            || !lookup.exact() && durable.isPresent()
               && requestedId.equals(String.valueOf(durable.id()))) {
            return durable;
        }
        Class<?> actualType = lookup.value() != null ? lookup.value().getClass()
                : durable.isPresent() ? durable.type() : lookup.modelType();
        if (!Object.class.equals(requestedType)
            && !requestedType.isAssignableFrom(actualType)) {
            return durable;
        }
        String id = lookup.available() ? lookup.modelId() : requestedId;
        if (durable instanceof ImmutableEntity<?> immutable) {
            return (Entity<T>) immutable.toBuilder()
                    .id(id).type((Class) actualType)
                    .idProperty(ModelMetadata.of(actualType).entityIdName())
                    .value(lookup.available() ? lookup.value() : null).build();
        }
        return (Entity<T>) ImmutableModelRoot.builder()
                .id(id).type((Class) actualType)
                .idProperty(ModelMetadata.of(actualType).entityIdName())
                .value(lookup.available() ? lookup.value() : null).build();
    }

    /** Overlays pending exact values without changing the durable context's pinned state boundary. */
    public static ModelCommitContext overlayCurrent(
            String namespace,
            ModelCommitContext durable) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return durable;
        }
        LinkedHashMap<String, Object> overlays = new LinkedHashMap<>();
        durable.entries().forEach(entry -> {
            Candidate value = scope.lookup(
                    namespace, entry.target().modelId(), true);
            if (value != null) {
                overlays.put(value.modelId(), value.available() ? value.value() : null);
            }
        });
        return overlays.isEmpty() ? durable : durable.withValues(overlays);
    }

    /** Returns pending exact values visible to the current message, in message order. */
    public static Map<String, StagedModel> currentValues(String namespace) {
        ModelBatchScope scope = current();
        int position = DeserializingMessage.getMessageBatchIndex();
        if (scope == null || position < 0) {
            return Map.of();
        }
        String effectiveNamespace = normalize(namespace);
        List<Map.Entry<ModelKey, Candidate>> visible = new ArrayList<>();
        scope.values.forEach((key, candidates) -> {
            Candidate candidate = key.namespace().equals(effectiveNamespace)
                    ? visible(candidates, position, true) : null;
            if (candidate != null && status(candidate) == Status.PENDING) {
                visible.add(Map.entry(key, candidate));
            }
        });
        visible.sort(Comparator
                .comparingInt((Map.Entry<ModelKey, Candidate> entry) -> entry.getValue().position())
                .thenComparing(entry -> entry.getKey().modelId()));
        LinkedHashMap<String, StagedModel> result = new LinkedHashMap<>();
        visible.forEach(entry -> {
            Candidate candidate = entry.getValue();
            dependOn(candidate.producer());
            result.put(entry.getKey().modelId(), candidate.staged());
        });
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    /** Returns one pending exact value; aliases are deliberately not resolved. */
    public static StagedModel currentValue(String namespace, String modelId) {
        ModelBatchScope scope = current();
        Candidate value = scope == null ? null : scope.lookup(namespace, modelId, true);
        return value == null ? null : new StagedModel(
                value.modelId(), value.modelType(), value.value(), value.existedBefore());
    }

    static Map<String, Object> currentValues(
            String namespace,
            ModelTargetResolver.Resolution resolution) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        int position = DeserializingMessage.getMessageBatchIndex();
        String effectiveNamespace = normalize(namespace);
        if (resolution.hasAncestorDependencies()) {
            scope.values.forEach((key, candidates) -> {
                Candidate candidate = key.namespace().equals(effectiveNamespace)
                        ? visible(candidates, position, true) : null;
                if (candidate != null && status(candidate) != Status.FAILURE
                    && resolution.ancestorDependencies().stream().anyMatch(dependency ->
                            compatible(dependency.modelType(), candidate.modelType()))) {
                    dependOn(candidate.producer());
                    result.put(candidate.modelId(), candidate.value());
                }
            });
        }
        List<String> pending = new ArrayList<>();
        resolution.models().forEach(target -> pending.add(target.modelId()));
        for (int index = 0; index < pending.size(); index++) {
            Candidate value = scope.lookup(namespace, pending.get(index), true);
            if (value == null || result.containsKey(value.modelId())) {
                continue;
            }
            result.put(value.modelId(), value.available() ? value.value() : null);
            if (value.value() != null) {
                ModelMetadata.validate(value.value().getClass()).parentReferences().forEach(parent -> {
                    Object parentId = parent.read(value.value());
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
            ModelCommitContext context) {
        ModelBatchScope scope = current();
        if (scope == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        context.entries().forEach(entry -> {
            Candidate value = scope.lookup(namespace, entry.target().modelId(), true);
            if (value != null) {
                result.put(value.modelId(), value.available() ? value.value() : null);
            }
        });
        return immutable(result);
    }

    private void stageModel(
            String namespace,
            String modelId,
            Class<?> modelType,
            Object before,
            Object after,
            int position,
            int segment,
            Operation producer) {
        ConcurrentLinkedDeque<Candidate> exact = candidates(namespace, modelId);
        Candidate value = new Candidate(
                modelId, modelType, after, true, true,
                existedBefore(exact, before, position, segment),
                position, segment, producer);
        exact.addFirst(value);
        Set<String> beforeAliases = aliases(before, modelType);
        Set<String> afterAliases = aliases(after, modelType);
        beforeAliases.stream().filter(alias -> !afterAliases.contains(alias)).forEach(alias ->
                candidates(namespace, alias).addFirst(value.asAlias(null, false)));
        afterAliases.forEach(alias ->
                candidates(namespace, alias).addFirst(value.asAlias(after, true)));
    }

    private ConcurrentLinkedDeque<Candidate> candidates(
            String namespace,
            String modelId) {
        return values.computeIfAbsent(
                new ModelKey(namespace, modelId),
                ignored -> new ConcurrentLinkedDeque<>());
    }

    private Candidate lookup(String namespace, String id, boolean exactOnly) {
        int position = DeserializingMessage.getMessageBatchIndex();
        if (position < 0) {
            return null;
        }
        ConcurrentLinkedDeque<Candidate> candidates = values.get(
                new ModelKey(normalize(namespace), id));
        Candidate candidate = visible(candidates, position, true);
        if (candidate == null && !exactOnly) {
            candidate = visible(candidates, position, false);
        }
        if (candidate == null || status(candidate) == Status.SUCCESS) {
            return null;
        }
        dependOn(candidate.producer());
        return candidate;
    }

    private static Candidate visible(
            Collection<Candidate> candidates,
            int position,
            boolean exactOnly) {
        if (candidates == null) {
            return null;
        }
        Operation consumer = currentDependency.get();
        int segment = currentSegment();
        Candidate result = null;
        for (Candidate candidate : candidates) {
            if (candidate.position() > position
                || exactOnly && !candidate.exact()
                || candidate.segment() != segment
                || status(candidate) == Status.FAILURE
                || consumer != null && candidate.producer() == consumer) {
                continue;
            }
            if (result == null || candidate.position() > result.position()) {
                result = candidate;
            }
        }
        return result;
    }

    private static void dependOn(Operation producer) {
        if (producer == null) {
            return;
        }
        Operation consumer = currentDependency.get();
        if (consumer != null) {
            if (consumer != producer) {
                consumer.dependsOn(producer);
            }
        } else if (DeserializingMessage.getCurrent() != null) {
            Invocation.awaitBeforeResultPublication(
                    DeserializingMessage.getCurrent(), producer);
        }
    }

    private static Status status(Candidate candidate) {
        if (candidate.producer() == null || !candidate.producer().isDone()) {
            return Status.PENDING;
        }
        CompletableFuture<?> completion = candidate.producer();
        return completion.isCompletedExceptionally() || completion.isCancelled()
                ? Status.FAILURE : Status.SUCCESS;
    }

    private static boolean existedBefore(
            Collection<Candidate> candidates,
            Object before,
            int position,
            int segment) {
        Candidate previous = visible(candidates, position, true);
        return previous != null && previous.segment() == segment
               && status(previous) == Status.PENDING
                ? previous.existedBefore() : before != null;
    }

    private static Set<String> aliases(Object value, Class<?> type) {
        if (value == null || Object.class.equals(type)) {
            return Set.of();
        }
        List<String> aliases = ModelMetadata.validate(type).aliases(value);
        return aliases == null || aliases.isEmpty() ? Set.of() : Set.copyOf(aliases);
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

    /** A pending exact model value; {@code value == null} represents deletion. */
    public record StagedModel(
            String modelId,
            Class<?> modelType,
            Object value,
            boolean existedBefore) {
    }

    record BatchLifecycle(
            Supplier<ModelCommitBatchingClient.ModelCommitBatch> readyBatch,
            Function<Integer, ModelCommitBatchingClient.ModelCommitBatch> batch,
            BooleanSupplier awaitBeforeResultPublication) {
    }

    private static final class Batch {
        private final BatchLifecycle lifecycle;
        private final List<Operation> operations = new ArrayList<>();
        private ModelCommitBatchingClient.ModelCommitBatch readyTransport;
        private boolean transportSettled;
        private boolean closed;

        private Batch(BatchLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        private synchronized Operation register(
                DeserializingMessage message,
                ModelCommitPolicy policy) {
            if (closed) {
                return new Operation(null, policy, true);
            }
            Operation operation = new Operation(this, policy, !policy.commitAfterBatch());
            if (!policy.commitAfterBatch()) {
                if (readyTransport == null) {
                    readyTransport = lifecycle.readyBatch().get();
                }
                operation.transport(readyTransport, operations.size());
                if (lifecycle.awaitBeforeResultPublication().getAsBoolean()) {
                    Invocation.awaitBeforeResultPublication(message, operation);
                }
            }
            operations.add(operation);
            return operation;
        }

        private void close(Throwable failure) {
            List<Operation> snapshot;
            synchronized (this) {
                closed = true;
                snapshot = List.copyOf(operations);
            }
            if (failure != null) {
                settleTransport(failure);
                snapshot.forEach(operation -> operation.fail(failure));
                return;
            }
            settleTransport(null);
            if (snapshot.isEmpty()) {
                return;
            }
            List<Operation> deferred = snapshot.stream()
                    .filter(operation -> operation.policy().commitAfterBatch()).toList();
            if (!deferred.isEmpty()) {
                CompletableFuture.allOf(deferred.stream()
                                .map(Operation::initialization)
                                .toArray(CompletableFuture[]::new))
                        .whenComplete((ignored, initializationFailure) -> {
                            if (initializationFailure == null) {
                                release(snapshot, deferred);
                            } else {
                                deferred.forEach(operation -> operation.fail(initializationFailure));
                            }
                        });
            }
            AsyncCompletionScope.register(CompletableFuture.allOf(
                    snapshot.toArray(CompletableFuture[]::new)));
        }

        private void release(
                List<Operation> all,
                List<Operation> deferred) {
            boolean sequential = all.stream().anyMatch(operation -> !operation.policy().async());
            Map<String, Operation> tails = new HashMap<>();
            Operation previous = null;
            for (Operation operation : all) {
                if (sequential && previous != null) {
                    operation.dependsOn(previous);
                }
                previous = operation;
                if (operation.modelIds() != null) {
                    operation.modelIds().forEach(modelId -> {
                        Operation predecessor = tails.put(modelId, operation);
                        if (predecessor != null) {
                            operation.dependsOn(predecessor);
                        }
                    });
                }
            }
            ModelCommitBatchingClient.ModelCommitBatch transport = deferred.stream()
                    .allMatch(operation -> operation.policy().async())
                    ? lifecycle.batch().apply(deferred.size()) : null;
            for (int index = 0; index < deferred.size(); index++) {
                deferred.get(index).transport(transport, index);
            }
            deferred.forEach(Operation::release);
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

    static class Operation extends CompletableFuture<Object> {
        private final Set<Operation> dependencies = ConcurrentHashMap.newKeySet();
        private final CompletableFuture<Void> initialized = new CompletableFuture<>();
        private final CompletableFuture<Void> release = new CompletableFuture<>();
        private final AtomicBoolean arrived = new AtomicBoolean();
        private final Batch batch;
        private final ModelCommitPolicy policy;
        private volatile Set<String> modelIds;
        private volatile ModelCommitBatchingClient.ModelCommitBatch transport;
        private volatile int slot = -1;

        Operation() {
            this(null, null, true);
        }

        private Operation(Batch batch, ModelCommitPolicy policy, boolean released) {
            this.batch = batch;
            this.policy = policy;
            if (batch == null) {
                initialized.complete(null);
                release.complete(null);
            } else if (released) {
                release.complete(null);
            }
        }

        void dependsOn(Operation producer) {
            if (producer != this) {
                dependencies.add(producer);
            }
        }

        CompletableFuture<Void> initialization() {
            return initialized;
        }

        void initialize(Collection<String> resolvedModelIds) {
            modelIds = batch == null ? null : Set.copyOf(resolvedModelIds);
            initialized.complete(null);
        }

        <T> CompletableFuture<T> executeAfterRelease(
                Function<Boolean, CompletableFuture<T>> action) {
            if (!arrived.compareAndSet(false, true)) {
                throw new IllegalStateException("Model commit operation was awaited twice");
            }
            return release.thenCompose(ignored -> {
                boolean dependent = !dependencies.isEmpty();
                if (dependent) {
                    detachTransport();
                }
                CompletableFuture<Void> predecessors = dependent
                                ? CompletableFuture.allOf(dependencies.stream()
                                .toArray(CompletableFuture[]::new))
                        : CompletableFuture.completedFuture(null);
                return predecessors.thenCompose(unused ->
                        Objects.requireNonNull(action.apply(dependent),
                                               "Model commit operation returned null"));
            }).whenComplete((ignored, failure) -> settleTransport());
        }

        void bind(CompletableFuture<?> result) {
            result.whenComplete((value, failure) -> {
                if (failure == null) {
                    complete(value);
                } else {
                    fail(failure);
                }
            });
        }

        void fail(Throwable failure) {
            completeExceptionally(failure);
            initialized.completeExceptionally(failure);
            release.completeExceptionally(failure);
            settleTransport();
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
            if (batch != null) {
                batch.settleTransport(null);
            }
        }

        private void settleTransport() {
            if (transport != null) {
                transport.skip(slot);
            }
        }

        boolean batched() {
            return batch != null;
        }

        ModelCommitPolicy policy() {
            return policy;
        }

        Set<String> modelIds() {
            return modelIds;
        }

        boolean hasBatchDependencies() {
            return !dependencies.isEmpty();
        }

        int dependencyCount() {
            return dependencies.size();
        }

        CompletableFuture<Void> dependencyCompletion() {
            return dependencies.isEmpty() ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(dependencies.stream()
                            .toArray(CompletableFuture[]::new));
        }

        ModelCommitBatchingClient.ModelCommitBatch transportBatch() {
            return transport;
        }

        int transportSlot() {
            return slot;
        }
    }

    private enum Status { PENDING, SUCCESS, FAILURE }

    private record ModelKey(String namespace, String modelId) {
        private ModelKey {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(modelId, "modelId");
        }
    }

    private record Candidate(
            String modelId,
            Class<?> modelType,
            Object value,
            boolean available,
            boolean exact,
            boolean existedBefore,
            int position,
            int segment,
            Operation producer) {
        private Candidate asAlias(Object aliasValue, boolean aliasAvailable) {
            return new Candidate(
                    modelId, modelType, aliasValue, aliasAvailable, false,
                    existedBefore, position, segment, producer);
        }

        private StagedModel staged() {
            return new StagedModel(modelId, modelType, value, existedBefore);
        }
    }

}
