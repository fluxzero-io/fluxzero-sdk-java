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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelDeadlineUpdate;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.scheduling.Deadline;
import io.fluxzero.sdk.scheduling.DeadlineInfo;
import io.fluxzero.sdk.scheduling.DeadlineMetadata;
import io.fluxzero.sdk.scheduling.Schedule;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Pure before/after deadline evaluation, owned by the Model pipeline. */
final class DeadlinePlan {
    record Declaration(
            Method method,
            String category,
            boolean command,
            boolean cancelOnDeletion,
            List<EntityMetadata.ModelParameter> parameters) {
        boolean contextual() {
            return !parameters.isEmpty();
        }

        boolean graph() {
            return parameters.stream().anyMatch(EntityMetadata.ModelParameter::graphWrapped);
        }
    }

    static List<Declaration> inspect(Class<?> type) {
        List<Declaration> result = new ArrayList<>();
        for (Method method : ReflectionUtils.getAnnotatedMethods(type, Deadline.class)) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) {
                throw new IllegalArgumentException(
                        "@Deadline requires an instance method returning a payload or Schedule: "
                                + method);
            }
            String category = method.getAnnotation(Deadline.class).value();
            if (category.isBlank()) {
                throw new IllegalArgumentException("@Deadline category must not be blank");
            }
            List<EntityMetadata.ModelParameter> parameters = new ArrayList<>();
            for (var parameter : method.getParameters()) {
                var model =
                        EntityMetadata.inspectModelParameter(parameter)
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "@Deadline parameters must be Models or"
                                                            + " Graphs: "
                                                                + parameter));
                if (model.collectionWrapped()
                        || model.entityWrapped()
                        || model.associationProperty() != null) {
                    throw new IllegalArgumentException(
                            "@Deadline accepts Model ancestors and Graph parameters: " + parameter);
                }
                parameters.add(model);
            }
            ReflectionUtils.ensureAccessible(method);
            result.add(
                    new Declaration(
                            method,
                            category,
                            method.getAnnotation(Deadline.class).command(),
                            method.getAnnotation(Deadline.class).cancelOnDeletion(),
                            List.copyOf(parameters)));
        }
        Set<String> categories = new LinkedHashSet<>();
        for (Declaration declaration : result) {
            if (!categories.add(declaration.category())) {
                throw new IllegalArgumentException(
                        "Duplicate @Deadline category: " + declaration.category());
            }
        }
        return List.copyOf(result);
    }

    private final DefaultModelRepository repository;
    private final Serializer serializer;
    private final Supplier<List<Class<?>>> knownTypes;

    DeadlinePlan(
            DefaultModelRepository repository,
            Serializer serializer,
            Supplier<List<Class<?>>> knownTypes) {
        this.repository = repository;
        this.serializer = serializer;
        this.knownTypes = knownTypes;
    }

    CommitAttempt evaluate(CommitAttempt attempt, DeserializingMessage message) {
        Instant referenceTime = Fluxzero.currentTime();
        return evaluate(attempt, message, referenceTime, referenceTime.toEpochMilli());
    }

    private CommitAttempt evaluate(CommitAttempt attempt, DeserializingMessage message,
                                   Instant referenceTime, long cutoff) {
        List<CommitAttempt.Step> originalSteps = attempt.steps();
        Map<String, Change> first = new LinkedHashMap<>(), last = new LinkedHashMap<>();
        for (Change change : attempt.transitions()) {
            if (change.updateState()) {
                first.putIfAbsent(change.modelId(), change);
                last.put(change.modelId(), change);
            }
        }
        Map<Class<?>, List<Declaration>> contextual = new LinkedHashMap<>();
        for (Class<?> type : knownTypes.get()) {
            List<Declaration> affected = EntityMetadata.of(type).deadlines().stream()
                    .filter(Declaration::contextual)
                    .filter(d -> last.values().stream().anyMatch(c -> affectedBy(type, d, c))).toList();
            if (!affected.isEmpty()) { contextual.put(type, affected); }
        }
        boolean local = last.values().stream().anyMatch(c -> !c.metadata().deadlines().isEmpty());
        if (!local && contextual.isEmpty()) {
            return attempt;
        }
        if (!contextual.isEmpty()) {
            attempt.ensureReadBoundary();
        }
        Map<String, Entity<?>> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        first.forEach((id, change) -> before.put(id, entity(change, change.before())));
        last.forEach((id, change) -> after.put(id, entity(change, change.after())));
        CommitAttempt oldContext = attempt.deadlineContext(before),
                newContext = attempt.deadlineContext(after);
        Map<String, Class<?>> candidates = new LinkedHashMap<>();
        Map<String, Set<Declaration>> affectedDeclarations = new LinkedHashMap<>();
        Map<String, Graph<?>> oldGraphs = new LinkedHashMap<>(), newGraphs = new LinkedHashMap<>();
        last.forEach((id, change) -> candidates.put(id, change.modelType()));
        for (CommitAttempt context : List.of(oldContext, newContext)) {
            if (contextual.isEmpty()) { break; }
            CommitAttempt.withGraphReads(context, () -> {
                Map<String, Graph<?>> graphs = context == oldContext ? oldGraphs : newGraphs;
                Map<String, Graph<?>> navigation = new LinkedHashMap<>();
                // Graph parameters can navigate the connected component. Plain Model parameters only
                // require descendants of changed ancestors; do not widen them because another method uses Graph.
                for (boolean connected : List.of(false, true)) {
                    List<Graph<?>> roots = new ArrayList<>();
                    for (var changed : last.entrySet()) {
                        if (contextual.entrySet().stream().noneMatch(e -> e.getValue().stream()
                                .anyMatch(d -> d.graph() == connected && affectedBy(e.getKey(), d, changed.getValue())))) {
                            continue;
                        }
                        roots.add(navigation.computeIfAbsent(changed.getKey(), id ->
                                Graphs.lazyRepositoryId(id, changed.getValue().modelType(), repository)));
                    }
                    for (Graph<?> graph : Graphs.related(roots, connected)) {
                        String id = graph.id().toString();
                        navigation.putIfAbsent(id, graph);
                        List<Declaration> declarations = graph.knownType().isEmpty() ? List.of()
                                : contextual.getOrDefault(graph.type(), List.of()).stream()
                                    .filter(d -> d.graph() == connected).toList();
                        if (!declarations.isEmpty()) {
                            candidates.putIfAbsent(id, graph.type());
                            graphs.putIfAbsent(id, graph);
                            affectedDeclarations.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                                    .addAll(declarations);
                        }
                    }
                }
                Graphs.modelValues(graphs.values().stream().map(g -> Graphs.<Object>cast(g)).toList());
                return null;
            });
        }
        List<ModelDeadlineUpdate> updates = new ArrayList<>();
        Map<String, Map<String, DeadlineInfo>> original = new LinkedHashMap<>(), planned = new LinkedHashMap<>();
        List<Change> contextualChanges = new ArrayList<>();
        for (var candidate : candidates.entrySet()) {
            String id = candidate.getKey();
            Class<?> type = candidate.getValue();
            Entity<?> stored = attempt.deadlineOrigin(id);
            if (stored == null) { stored = attempt.entity(id); }
            if (stored == null) { stored = attempt.graphReadEntity(id); }
            Graph<?> oldGraph = oldGraphs.get(id);
            Map<String, DeadlineInfo> recorded = stored == null && oldGraph != null
                    ? oldGraph.deadlines() : DeadlineMetadata.get(stored);
            original.put(id, recorded);
            Map<String, DeadlineInfo> desired = new LinkedHashMap<>(recorded);
            for (Declaration declaration : EntityMetadata.of(type).deadlines()) {
                if (!last.containsKey(id)
                        && !affectedDeclarations.getOrDefault(id, Set.of()).contains(declaration)) {
                    continue;
                }
                if (last.containsKey(id)
                        && last.get(id).after() == null
                        && !declaration.cancelOnDeletion()) {
                    continue;
                }
                Schedule old =
                        invoke(
                                declaration,
                                id,
                                type,
                                before.get(id),
                                oldGraphs,
                                oldContext,
                                referenceTime);
                Schedule next =
                        invoke(
                                declaration,
                                id,
                                type,
                                after.get(id),
                                newGraphs,
                                newContext,
                                referenceTime);
                boolean sameContent = sameContent(old, next);
                if (sameContent && (old == null || old.getDeadline().equals(next.getDeadline()))) {
                    continue;
                }
                String category = declaration.category();
                DeadlineInfo previous = recorded.get(category);
                // Compare declarations first; only changed existing work needs the original-time guard.
                Instant oldTime = previous == null ? old == null ? null : old.getDeadline() : previous.deadline();
                if (next != null && old != null && oldTime != null && oldTime.toEpochMilli() <= cutoff) {
                    continue;
                }
                String previousId = previous != null ? previous.scheduleId()
                        : old == null ? null : old.hasExplicitScheduleId() ? old.getScheduleId()
                        : ModelDeadlineUpdate.scheduleId(id, category);
                String scheduleId = next != null && next.hasExplicitScheduleId() ? next.getScheduleId()
                        : ModelDeadlineUpdate.scheduleId(id, category);
                if (next == null) {
                    desired.remove(category);
                    if (previousId != null) {
                        updates.add(new ModelDeadlineUpdate(id, category, previousId, null, declaration.cancelOnDeletion(), null));
                    }
                    continue;
                }
                desired.put(category, new DeadlineInfo(scheduleId, next.getDeadline(), declaration.command(),
                                                       declaration.cancelOnDeletion()));
                String messageId =
                        UUID.nameUUIDFromBytes(
                                        (ModelDeadlineUpdate.scheduleId(id, category) + ":"
                                                + message.getMessageId() + ":" + scheduleId)
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString();
                var provider = Fluxzero.get().userProvider();
                Metadata metadata = DeadlineMetadata.strip(next.getMetadata());
                if (provider != null) {
                    var systemUser = provider.getSystemUser();
                    if (systemUser == null && (provider.getActiveUser() != null || provider.containsUser(metadata))) {
                        throw new IllegalStateException("@Deadline requires a system user instead of inheriting the caller");
                    }
                    if (systemUser != null) { metadata = provider.addToMetadata(metadata, systemUser); }
                }
                Schedule schedule =
                        new Schedule(
                                next.getPayload(),
                                metadata,
                                messageId,
                                Fluxzero.currentTime(),
                                scheduleId,
                                next.getDeadline());
                var prepared =
                        Fluxzero.get()
                                .messageScheduler()
                                .forNamespace(ClientUtils.getConsumerNamespace(message))
                                .prepareDeadline(schedule, declaration.command());
                updates.add(
                        new ModelDeadlineUpdate(
                                id, category, previousId, prepared, declaration.cancelOnDeletion(),
                                old == null || oldTime == null ? null : oldTime.toEpochMilli()));
            }
            planned.put(id, Map.copyOf(desired));
            if (!last.containsKey(id) && !desired.equals(recorded)) {
                Entity<?> owner = stored != null ? stored : attempt.graphReadEntity(id);
                if (owner == null) { throw new IllegalStateException("Missing deadline owner " + id); }
                Object value = owner.get();
                contextualChanges.add(Change.applied(id, type, owner.sequenceNumber(), owner.lastEventIndex(),
                        value, value, null, java.util.function.UnaryOperator.identity(), false)
                        .checkedReplacement().withEffects(EntityMetadata.of(type).rootConfiguration().orElseThrow().eventSourced(), false, true)
                        .withDeadlines(desired).asDeadlineUpdate());
            }
        }
        attempt.deadlines(updates);
        Map<String, Change> finalChanges = new LinkedHashMap<>();
        attempt.transitions().forEach(c -> finalChanges.put(c.modelId(), c));
        List<CommitAttempt.Step> steps = new ArrayList<>();
        for (var step : attempt.steps()) {
            steps.add(new CommitAttempt.Step(step.message(), step.changes().stream().map(change -> {
                Map<String, DeadlineInfo> value = change == finalChanges.get(change.modelId())
                        ? planned.get(change.modelId()) : original.get(change.modelId());
                return value == null ? change : change.withDeadlines(value);
            }).toList(), step.cascadeCause()));
        }
        if (!contextualChanges.isEmpty()) {
            steps.add(new CommitAttempt.Step(message, contextualChanges));
        }
        attempt.deadlineSteps(steps);
        attempt.finishDeadlineReads();
        attempt.deadlineReevaluation(nextCutoff -> {
            attempt.deadlineSteps(originalSteps);
            evaluate(attempt, message, referenceTime, Math.max(cutoff, nextCutoff));
        });
        return attempt;
    }

    private Schedule invoke(
            Declaration declaration,
            String id,
            Class<?> type,
            Entity<?> direct,
            Map<String, Graph<?>> graphs,
            CommitAttempt context,
            Instant referenceTime) {
        return CommitAttempt.withGraphReads(
                context,
                () -> {
                    Graph<?> graph = graphs.get(id);
                    if (graph == null && (direct == null || declaration.contextual())) {
                        graph =
                                graphs.computeIfAbsent(
                                        id,
                                        ignored -> Graphs.lazyRepositoryId(id, type, repository));
                    }
                    Object value = direct == null ? graph.get() : direct.get();
                    if (value == null) {
                        return null;
                    }
                    Object[] arguments = new Object[declaration.parameters().size()];
                    for (int i = 0; i < arguments.length; i++) {
                        var parameter = declaration.parameters().get(i);
                        Graph<?> selected = graph.ancestor(parameter.modelType()).orElse(null);
                        arguments[i] =
                                parameter.graphWrapped()
                                        ? selected
                                        : selected == null ? null : selected.get();
                    }
                    try {
                        return Schedule.forDeadline(
                                declaration.method().invoke(value, arguments),
                                declaration.method().getAnnotation(Deadline.class),
                                referenceTime);
                    } catch (InvocationTargetException e) {
                        throw new IllegalStateException("Deadline evaluation failed", e.getCause());
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Cannot evaluate @Deadline", e);
                    }
                });
    }

    private boolean sameContent(Schedule left, Schedule right) {
        if (left == right) {
            return true;
        }
        if (left == null
                || right == null
                || !Objects.equals(explicitId(left), explicitId(right))
                || !businessMetadata(left).equals(businessMetadata(right))) {
            return false;
        }
        if (Objects.equals(left.getPayload(), right.getPayload())) {
            return true;
        }
        Data<byte[]> a = serializer.serialize(left.getPayload()),
                b = serializer.serialize(right.getPayload());
        return Objects.equals(a.getType(), b.getType())
                && a.getRevision() == b.getRevision()
                && Objects.equals(a.getFormat(), b.getFormat())
                && Arrays.equals(a.getValue(), b.getValue());
    }

    private static String explicitId(Schedule schedule) {
        return schedule.hasExplicitScheduleId() ? schedule.getScheduleId() : null;
    }

    private static Map<String, String> businessMetadata(Schedule schedule) {
        Map<String, String> result = new LinkedHashMap<>();
        schedule.getMetadata().entrySet().stream()
                .filter(e -> !e.getKey().startsWith("$"))
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static boolean affectedBy(Class<?> owner, Declaration declaration, Change change) {
        if (!potentiallyRelated(owner, change.modelType(), declaration.graph())) { return false; }
        if (declaration.graph()) { return true; }
        if (declaration.parameters().stream().anyMatch(p -> p.modelType().isAssignableFrom(change.modelType())
                || change.modelType().isAssignableFrom(p.modelType()))) { return true; }
        // Moving an intermediate ancestor can change which value is injected even if its own type is not injected.
        return !change.metadata().parentRelationships(change.modelId(), change.before())
                .equals(change.metadata().parentRelationships(change.modelId(), change.after()));
    }

    private static boolean potentiallyRelated(Class<?> owner, Class<?> changed, boolean graph) {
        Set<Class<?>> ancestors = new LinkedHashSet<>();
        if (!ancestorTypes(owner, ancestors)) {
            return true;
        }
        if (ancestors.stream()
                .anyMatch(t -> t.isAssignableFrom(changed) || changed.isAssignableFrom(t))) {
            return true;
        }
        if (!graph) {
            return false;
        }
        Set<Class<?>> changedAncestors = new LinkedHashSet<>();
        if (!ancestorTypes(changed, changedAncestors)) {
            return true;
        }
        return ancestors.stream()
                .anyMatch(
                        a ->
                                changedAncestors.stream()
                                        .anyMatch(
                                                b ->
                                                        a.isAssignableFrom(b)
                                                                || b.isAssignableFrom(a)));
    }

    private static boolean ancestorTypes(Class<?> type, Set<Class<?>> result) {
        if (!result.add(type)) {
            return true;
        }
        for (var parent : EntityMetadata.of(type).parentReferences()) {
            if (parent.parentModelTypes().isEmpty()) {
                return false;
            }
            for (Class<?> parentType : parent.parentModelTypes()) {
                if (!ancestorTypes(parentType, result)) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Entity<?> entity(Change change, Object value) {
        return ImmutableModelRoot.staged(
                change.modelId(),
                (Class) change.modelType(),
                change.metadata().entityIdName(),
                value,
                change.beforeSequenceNumber());
    }
}
