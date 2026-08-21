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

package io.fluxzero.common.modeling;

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelCommitTargetResult;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Owns storage-neutral Model commit description, head assignment and accepted-result construction. */
public final class ModelCommitAssignment {
    /** Describes target scope and optional work before positions are assigned. */
    public static Description describe(CommitModels source) {
        if (source.getSubsteps().size() == 1
            && source.getSubsteps().getFirst().getTargets().size() == 1) {
            var target = source.getSubsteps().getFirst().getTargets().getFirst();
            List<String> ids = List.of(target.getModelId());
            return new Description(
                    ids, target.isStoreEvent() ? List.of() : ids,
                    target.getDocument() != null || target.getSnapshot() != null,
                    target.isDelete() || target.isUpdateRelationships(),
                    target.isDelete() ? Map.of(target.getModelId(), 0) : Map.of(),
                    target.isDelete() && target.isCascadeDelete() ? ids : List.of(),
                    Aliases.from(target));
        }
        var targets = new LinkedHashSet<String>();
        var unstored = new LinkedHashSet<String>();
        var deletions = new LinkedHashMap<String, Integer>();
        var cascadeRoots = new LinkedHashSet<String>();
        LinkedHashMap<String, List<String>> aliases = null;
        boolean materialization = false, relationships = false;
        for (int step = 0; step < source.getSubsteps().size(); step++) {
            for (var target : source.getSubsteps().get(step).getTargets()) {
                targets.add(target.getModelId());
                if (!target.isStoreEvent()) {
                    unstored.add(target.getModelId());
                }
                materialization |= target.getDocument() != null || target.getSnapshot() != null;
                relationships |= target.isDelete() || target.isUpdateRelationships();
                deletions.remove(target.getModelId());
                cascadeRoots.remove(target.getModelId());
                if (target.isDelete()) {
                    deletions.put(target.getModelId(), step);
                    if (target.isCascadeDelete()) {
                        cascadeRoots.add(target.getModelId());
                    }
                }
                if (target.isDelete() || target.getAliases() != null) {
                    if (aliases == null) {
                        aliases = new LinkedHashMap<>();
                    }
                    Aliases.put(aliases, target);
                }
            }
        }
        return new Description(
                List.copyOf(targets), List.copyOf(unstored), materialization, relationships,
                Map.copyOf(deletions), List.copyOf(cascadeRoots), Aliases.from(aliases));
    }

    /** Starts an ordered assignment session in which later commits observe earlier assigned heads. */
    public static <H extends Head> Session<H> session(
            Function<String, H> currentHeads, HeadFactory<H> headFactory, long firstStateIndex) {
        return new Session<>(currentHeads, headFactory, firstStateIndex);
    }

    /** Store head values needed to assign the next transition. */
    public interface Head {
        String modelType();
        long sequenceNumber();
        boolean historyComplete();
        String documentCollection();
        default Long firstIncompleteStateIndex() { return historyComplete() ? null : Long.MIN_VALUE; }
    }

    /** Constructs a compact store-specific head from the values assigned by this owner. */
    @FunctionalInterface
    public interface HeadFactory<H extends Head> {
        H create(
                String modelId, H previous, String modelType, long sequenceNumber,
                long stateIndex, Long firstIncompleteStateIndex, boolean deleted, String documentCollection);
    }

    /** Storage-neutral work known before assignment. */
    public record Description(
            List<String> targetIds, List<String> unstoredTargetIds, boolean mayMaterialize,
            boolean affectsRelationships, Map<String, Integer> finalDeletionSubsteps,
            List<String> cascadeRootIds, Aliases aliases) {}

    /** Final alias replacements and their requested owners in commit order. */
    public static final class Aliases {
        private static final Aliases EMPTY = new Aliases(Map.of());
        private final Map<String, List<String>> replacements;
        private final Map<String, String> owners;

        private Aliases(Map<String, List<String>> replacements) {
            this.replacements = replacements;
            LinkedHashMap<String, String> owners = new LinkedHashMap<>();
            replacements.forEach((modelId, aliases) -> aliases.forEach(alias -> {
                String existing = owners.putIfAbsent(alias, modelId);
                if (existing != null && !existing.equals(modelId)) {
                    throw new AliasCollisionException(alias, existing, modelId);
                }
            }));
            this.owners = Collections.unmodifiableMap(owners);
        }

        public Map<String, List<String>> replacements() { return replacements; }
        public Map<String, String> owners() { return owners; }
        public boolean isEmpty() { return replacements.isEmpty(); }

