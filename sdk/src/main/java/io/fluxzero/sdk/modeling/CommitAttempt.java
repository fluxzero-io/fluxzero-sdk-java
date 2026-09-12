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

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelRelationshipRead;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
import io.fluxzero.sdk.persisting.repository.ModelRepository;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Internal state of one model commit attempt, from its loaded begin-state through ordered changes and completion.
 * Instances used only as a handler read context leave the evaluation and completion portions empty.
 */
public final class CommitAttempt {
    private static final ThreadLocal<CommitAttempt> graphInvocation = new ThreadLocal<>();
    private volatile CommitAttempt activeGraphInvocation;
    private List<GraphReadProof> graphCaptures;
    private ModelAncestorResolver.AncestorReads ancestorReads;
    private CommitAttempt graphReadOwner;
    private Map<String, Class<?>> graphReadTypes;
    private Set<String> graphApplyReads;
    private Set<ModelRelationshipRead> graphRelationships;
    private Set<ModelRelationshipRead> graphApplyRelationships;
    private long graphReadGeneration;

    void resetGraphReads() {
        graphReadGeneration++;
        graphReadTypes = null;
        graphApplyReads = null;
        graphRelationships = null;
        graphApplyRelationships = null;
    }

    <T> Graph<T> trackGraph(Graph<T> graph, ModelRepository repository) {
        return graphReadOwner == null ? graph : Graphs.withReadContext(graph,
                new GraphReadContext(graphReadOwner, graphReadOwner.graphReadGeneration, repository, readStateIndex));
    }

    record GraphReadContext(CommitAttempt owner, long generation, ModelRepository repository, long boundary) {
    }

    static <T> Graph<T> historicalGraph(Graph<T> graph) {
        return graph == null || !(graph instanceof GraphView<?> view)
               || view.context().readContext() == null ? graph : Graphs.withReadContext(graph, null);
    }

    void bindGraphReads(CommitAttempt owner) {
        graphReadOwner = owner;
        recordAncestorReads(false);
    }

    /** Retains identity-only proof used to resolve indirect injected Models at this context's pinned boundary. */
    public CommitAttempt withAncestorReads(ModelAncestorResolver.AncestorReads reads) {
        if (reads != null && reads.stateIndex() != readStateIndex) {
            throw new IllegalArgumentException("Ancestor proof must use the loaded context's pinned boundary");
        }
        ancestorReads = reads;
        return this;
    }

    /** Returns the proof accompanying this context's ancestor selection, or {@code null} for direct targets. */
    public ModelAncestorResolver.AncestorReads ancestorReads() {
        return ancestorReads;
    }

    void recordAncestorReads(boolean apply) {
        if (ancestorReads == null || graphReadOwner == null) {
            return;
        }
        Set<String> previous = readCollector;
        if (apply && readCollector == null) {
            throw new IllegalStateException("Apply ancestor reads require an active apply collector");
        }
        if (!apply) {
            readCollector = null;
        }
        try {
            ancestorReads.modelTypes().forEach((id, type) -> recordGraphValue(this, id, type));
            ancestorReads.parentCollections().forEach(id -> recordGraphRelationship(this,
                    new ModelRelationshipRead(id, ModelRelationshipRead.Direction.PARENTS, null)));
        } finally {
            readCollector = previous;
        }
    }

    static <R> R withGraphReads(CommitAttempt context, Supplier<R> action) {
        CommitAttempt previous = graphInvocation.get();
        CommitAttempt owner = context.graphReadOwner;
        if (owner == null && previous == null) {
            return action.get();
        }
        CommitAttempt previousInvocation = owner == null ? null : owner.activeGraphInvocation;
        if (owner != null) {
            owner.activeGraphInvocation = context;
        }
        graphInvocation.set(context);
        try {
            return action.get();
        } finally {
            if (owner != null) {
                owner.activeGraphInvocation = previousInvocation;
            }
            if (previous == null) {
                graphInvocation.remove();
            } else {
                graphInvocation.set(previous);
            }
        }
    }

    static void graphValueRead(GraphView<?> graph) {
        CommitAttempt context = graphReadContext(graph);
        if (context == null) {
            return;
        }
        if (graph.node().data().type() == null) {
            throw graph.node().data().unknownType();
        }
        replayGraphReads(graph, graph.context().readProof());
        recordGraphValue(context, graph.node().data().id(), graph.node().data().type());
    }

