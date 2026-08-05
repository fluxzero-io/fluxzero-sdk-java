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

import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.tracking.handling.Invocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Infrastructure-owned read-your-writes view for one tracked message batch.
 *
 * <p>Pending model evaluations are visible to later messages in the same batch, including asynchronous workers that
 * captured the message context. Successful or failed evaluations stop shadowing the durable repository immediately;
 * a subsequent load then observes the authoritative committed state or failure recovery. Values never cross the
 * message-batch resource boundary.</p>
 */
public final class MessageBatchModelView {
    private static final Object RESOURCE_KEY = MessageBatchModelView.class;
    private static final String APPLICATION_NAMESPACE = "\u0000";
    private static final ThreadLocal<Dependency> currentDependency =
            ThreadLocalContext.create();

    private final ConcurrentHashMap<ModelKey, Candidate> aliasValues =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ModelKey, Candidate> exactValues =
            new ConcurrentHashMap<>();
    private final Object indexLock = new Object();
    private volatile Slots slots;
    private volatile int indexedThrough = -1;

    private MessageBatchModelView() {
        int batchSize = DeserializingMessage.getMessageBatchSize();
        slots = new Slots(batchSize < 0 ? 256 : Math.max(1, batchSize));
    }

    static Stage stage(
            String namespace,
            ModelCommitEngine.CommitEvaluation evaluation,
            Dependency producer) {
        int messageIndex = DeserializingMessage.getMessageBatchIndex();
        if (messageIndex < 0 || evaluation.finalValues().isEmpty()) {
            return null;
        }
        MessageBatchModelView view =
                DeserializingMessage.getMessageBatchResource(
                        RESOURCE_KEY);
        if (view == null) {
            view = DeserializingMessage.computeForMessageBatchIfAbsent(
                    RESOURCE_KEY, ignored -> new MessageBatchModelView());
        }
        if (view == null) {
            return null;
        }
        if (producer != null) {
            DependencyContext dependency =
                    new DependencyContext(producer);
            evaluation.substeps().forEach(substep ->
                    substep.message().putContext(
                            DependencyContext.class,
                            dependency));
        }
        int segment = currentSegment();
        Stage stage = producer == null ? new Stage() : null;
        view.store(
                messageIndex, normalize(namespace), segment,
                evaluation, producer, stage);
        return stage;
    }

    static <T> T withDependency(
            Dependency dependency,
            Supplier<T> action) {
        Dependency previous = currentDependency.get();
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
        Dependency dependency = message
                .getContext(DependencyContext.class)
                .map(DependencyContext::dependency)
                .orElse(null);
        return withDependency(dependency, action);
    }

    /**
     * Creates a dependency owner for an explicit model operation in the current message batch.
     * Outside message-batch handling this returns {@code null}, keeping ordinary explicit operations allocation-free.
     */
    static Operation newOperation() {
        return DeserializingMessage.getMessageBatchIndex() < 0
                ? null : new Operation();
    }

