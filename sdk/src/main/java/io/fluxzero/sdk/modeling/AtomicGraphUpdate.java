/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** In-process operation carrier; only the resulting DirectModelUpdate is persisted, never this callback. */
final class AtomicGraphUpdate<T> {
    final String modelId;
    final Class<T> modelType;
    final DefaultModelRepository repository;
    final Long expectedRevision;
    final UnaryOperator<Graph<T>> function;
    Entity<T> previous;
    Graph<T> before;
    Graph<T> after;
    ModelCommitConflictException rejected;
    ModelCommitConflictException preparationConflict;

    private AtomicGraphUpdate(GraphView<T> source, Long expectedRevision, UnaryOperator<Graph<T>> function) {
        modelId = source.id().toString();
        modelType = source.type();
        repository = (DefaultModelRepository) source.state().repository();
        this.expectedRevision = expectedRevision;
        this.function = Objects.requireNonNull(function, "update");
    }

    static <T> boolean compareAndSet(Graph<T> source, T replacement) {
        GraphView<T> view = validate(source);
        if (replacement != null && !view.type().isInstance(replacement)) {
            throw new IllegalArgumentException("Replacement must have the selected Model type");
        }
        if (source.isEmpty() || source.revisionStateIndex() < 0) { return false; }
        AtomicGraphUpdate<T> operation = new AtomicGraphUpdate<>(
                view, source.revisionStateIndex(), graph -> graph.update(ignored -> replacement));
        try {
            Fluxzero.assertAndApply(operation);
            return true;
        } catch (ComparisonFailed conflict) {
            return false;
        } catch (ModelCommitConflictException conflict) {
            if (conflict == operation.rejected || conflict == operation.preparationConflict) { return false; }
            throw conflict;
        }
    }

    static <T> Graph<T> update(Graph<T> source, UnaryOperator<Graph<T>> function, int maxRetries,
                                boolean returnBefore) {
        Objects.requireNonNull(function, "update");
        if (maxRetries < 0) { throw new IllegalArgumentException("maxRetries must not be negative"); }
        GraphView<T> view = validate(source);
        for (int retries = 0; ; retries++) {
            AtomicGraphUpdate<T> operation = new AtomicGraphUpdate<>(view, null, function);
            try {
                Fluxzero.assertAndApply(operation);
                return returnBefore ? operation.before : operation.after;
            } catch (ModelCommitConflictException conflict) {
                // Only a definitively rejected write can be repeated. Never repeat an indeterminate transport
                // outcome or a mutation that already lost an authoritative pinned document.
                if (conflict != operation.rejected || retries >= maxRetries) { throw conflict; }
            }
        }
    }

    private static <T> GraphView<T> validate(Graph<T> source) {
        if (!(source instanceof GraphView<T> view)
            || !(view.state().repository() instanceof DefaultModelRepository repository)
            || !EntityMetadata.of(view.type()).isModel()) {
            throw new UnsupportedOperationException("Atomic Graph operations require an independent SDK Model");
        }
        var current = Fluxzero.get().modelRepository();
        var message = DeserializingMessage.getCurrent();
        if (message != null) { current = current.forNamespace(ClientUtils.getConsumerNamespace(message)); }
        if (!repository.sharesReadContext(current)) {
            throw new IllegalArgumentException("The Graph belongs to a different application or namespace");
        }
        if (Entity.isLoading() || CommitAttempt.currentReadContext(repository) != null) {
            throw new IllegalStateException("Atomic Graph operations cannot run inside a Model mutation or replay");
        }
        if (!view.state().stagedChanges().isEmpty()) {
            throw new IllegalArgumentException("Atomic Graph operations require an unstaged receiver");
        }
        return view;
    }

    @SuppressWarnings("unchecked")
    CommitAttempt evaluate(CommitAttempt context, ModelReducer.SubstepResolver resolver,
                           DeserializingMessage message) {
        context.durableReadsOnly();
        context.readResolver(resolver);
        context.bindGraphReads(context);
        previous = (Entity<T>) context.entity(modelId);
        if (previous == null || previous.isEmpty()) {
            if (expectedRevision != null) { throw new ComparisonFailed(); }
            throw new NoSuchElementException("Cannot atomically update absent Model " + modelId);
        }
        Graph<T> input = Graphs.lazy(previous, context, repository);
        before = Graphs.pinnedRevision(previous, context.readStateIndex(), repository);
        if (expectedRevision != null && input.revisionStateIndex() != expectedRevision) {
            throw new ComparisonFailed();
        }
        Change change = CommitAttempt.withGraphReads(context, () -> {
            Graph<T> output = Objects.requireNonNull(function.apply(input),
                                                    "Return Graph.delete(), not a null Graph");
            if (!(output instanceof GraphView<T> view) || !modelId.equals(output.id().toString())
                || modelType != output.type() || view.state().repository() != repository
                || view.context().readContext() == null || view.context().readContext().owner() != context) {
                throw new IllegalArgumentException("Atomic update must return its same-target transactional Graph");
            }
            List<GraphMutation> changes = Graphs.stagedChanges(output);
            if (changes.size() > 1 || !changes.isEmpty() && !modelId.equals(changes.getFirst().modelId())
                || changes.isEmpty() && output != input) {
                throw new IllegalArgumentException("Use update() or delete() on the supplied Graph only");
            }
            T value = output.get();
            if (!EntityMetadata.of(modelType).identifies(modelId, value)) {
                throw new IllegalArgumentException("Atomic replacement cannot change Model identity");
            }
            GraphMutation mutation = new GraphMutation(modelId, modelType, input.revisionStateIndex(),
                    context.readStateIndex(), false, value, entity -> ((Entity<T>) entity).update(ignored -> value));
            return Change.resolve(mutation, previous, value).checkedReplacement();
        });
        context.evaluated(context.readStateIndex(), List.of(modelId), Map.of(modelId, modelType),
                          List.of(new CommitAttempt.Step(message.withPayload(input), List.of(change))));
        context.finishReadBoundary();
        return context;
    }

    private static final class ComparisonFailed extends RuntimeException {
        private ComparisonFailed() { super("Model revision changed", null, false, false); }
    }
}