    private static void recordGraphValue(CommitAttempt context, String id, Class<?> type) {
        CommitAttempt owner = context.graphReadOwner;
        synchronized (owner) {
            if (owner.graphReadTypes == null) {
                owner.graphReadTypes = new LinkedHashMap<>();
                owner.graphApplyReads = new LinkedHashSet<>();
            }
            owner.graphReadTypes.putIfAbsent(id, type);
            if (context.readCollector != null) {
                owner.graphApplyReads.add(id);
            }
            if (owner.graphCaptures != null) {
                for (GraphReadProof capture : owner.graphCaptures) {
                    capture.values.putIfAbsent(id, type);
                }
            }
        }
    }

    static void graphRelationshipRead(GraphView<?> graph,
                                     ModelRelationshipRead.Direction direction, String path) {
        CommitAttempt context = graphReadContext(graph);
        if (context == null) {
            return;
        }
        replayGraphReads(graph, graph.context().readProof());
        recordGraphRelationship(context, new ModelRelationshipRead(graph.id().toString(), direction, path));
    }

    private static void recordGraphRelationship(CommitAttempt context, ModelRelationshipRead read) {
        CommitAttempt owner = context.graphReadOwner;
        synchronized (owner) {
            if (owner.graphRelationships == null) {
                owner.graphRelationships = new LinkedHashSet<>();
                owner.graphApplyRelationships = new LinkedHashSet<>();
            }
            owner.graphRelationships.add(read);
            if (context.readCollector != null) {
                owner.graphApplyRelationships.add(read);
            }
            if (owner.graphCaptures != null) {
                for (GraphReadProof capture : owner.graphCaptures) {
                    capture.relationships.add(read);
                }
            }
        }
    }

    static boolean tracksGraph(GraphView<?> graph) {
        return graphReadContext(graph) != null;
    }

    static void graphAncestorsRead(GraphView<?> graph, ModelAncestorResolver.AncestorReads reads) {
        CommitAttempt context = graphReadContext(graph);
        if (context != null) {
            if (reads.stateIndex() != graph.stateIndex()) {
                throw new IllegalStateException("Ancestor traversal changed the pinned Graph boundary");
            }
            reads.modelTypes().forEach((id, type) -> recordGraphValue(context, id, type));
            reads.parentCollections().forEach(id -> recordGraphRelationship(context,
                    new ModelRelationshipRead(id, ModelRelationshipRead.Direction.PARENTS, null)));
        }
    }

    /** Carries reads through cached transformations without running user mappers or predicates a second time. */
    static final class GraphReadProof {
        private final CommitAttempt owner;
        private final long generation;
        private final Map<String, Class<?>> values = new LinkedHashMap<>();
        private final Set<ModelRelationshipRead> relationships = new LinkedHashSet<>();

        private GraphReadProof(CommitAttempt owner) {
            this.owner = owner;
            generation = owner.graphReadGeneration;
        }
    }

    static <T> T captureGraphReads(GraphView<?> graph, Supplier<T> action, Consumer<GraphReadProof> result) {
        CommitAttempt context = graphReadContext(graph);
        if (context == null) {
            return action.get();
        }
        GraphReadProof proof = new GraphReadProof(context.graphReadOwner);
        synchronized (proof.owner) {
            if (proof.owner.graphCaptures == null) {
                proof.owner.graphCaptures = new ArrayList<>();
            }
            proof.owner.graphCaptures.add(proof);
        }
        try {
            T value = action.get();
            result.accept(proof);
            return value;
        } finally {
            synchronized (proof.owner) {
                proof.owner.graphCaptures.remove(proof);
            }
        }
    }

    static void replayGraphReads(GraphView<?> graph, GraphReadProof proof) {
        if (proof == null) {
            return;
        }
        CommitAttempt context = graphReadContext(graph);
        if (context != null && proof.owner == context.graphReadOwner
            && proof.generation == context.graphReadOwner.graphReadGeneration) {
            synchronized (proof.owner) {
                proof.values.forEach((id, type) -> recordGraphValue(context, id, type));
                proof.relationships.forEach(read -> recordGraphRelationship(context, read));
            }
        }
    }