    /**
     * Overlays a direct durable model load with the newest pending value visible to the current message.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> Entity<T> overlayCurrent(
            String namespace,
            String requestedId,
            Class<T> requestedType,
            Entity<T> durable) {
        MessageBatchModelView view = current();
        if (view == null) {
            return durable;
        }
        Lookup lookup = view.lookup(namespace, requestedId);
        if (lookup == null) {
            return durable;
        }
        if (!lookup.exact()
            && durable.isPresent()
            && requestedId.equals(String.valueOf(durable.id()))) {
            return durable;
        }
        Class<?> actualType = lookup.value() != null
                ? lookup.value().getClass()
                : durable.isPresent()
                        ? durable.type()
                        : lookup.modelType();
        if (!Object.class.equals(requestedType)
            && !requestedType.isAssignableFrom(actualType)) {
            return durable;
        }
        String resolvedId = lookup.available()
                ? lookup.modelId() : requestedId;
        if (durable instanceof ImmutableEntity<?> immutable) {
            return (Entity<T>) immutable.toBuilder()
                    .id(resolvedId)
                    .type((Class) actualType)
                    .idProperty(ModelMetadata.of(actualType).entityIdName())
                    .value(lookup.available() ? lookup.value() : null)
                    .build();
        }
        return (Entity<T>) ImmutableModelRoot.builder()
                .id(resolvedId)
                .type((Class) actualType)
                .idProperty(ModelMetadata.of(actualType).entityIdName())
                .value(lookup.available() ? lookup.value() : null)
                .build();
    }

    /**
     * Overlays pending direct values on a handler injection context without changing its durable read boundary.
     */
    public static ModelCommitContext overlayCurrent(
            String namespace,
            ModelCommitContext durable) {
        MessageBatchModelView view = current();
        if (view == null) {
            return durable;
        }
        LinkedHashMap<String, Object> overlays = new LinkedHashMap<>();
        for (ModelCommitContext.Entry entry : durable.entries()) {
            Lookup lookup = view.lookup(
                    namespace, entry.target().modelId());
            if (lookup != null) {
                overlays.put(
                        entry.target().modelId(),
                        lookup.available() ? lookup.value() : null);
            }
        }
        return overlays.isEmpty()
                ? durable : durable.withValues(overlays);
    }