        /** Validates replacements against a store's current alias-to-model view. */
        public void validate(Map<String, String> current) {
            Map<String, String> aliases = new HashMap<>(current);
            aliases.entrySet().removeIf(entry -> replacements.containsKey(entry.getValue()));
            owners.forEach((alias, modelId) -> {
                String existing = aliases.putIfAbsent(alias, modelId);
                if (existing != null && !existing.equals(modelId)) {
                    throw new AliasCollisionException(alias, existing, modelId);
                }
            });
        }

        /** Applies a previously validated plan to an alias-to-model view. */
        public void applyTo(Map<String, String> current) {
            if (!replacements.isEmpty()) {
                current.entrySet().removeIf(entry -> replacements.containsKey(entry.getValue()));
                current.putAll(owners);
            }
        }

        private static Aliases from(ModelCommitTarget target) {
            if (!target.isDelete() && target.getAliases() == null) {
                return EMPTY;
            }
            LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
            put(result, target);
            return from(result);
        }

        private static Aliases from(Map<String, List<String>> replacements) {
            return replacements == null || replacements.isEmpty()
                    ? EMPTY
                    : new Aliases(Collections.unmodifiableMap(new LinkedHashMap<>(replacements)));
        }

        private static void put(
                Map<String, List<String>> replacements, ModelCommitTarget target) {
            replacements.put(
                    target.getModelId(), target.isDelete() ? List.of()
                            : target.getAliases().stream()
                                    .filter(alias -> !alias.equals(target.getModelId())).toList());
        }
    }

    /** Signals that one alias would identify two different models. */
    public static final class AliasCollisionException extends IllegalStateException {
        public AliasCollisionException(String alias, String existingModelId, String requestedModelId) {
            super("Model alias '%s' belongs to '%s' and cannot also identify '%s'"
                          .formatted(alias, existingModelId, requestedModelId));
        }
    }

    /** Receives assigned heads so a store can build only its own persistence representation. */
    @FunctionalInterface
    public interface HeadConsumer<H extends Head> {
        void accept(ModelCommitStep step, ModelCommitTarget target, int substep, H head);
    }

    /** One fully assigned commit. Event indices are read when its accepted result is requested. */
    public static final class Commit<H extends Head> {
        private final CommitModels source;
        private final H singleHead;
        private final List<List<ModelCommitTargetResult>> assignedTargets;
        private final long firstStateIndex;
        private final boolean materialization;
        private volatile CommitModelsResult result;
        private Commit(
                CommitModels source, H singleHead, List<List<ModelCommitTargetResult>> assignedTargets,
                long first, boolean materialization) {
            this.source = source;
            this.singleHead = singleHead;
            this.assignedTargets = assignedTargets;
            this.firstStateIndex = first;
            this.materialization = materialization;
        }
        public long firstStateIndex() { return firstStateIndex; }
        public boolean hasMaterialization() { return materialization; }
        public CommitModelsResult result() {
            CommitModelsResult current = result;
            if (current != null) {
                return current;
            }
            result = createResult();
            return result;
        }
        private CommitModelsResult createResult() {
            if (singleHead != null) {
                ModelCommitStep step = source.getSubsteps().getFirst();
                ModelCommitTarget target = step.getTargets().getFirst();
                return CommitModelsResult.acceptedSingleTarget(
                        source.getRequestId(), source.getCommitId(), firstStateIndex,
                        step.isPublishEvent() ? step.getEvent().getIndex() : null,
                        target.getModelId(), singleHead.sequenceNumber(), singleHead.historyComplete());
            }
            List<ModelUpdate> updates = new ArrayList<>(source.getSubsteps().size());
            for (int step = 0; step < source.getSubsteps().size(); step++) {
                ModelCommitStep sourceStep = source.getSubsteps().get(step);
                updates.add(new ModelUpdate(
                        ModelUpdateKind.COMMIT, source.getCommitId(), step,
                        firstStateIndex + step,
                        sourceStep.isPublishEvent() ? sourceStep.getEvent().getIndex() : null,
                        assignedTargets.get(step)));
            }
            return CommitModelsResult.accepted(
                    source.getRequestId(), source.getCommitId(), List.copyOf(updates));
        }
    }