    private static CommitAttempt graphReadContext(GraphView<?> graph) {
        GraphReadContext provenance = graph.context().readContext();
        if (provenance == null) {
            return null;
        }
        CommitAttempt context = graphInvocation.get();
        if (context != null && context.graphReadOwner == null) {
            return null;
        }
        if (context == null) {
            // Joined parallel scans still belong to the synchronous invocation that owns this injected Graph.
            // Work escaping that invocation cannot silently attach reads to a completed evaluation.
            context = provenance.owner().activeGraphInvocation;
        }
        if (context == null) {
            return null;
        }
        if (provenance.owner() != context.graphReadOwner
            || provenance.generation() != context.graphReadOwner.graphReadGeneration
            || provenance.repository() != graph.state().repository()
            || graph.state().boundary().before()
            || graph.state().boundary().commitId() != null || graph.state().boundary().eventIndex() != null
            || provenance.boundary() != graph.stateIndex()) {
            return null;
        }
        return context;
    }

    /** Returns the relationship dependencies relevant to the policy; ACCEPT excludes assertion-only reads. */
    public List<ModelRelationshipRead> readRelationships(ModelConflictPolicy policy) {
        Set<ModelRelationshipRead> reads =
                ModelConflictPolicy.resolve(policy) == ModelConflictPolicy.ACCEPT
                        ? graphApplyRelationships : graphRelationships;
        return reads == null ? List.of() : List.copyOf(reads);
    }

    private static final MutationPlan.Resolution EMPTY_RESOLUTION =
            new MutationPlan.Resolution(List.of(), List.of());

    private long readStateIndex = -1L;
    private MutationPlan.Resolution resolution = EMPTY_RESOLUTION;
    private Map<String, Entity<?>> entities = Map.of();

    private List<String> readModelIds = List.of();
    private List<String> applyReadModelIds = List.of();
    private Set<String> readCollector;
    private Map<String, Class<?>> readModelTypes = Map.of();
    private List<Step> steps = List.of();
    private List<Change> changes = List.of();
    private Set<String> cascadeRootIds = Set.of();
    private volatile CompletableFuture<Object> completion;
    private boolean submitted;

    CommitAttempt() {
    }

    static CommitAttempt fromSteps(
            long readStateIndex,
            Collection<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            List<Step> steps) {
        CommitAttempt result = new CommitAttempt();
        result.evaluated(
                readStateIndex, readModelIds, readModelTypes,
                steps);
        return result;
    }

    static CommitAttempt fromChanges(
            long readStateIndex,
            Collection<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            DeserializingMessage message,
            List<Change> changes) {
        return fromSteps(
                readStateIndex, readModelIds, readModelTypes,
                List.of(new Step(message, changes)));
    }