    /**
     * Returns every exact pending model value visible to the current message. Alias entries are omitted.
     */
    public static Map<String, StagedModel> currentValues(
            String namespace) {
        MessageBatchModelView view = current();
        if (view == null) {
            return Map.of();
        }
        int messageIndex = DeserializingMessage.getMessageBatchIndex();
        if (messageIndex < 0) {
            return Map.of();
        }
        view.ensureIndexed(messageIndex);
        String normalizedNamespace = normalize(namespace);
        List<Map.Entry<ModelKey, Candidate>> visibleValues =
                new ArrayList<>();
        view.exactValues.forEach((key, candidate) -> {
            if (!key.namespace().equals(normalizedNamespace)) {
                return;
            }
            Candidate visible = visible(candidate, messageIndex);
            if (visible != null && status(visible) == Status.PENDING) {
                visibleValues.add(Map.entry(key, visible));
            }
        });
        if (visibleValues.isEmpty()) {
            return Map.of();
        }
        visibleValues.sort(
                java.util.Comparator
                        .comparingInt((Map.Entry<ModelKey, Candidate> entry) ->
                                entry.getValue().messageIndex())
                        .thenComparing(entry ->
                                entry.getKey().modelId()));
        LinkedHashMap<String, StagedModel> result =
                new LinkedHashMap<>(visibleValues.size());
        visibleValues.forEach(entry -> {
            Candidate visible = entry.getValue();
            dependOn(visible);
            result.put(
                    entry.getKey().modelId(),
                    new StagedModel(
                            visible.modelId(), visible.modelType(),
                            visible.value(), visible.existedBefore()));
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the pending exact value for one model ID that is visible to the current message. Aliases are deliberately
     * not resolved because infrastructure callers use this method to overlay an already identified relationship node.
     */
    public static StagedModel currentValue(
            String namespace,
            String modelId) {
        MessageBatchModelView view = current();
        if (view == null) {
            return null;
        }
        int messageIndex = DeserializingMessage.getMessageBatchIndex();
        if (messageIndex < 0) {
            return null;
        }
        view.ensureIndexed(messageIndex);
        Candidate visible = visible(
                view.exactValues.get(new ModelKey(
                        normalize(namespace), modelId)),
                messageIndex);
        if (visible == null
            || status(visible) != Status.PENDING) {
            return null;
        }
        dependOn(visible);
        return new StagedModel(
                visible.modelId(), visible.modelType(),
                visible.value(), visible.existedBefore());
    }

    private static MessageBatchModelView current() {
        return DeserializingMessage.getMessageBatchResource(
                RESOURCE_KEY);
    }

    private void store(
            int messageIndex,
            String namespace,
            int segment,
            ModelCommitEngine.CommitEvaluation evaluation,
            Dependency producer,
            Stage stage) {
        synchronized (indexLock) {
            Slots current = slotsFor(messageIndex);
            ModelCommitEngine.CommitEvaluation existing =
                    current.evaluations()[messageIndex];
            if (existing == null) {
                current.namespaces()[messageIndex] = namespace;
                current.segments()[messageIndex] = segment;
                current.producers()[messageIndex] = producer;
                current.stages()[messageIndex] = stage;
                current.evaluations()[messageIndex] = evaluation;
                if (messageIndex <= indexedThrough) {
                    indexEvaluation(
                            messageIndex, namespace, segment,
                            evaluation,
                            producer, stage);
                }
                return;
            }
            /*
             * One handler may invoke multiple explicit model operations. Preserve every operation without allocating
             * a per-message collection on the ordinary one-evaluation path: index the first slot on the first repeat,
             * then append this and any following evaluations directly to the lazy index.
             */
            if (messageIndex > indexedThrough) {
                ensureIndexed(messageIndex);
            }
            indexEvaluation(
                    messageIndex, namespace, segment,
                    evaluation, producer, stage);
        }
    }

    private Slots slotsFor(int messageIndex) {
        Slots current = slots;
        if (messageIndex < current.evaluations().length) {
            return current;
        }
        int capacity = current.evaluations().length;
        while (capacity <= messageIndex) {
            capacity = Math.max(capacity + 1, capacity << 1);
        }
        Slots expanded = new Slots(capacity);
        System.arraycopy(
                current.evaluations(), 0,
                expanded.evaluations(), 0,
                current.evaluations().length);
        System.arraycopy(
                current.namespaces(), 0,
                expanded.namespaces(), 0,
                current.namespaces().length);
        System.arraycopy(
                current.segments(), 0,
                expanded.segments(), 0,
                current.segments().length);
        System.arraycopy(
                current.producers(), 0,
                expanded.producers(), 0,
                current.producers().length);
        System.arraycopy(
                current.stages(), 0,
                expanded.stages(), 0,
                current.stages().length);
        slots = expanded;
        return expanded;
    }

    private void ensureIndexed(int messageIndex) {
        if (indexedThrough >= messageIndex) {
            return;
        }
        synchronized (indexLock) {
            int until = Math.min(
                    messageIndex,
                    slots.evaluations().length - 1);
            for (int index = indexedThrough + 1;
                 index <= until; index++) {
                ModelCommitEngine.CommitEvaluation evaluation =
                        slots.evaluations()[index];
                if (evaluation != null) {
                    indexEvaluation(
                            index,
                            slots.namespaces()[index],
                            slots.segments()[index],
                            evaluation,
                            slots.producers()[index],
                            slots.stages()[index]);
                }
            }
            indexedThrough = Math.max(indexedThrough, until);
        }
    }

    private void indexEvaluation(
            int messageIndex,
            String namespace,
            int segment,
            ModelCommitEngine.CommitEvaluation evaluation,
            Dependency producer,
            Stage stage) {
        Map<String, Object> beforeValues = new LinkedHashMap<>();
        evaluation.transitions().forEach(transition ->
                beforeValues.putIfAbsent(
                        transition.modelId(), transition.before()));
        evaluation.finalValues().forEach((modelId, value) -> {
            Object before = beforeValues.get(modelId);
            Class<?> modelType = value != null
                    ? value.getClass()
                    : before != null
                            ? before.getClass()
                            : evaluation.readModelTypes().get(modelId);
            if (modelType == null) {
                modelType = Object.class;
            }
            stageModel(
                    namespace, modelId, modelType,
                    before, value,
                    messageIndex, segment,
                    stage, producer);
        });
    }

    private void stageModel(
            String namespace,
            String modelId,
            Class<?> modelType,
            Object before,
            Object after,
            int messageIndex,
            int segment,
            Stage stage,
            Dependency producer) {
        ModelKey exactKey = new ModelKey(
                namespace, modelId);
        Candidate exact = exactValues.compute(
                exactKey,
                (ignored, previous) -> new Candidate(
                        modelId, modelType, after, true,
                        existedBefore(
                                before, previous,
                                messageIndex, segment),
                        messageIndex, segment, stage, producer,
                        previous));

        Set<String> beforeAliases = aliases(before, modelType);
        Set<String> afterAliases = aliases(after, modelType);
        for (String removed : difference(beforeAliases, afterAliases)) {
            ModelKey aliasKey = new ModelKey(
                    namespace, removed);
            Candidate invalid = new Candidate(
                    modelId, modelType, null, false, exact.existedBefore(),
                    messageIndex, segment, stage, producer, null);
            aliasValues.compute(aliasKey, (ignored, previous) ->
                    invalid.withPrevious(previous));
        }
        for (String alias : afterAliases) {
            ModelKey aliasKey = new ModelKey(
                    namespace, alias);
            Candidate mapped = new Candidate(
                    modelId, modelType, after, true, exact.existedBefore(),
                    messageIndex, segment, stage, producer, null);
            aliasValues.compute(aliasKey, (ignored, previous) ->
                    mapped.withPrevious(previous));
        }
    }

    private Lookup lookup(
            String namespace,
            String requestedId) {
        int messageIndex = DeserializingMessage.getMessageBatchIndex();
        if (messageIndex < 0) {
            return null;
        }
        ensureIndexed(messageIndex);
        ModelKey key = new ModelKey(
                normalize(namespace), requestedId);
        Candidate candidate = visible(
                exactValues.get(key), messageIndex);
        boolean exact = candidate != null;
        if (candidate == null) {
            candidate = visible(
                    aliasValues.get(key), messageIndex);
        }
        if (candidate == null
            || status(candidate) == Status.SUCCESS) {
            return null;
        }
        dependOn(candidate);
        return new Lookup(
                candidate.modelId(), candidate.modelType(),
                candidate.value(), candidate.available(), exact);
    }

    private static Candidate visible(
            Candidate first,
            int messageIndex) {
        Dependency consumer = currentDependency.get();
        int segment = currentSegment();
        Candidate best = null;
        for (Candidate candidate = first;
             candidate != null;
             candidate = candidate.previous()) {
            if (candidate.messageIndex() > messageIndex
                || candidate.segment() != segment
                || status(candidate) == Status.FAILURE
                || consumer != null
                   && candidate.producer() == consumer) {
                continue;
            }
            if (best == null
                || candidate.messageIndex() > best.messageIndex()) {
                best = candidate;
            }
        }
        return best;
    }

    private static void dependOn(Candidate candidate) {
        Dependency producer = candidate.producer();
        if (producer == null) {
            return;
        }
        Dependency consumer = currentDependency.get();
        if (consumer != null) {
            if (consumer != producer) {
                consumer.dependsOn(producer);
            }
            return;
        }
        DeserializingMessage message = DeserializingMessage.getCurrent();
        if (message != null) {
            Invocation.awaitBeforeResultPublication(
                    message, producer.completion());
        }
    }

    private static Status status(Candidate candidate) {
        Dependency producer = candidate.producer();
        if (producer != null) {
            CompletableFuture<?> completion = producer.completion();
            if (!completion.isDone()) {
                return Status.PENDING;
            }
            return completion.isCompletedExceptionally()
                   || completion.isCancelled()
                    ? Status.FAILURE : Status.SUCCESS;
        }
        Stage stage = candidate.stage();
        return stage == null ? Status.PENDING : stage.status;
    }

    private static Set<String> aliases(Object value, Class<?> modelType) {
        if (Object.class.equals(modelType)) {
            return Set.of();
        }
        List<String> aliases =
                ModelMetadata.validate(modelType).aliases(value);
        return aliases == null || aliases.isEmpty()
                ? Set.of() : Set.copyOf(aliases);
    }

    private static Set<String> difference(
            Set<String> left,
            Set<String> right) {
        if (left.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static boolean existedBefore(
            Object before,
            Candidate previous,
            int messageIndex,
            int segment) {
        for (Candidate candidate = previous;
             candidate != null;
             candidate = candidate.previous()) {
            if (candidate.messageIndex() <= messageIndex
                && candidate.segment() == segment
                && status(candidate) == Status.PENDING) {
                return candidate.existedBefore();
            }
        }
        return before != null;
    }

    private static String normalize(String namespace) {
        return namespace == null ? APPLICATION_NAMESPACE : namespace;
    }

    private static int currentSegment() {
        if (DeserializingMessage.getMessageBatchIndex() >= 0) {
            return DeserializingMessage.getMessageBatchSegment();
        }
        DeserializingMessage message =
                DeserializingMessage.getCurrent();
        if (message == null) {
            return -1;
        }
        Integer segment = message.getSerializedObject()
                .getSegment();
        return segment == null ? -1 : segment;
    }

    /** A pending exact model value; {@code value == null} represents deletion. */
    public record StagedModel(
            String modelId,
            Class<?> modelType,
            Object value,
            boolean existedBefore) {
    }

    static final class Stage {
        private volatile Status status = Status.PENDING;

        void complete(Throwable failure) {
            status = failure == null
                    ? Status.SUCCESS : Status.FAILURE;
        }
    }

    interface Dependency {
        void dependsOn(Dependency producer);

        CompletableFuture<?> completion();
    }

    static final class Operation implements Dependency {
        private final Set<Dependency> dependencies =
                ConcurrentHashMap.newKeySet();
        private final CompletableFuture<Object> completion =
                new CompletableFuture<>();

        @Override
        public void dependsOn(Dependency producer) {
            if (producer != this) {
                dependencies.add(producer);
            }
        }

        @Override
        public CompletableFuture<?> completion() {
            return completion;
        }

        boolean hasDependencies() {
            return !dependencies.isEmpty();
        }

        CompletableFuture<Void> dependencyCompletion() {
            return dependencies.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(
                            dependencies.stream()
                                    .map(Dependency::completion)
                                    .toArray(CompletableFuture[]::new));
        }

        int dependencyCount() {
            return dependencies.size();
        }

        void bind(CompletableFuture<?> result) {
            result.whenComplete((value, failure) -> {
                if (failure == null) {
                    completion.complete(value);
                } else {
                    completion.completeExceptionally(failure);
                }
            });
        }

        void fail(Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }

    private enum Status {
        PENDING,
        SUCCESS,
        FAILURE
    }

    private record ModelKey(
            String namespace,
            String modelId) {
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
            boolean existedBefore,
            int messageIndex,
            int segment,
            Stage stage,
            Dependency producer,
            Candidate previous) {
        private Candidate withPrevious(Candidate previous) {
            return new Candidate(
                    modelId, modelType, value, available, existedBefore,
                    messageIndex, segment, stage, producer, previous);
        }
    }

    private record Slots(
            ModelCommitEngine.CommitEvaluation[] evaluations,
            String[] namespaces,
            int[] segments,
            Dependency[] producers,
            Stage[] stages) {
        private Slots(int capacity) {
            this(new ModelCommitEngine.CommitEvaluation[capacity],
                 new String[capacity],
                 new int[capacity],
                 new Dependency[capacity],
                 new Stage[capacity]);
        }
    }

    private record Lookup(
            String modelId,
            Class<?> modelType,
            Object value,
            boolean available,
            boolean exact) {
    }

    private record DependencyContext(
            Dependency dependency) {
    }
}