    /** Mutable ordering state for one atomic assignment batch. */
    public static final class Session<H extends Head> {
        private final Function<String, H> currentHeads;
        private final HeadFactory<H> headFactory;
        private Map<String, H> assignedHeads;
        private LinkedHashMap<String, List<String>> aliasReplacements;
        private String lastModelId;
        private H lastHead;
        private long nextStateIndex;
        private boolean stateIndexExhausted;
        private Session(Function<String, H> currentHeads, HeadFactory<H> headFactory, long firstStateIndex) {
            this.currentHeads = currentHeads;
            this.headFactory = headFactory;
            this.nextStateIndex = firstStateIndex;
        }
        public Commit<H> assign(
                CommitModels source, Description description, HeadConsumer<H> consumer) {
            if (!description.aliases().isEmpty()) {
                if (aliasReplacements == null) {
                    aliasReplacements = new LinkedHashMap<>();
                }
                aliasReplacements.putAll(description.aliases().replacements());
            }
            int stepCount = source.getSubsteps().size();
            if (stateIndexExhausted
                || nextStateIndex > Long.MAX_VALUE - (stepCount - 1L)) {
                throw new IllegalStateException("Model state index space is exhausted");
            }
            long first = nextStateIndex;
            boolean single = stepCount == 1
                             && source.getSubsteps().getFirst().getTargets().size() == 1;
            H singleHead = null;
            List<List<ModelCommitTargetResult>> assignedTargets =
                    single ? null : new ArrayList<>(stepCount);
            boolean materialization = false;
            for (int step = 0; step < stepCount; step++) {
                ModelCommitStep sourceStep = source.getSubsteps().get(step);
                long stateIndex = nextStateIndex;
                if (stateIndex == Long.MAX_VALUE) {
                    stateIndexExhausted = true;
                } else {
                    nextStateIndex++;
                }
                List<ModelCommitTargetResult> targetResults =
                        single ? null : new ArrayList<>(sourceStep.getTargets().size());
                for (ModelCommitTarget target : sourceStep.getTargets()) {
                    H previous = previous(target.getModelId());
                    String type = modelType(
                            target.getModelId(), previous == null ? null : previous.modelType(),
                            target.getModelType());
                    long sequence = (previous == null ? -1L : previous.sequenceNumber())
                                    + (target.isStoreEvent() ? 1L : 0L);
                    Long incomplete = previous == null ? null : previous.firstIncompleteStateIndex();
                    if (incomplete == null && target.isUpdateState() && !target.isStoreEvent()) {
                        incomplete = stateIndex;
                    }
                    String collection = target.isDelete() ? null
                            : target.getDocument() == null
                                    ? previous == null ? null : previous.documentCollection()
                                    : target.getDocument().getDocument() == null ? null
                                            : target.getDocument().getCollection();
                    H head = headFactory.create(target.getModelId(), previous, type, sequence, stateIndex,
                                                incomplete, target.isDelete(), collection);
                    remember(target.getModelId(), head);
                    consumer.accept(sourceStep, target, step, head);
                    materialization |= target.getDocument() != null
                                       || target.getSnapshot() != null && head.historyComplete();
                    if (single) {
                        singleHead = head;
                    } else {
                        targetResults.add(new ModelCommitTargetResult(
                                target.getModelId(), head.sequenceNumber(), head.historyComplete()));
                    }
                }
                if (!single) {
                    assignedTargets.add(List.copyOf(targetResults));
                }
            }
            return new Commit<>(source, singleHead,
                                assignedTargets == null ? null : List.copyOf(assignedTargets),
                                first, materialization);
        }
        public Aliases aliases() { return Aliases.from(aliasReplacements); }
        private H previous(String modelId) {
            if (modelId.equals(lastModelId)) {
                return lastHead;
            }
            H assigned = assignedHeads == null ? null : assignedHeads.get(modelId);
            return assigned == null ? currentHeads.apply(modelId) : assigned;
        }
        private void remember(String modelId, H head) {
            if (lastModelId != null && !lastModelId.equals(modelId)) {
                if (assignedHeads == null) {
                    assignedHeads = new HashMap<>();
                    assignedHeads.put(lastModelId, lastHead);
                }
                assignedHeads.put(modelId, head);
            } else if (assignedHeads != null) {
                assignedHeads.put(modelId, head);
            }
            lastModelId = modelId;
            lastHead = head;
        }
    }

    private static String modelType(String modelId, String previous, String requested) {
        if (previous != null && requested != null && !previous.equals(requested)) {
            throw new IllegalArgumentException(
                    "Model %s already has type %s instead of %s"
                            .formatted(modelId, previous, requested));
        }
        return requested == null ? previous : requested;
    }

    private ModelCommitAssignment() {}
}