    /** Creates a loaded begin-state for one resolved target set. */
    public static CommitAttempt create(
            long readStateIndex,
            MutationPlan.Resolution resolution,
            Map<String, ? extends Entity<?>> loadedModels) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(loadedModels, "loadedModels");
        if (resolution.models().size() == 1 && loadedModels.size() == 1) {
            MutationPlan.ResolvedModel target = resolution.models().getFirst();
            Entity<?> entity = loadedModels.get(target.modelId());
            if (entity == null) {
                throw missing(target, readStateIndex);
            }
            validateLoadedEntity(target, entity);
            CommitAttempt result = new CommitAttempt();
            result.readStateIndex = readStateIndex;
            result.resolution = resolution;
            result.entities = Map.of(target.modelId(), entity);
            return result;
        }
        LinkedHashMap<String, Entity<?>> entities =
                new LinkedHashMap<>(resolution.models().size());
        Map<String, Entity<?>> remaining = new LinkedHashMap<>(loadedModels);
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            Entity<?> entity = remaining.remove(target.modelId());
            if (entity == null) {
                throw missing(target, readStateIndex);
            }
            validateLoadedEntity(target, entity);
            entities.put(target.modelId(), entity);
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException(
                    "Commit load returned unrelated model IDs %s; only resolved commit targets may enter the context"
                            .formatted(remaining.keySet()));
        }
        CommitAttempt result = new CommitAttempt();
        result.readStateIndex = readStateIndex;
        result.resolution = resolution;
        result.entities = immutable(entities);
        return result;
    }

    private static IllegalArgumentException missing(
            MutationPlan.ResolvedModel target, long readStateIndex) {
        return new IllegalArgumentException(
                "Missing loaded model '%s' of type %s at state index %d"
                        .formatted(target.modelId(), target.modelType().getName(), readStateIndex));
    }

    /** Creates the allocation-minimal begin-state used by a direct single-model strategy. */
    public static CommitAttempt createSingle(
            long readStateIndex,
            String modelId,
            Class<?> modelType,
            MutationPlan.Access access,
            List<String> sourceProperties,
            Entity<?> entity) {
        MutationPlan.ResolvedModel target = new MutationPlan.ResolvedModel(
                modelId, modelType, access, sourceProperties);
        validateLoadedEntity(target, entity);
        CommitAttempt result = new CommitAttempt();
        result.readStateIndex = readStateIndex;
        result.resolution = new MutationPlan.Resolution(List.of(target), List.of());
        result.entities = Map.of(modelId, entity);
        return result;
    }

    private static void validateLoadedEntity(
            MutationPlan.ResolvedModel target, Entity<?> entity) {
        Object loadedId = entity.id();
        if (loadedId == null || !target.modelId().equals(loadedId.toString())) {
            throw new IllegalArgumentException(
                    "Loaded model for '%s' reports ID '%s'".formatted(target.modelId(), loadedId));
        }
        Class<?> loadedType = entity.type();
        if (loadedType == null || !target.modelType().isAssignableFrom(loadedType)) {
            throw new IllegalArgumentException(
                    "Loaded model '%s' has incompatible type %s; expected %s"
                            .formatted(target.modelId(),
                                       loadedType == null ? "null" : loadedType.getName(),
                                       target.modelType().getName()));
        }
    }

    public long readStateIndex() {
        return readStateIndex;
    }

    public List<String> modelIds() {
        ArrayList<String> result = new ArrayList<>(resolution.models().size());
        resolution.models().forEach(target -> result.add(target.modelId()));
        return List.copyOf(result);
    }

    public List<MutationPlan.ResolvedModel> targets() {
        return resolution.models();
    }

    public MutationPlan.ResolvedModel target(String modelId) {
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            if (target.modelId().equals(modelId)) {
                return target;
            }
        }
        return null;
    }

    public Entity<?> entity(String modelId) {
        if (readCollector != null && modelId != null) {
            readCollector.add(modelId);
        }
        return entities.get(modelId);
    }

    public Map<String, Entity<?>> entities() {
        if (readCollector != null) {
            readCollector.addAll(entities.keySet());
        }
        return entities;
    }

    public DeserializingMessage attachTo(DeserializingMessage message) {
        return Objects.requireNonNull(message, "message").putContext(CommitAttempt.class, this);
    }

    Entity<?> resolve(Class<?> modelType, String sourceProperty) {
        String entityIdProperty = Objects.requireNonNull(
                EntityMetadata.of(modelType).entityIdName(),
                () -> modelType.getName() + " has no @EntityId");
        Entity<?> candidate = null;
        Entity<?> secondCandidate = null;
        Entity<?> exact = null;
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            Class<?> targetType = target.modelType();
            if (!EntityMetadata.compatibleTypes(modelType, targetType)) {
                continue;
            }
            boolean propertyMatch = target.sourceProperties().contains(
                    sourceProperty == null ? entityIdProperty : sourceProperty);
            if (propertyMatch) {
                if (exact != null) {
                    throw ambiguous(modelType, sourceProperty == null ? entityIdProperty : sourceProperty,
                                    List.of(exact.id().toString(), target.modelId()));
                }
                exact = entities.get(target.modelId());
            }
            if (candidate == null) {
                candidate = entities.get(target.modelId());
            } else if (secondCandidate == null) {
                secondCandidate = entities.get(target.modelId());
            }
        }
        if (exact != null || sourceProperty != null) {
            return recordRead(exact);
        }
        if (secondCandidate != null) {
            throw ambiguous(modelType, null, resolution.models().stream()
                    .filter(target -> EntityMetadata.compatibleTypes(
                            modelType, target.modelType()))
                    .map(MutationPlan.ResolvedModel::modelId).toList());
        }
        return recordRead(candidate);
    }

    private Entity<?> recordRead(Entity<?> entity) {
        if (readCollector != null && entity != null) {
            readCollector.add(entity.id().toString());
        }
        return entity;
    }

    Set<String> collectReads(Set<String> collector) {
        Set<String> previous = readCollector;
        readCollector = collector;
        return previous;
    }

    MutationPlan.DirectReferences references(EntityMetadata.ModelParameter parameter) {
        return resolution.references().get(parameter);
    }

    boolean mayWrite(String modelId, Class<?> modelType, Executable handler) {
        MutationPlan.ResolvedModel target = target(modelId);
        if (target == null) {
            return false;
        }
        if (target.access().writes()) {
            return true;
        }
        String handlerSignature = handler == null ? null : handler.toGenericString();
        for (MutationPlan.DeferredWriteTarget deferred : resolution.deferredWrites()) {
            if (deferred.handler().equals(handlerSignature)
                && deferred.modelType().isAssignableFrom(modelType)
                && deferred.candidateModelIds().contains(modelId)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    CommitAttempt withValues(Map<String, Object> values) {
        if (values.isEmpty()) {
            return this;
        }
        LinkedHashMap<String, Entity<?>> updated = new LinkedHashMap<>(entities);
        values.forEach((modelId, value) -> {
            Entity<?> current = updated.get(modelId);
            MutationPlan.ResolvedModel target = target(modelId);
            if (current == null || target == null) {
                return;
            }
            Class modelType = value == null ? target.modelType() : value.getClass();
            Entity<?> entity = current instanceof ImmutableEntity<?> immutable
                    ? immutable.toBuilder().type(modelType).value(value).build()
                    : ImmutableEntity.builder()
                            .id(current.id()).type(modelType).value(value)
                            .idProperty(EntityMetadata.of(target.modelType()).entityIdName()).build();
            updated.put(modelId, entity);
        });
        CommitAttempt result = new CommitAttempt();
        result.readStateIndex = readStateIndex;
        result.resolution = resolution;
        result.entities = immutable(updated);
        result.graphReadOwner = graphReadOwner;
        result.ancestorReads = ancestorReads;
        return result;
    }

    void evaluated(
            long stateIndex,
            Collection<String> readIds,
            Map<String, Class<?>> readTypes,
            List<Step> steps) {
        evaluated(stateIndex, readIds, readIds, readTypes, steps);
    }

    void evaluated(
            long stateIndex,
            Collection<String> readIds,
            Collection<String> applyReadIds,
            Map<String, Class<?>> readTypes,
            List<Step> steps) {
        readStateIndex = stateIndex;
        readModelIds = List.copyOf(readIds);
        applyReadModelIds = readIds == applyReadIds ? readModelIds : List.copyOf(applyReadIds);
        readModelTypes = Map.copyOf(readTypes);
        if (graphReadTypes != null) {
            LinkedHashSet<String> combinedReads = new LinkedHashSet<>(readModelIds);
            combinedReads.addAll(graphReadTypes.keySet());
            readModelIds = List.copyOf(combinedReads);
            LinkedHashSet<String> combinedApplyReads = new LinkedHashSet<>(applyReadModelIds);
            combinedApplyReads.addAll(graphApplyReads);
            applyReadModelIds = List.copyOf(combinedApplyReads);
            LinkedHashMap<String, Class<?>> combinedTypes = new LinkedHashMap<>(readModelTypes);
            graphReadTypes.forEach(combinedTypes::putIfAbsent);
            readModelTypes = Map.copyOf(combinedTypes);
        }
        this.steps = List.copyOf(steps);
        ArrayList<Change> ordered = new ArrayList<>();
        for (Step step : this.steps) {
            ordered.addAll(step.changes());
        }
        changes = List.copyOf(ordered);
    }

    void cascadeRoots(Set<String> modelIds) {
        cascadeRootIds = Set.copyOf(modelIds);
    }

    public List<String> readModelIds() {
        return readModelIds;
    }

    /** Reads needed to preserve this policy's evaluation: ACCEPT only reapplies its original steps. */
    public List<String> readModelIds(ModelConflictPolicy policy) {
        return ModelConflictPolicy.resolve(policy) == ModelConflictPolicy.ACCEPT ? applyReadModelIds : readModelIds;
    }

    Map<String, Class<?>> readModelTypes() {
        return readModelTypes;
    }

    public List<Step> steps() {
        return steps;
    }

    public List<Change> transitions() {
        return changes;
    }

    public Set<String> cascadeRootIds() {
        return cascadeRootIds;
    }

    ModelConflictPolicy conflictPolicy(ModelConflictPolicy configured) {
        return conflictPolicy(configured, configured);
    }

    ModelConflictPolicy conflictPolicy(ModelConflictPolicy configured, ModelConflictPolicy creationPolicy) {
        ModelConflictPolicy application = ModelConflictPolicy.resolve(configured);
        if (changes.size() == 1 && readModelTypes.size() == 1
            && readModelTypes.containsKey(changes.getFirst().modelId())) {
            return transitionPolicy(changes.getFirst(), application, creationPolicy);
        }
        ModelConflictPolicy result = ModelConflictPolicy.ACCEPT;
        Set<String> written = new HashSet<>();
        for (Change change : changes) {
            written.add(change.modelId());
            result = strictest(result, transitionPolicy(change, application, creationPolicy));
        }
        for (Map.Entry<String, Class<?>> entry : readModelTypes.entrySet()) {
            if (!written.contains(entry.getKey())) {
                result = strictest(result, inherit(modelPolicy(entry.getValue()), application));
            }
        }
        return result;
    }

    CompletableFuture<Object> completion() {
        CompletableFuture<Object> result = completion;
        if (result == null) {
            synchronized (this) {
                result = completion;
                if (result == null) {
                    completion = result = new CompletableFuture<>();
                }
            }
        }
        return result;
    }

    /** Returns this exact commit completion through its public no-value contract. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    CompletableFuture<Void> completionResult() {
        return (CompletableFuture) completion();
    }

    void submit(Supplier<CompletableFuture<Object>> execution) {
        synchronized (this) {
            if (submitted) {
                throw new IllegalStateException("Model commit attempt was awaited twice");
            }
            submitted = true;
        }
        Objects.requireNonNull(execution.get(), "execution").whenComplete((value, failure) -> {
            if (failure == null) {
                completion().complete(value);
            } else {
                fail(failure);
            }
        });
    }

    void fail(Throwable failure) {
        completion().completeExceptionally(failure);
    }

    private static IllegalStateException ambiguous(
            Class<?> modelType, String sourceProperty, List<String> modelIds) {
        return new IllegalStateException(
                "Commit context contains multiple %s models%s: %s. Qualify the handler parameter with "
                        .formatted(modelType.getName(),
                                   sourceProperty == null ? "" : " for payload property '" + sourceProperty + "'",
                                   modelIds)
                + "@Association(\"payloadProperty\").");
    }

    private static ModelConflictPolicy transitionPolicy(
            Change change, ModelConflictPolicy application, ModelConflictPolicy creationPolicy) {
        boolean creation = change.before() == null && change.beforeSequenceNumber() < 0L;
        ModelConflictPolicy result = inherit(change.conflictPolicy(), creation ? creationPolicy : application);
        return creation && result == ModelConflictPolicy.ACCEPT ? ModelConflictPolicy.FAIL : result;
    }

    private static ModelConflictPolicy modelPolicy(Class<?> type) {
        return EntityMetadata.of(type).rootConfiguration()
                .filter(configuration -> configuration.kind() == EntityMetadata.RootKind.MODEL)
                .map(EntityMetadata.RootConfiguration::conflictPolicy)
                .orElse(ModelConflictPolicy.DEFAULT);
    }

    private static ModelConflictPolicy inherit(
            ModelConflictPolicy declared, ModelConflictPolicy application) {
        return declared == null || declared == ModelConflictPolicy.DEFAULT ? application : declared;
    }

    private static ModelConflictPolicy strictest(
            ModelConflictPolicy left, ModelConflictPolicy right) {
        return rank(right) > rank(left) ? right : left;
    }

    private static int rank(ModelConflictPolicy policy) {
        return switch (ModelConflictPolicy.resolve(policy)) {
            case FAIL -> 3;
            case RETRY -> 2;
            case ACCEPT, DEFAULT -> 1;
        };
    }

    private static <K, V> Map<K, V> immutable(LinkedHashMap<K, V> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        if (values.size() == 1) {
            Map.Entry<K, V> entry = values.entrySet().iterator().next();
            return Collections.singletonMap(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    /** One ordered mutation journal entry. */
    public record Step(
            DeserializingMessage message,
            List<Change> changes) {
        public Step {
            changes = List.copyOf(changes);
            if (!changes.isEmpty()) {
                boolean direct = changes.getFirst().directMutation();
                if (changes.stream().anyMatch(change -> change.directMutation() != direct)) {
                    throw new IllegalArgumentException(
                            "Direct mutations must occupy their own model commit step");
                }
            }
        }

        public boolean directMutation() {
            return !changes.isEmpty() && changes.getFirst().directMutation();
        }
    }

}
