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
import io.fluxzero.common.api.modeling.ModelDeadlineClaim;
import io.fluxzero.common.api.modeling.ModelDeadlineUpdate;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.scheduling.Deadline;
import io.fluxzero.sdk.scheduling.DeadlineDelivery;
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
        Map<String, Change> first = new LinkedHashMap<>(), last = new LinkedHashMap<>();
        for (Change change : attempt.transitions()) {
            if (change.updateState()) {
                first.putIfAbsent(change.modelId(), change);
                last.put(change.modelId(), change);
            }
        }
        ModelDeadlineClaim claim = DeadlineDelivery.claim(message);
        Instant referenceTime = Fluxzero.currentTime();
        Set<Class<?>> contextual = new LinkedHashSet<>();
        boolean graphContext = false;
        for (Class<?> type : knownTypes.get()) {
            var declarations = EntityMetadata.of(type).deadlines();
            boolean graph = declarations.stream().anyMatch(Declaration::graph);
            if (last.values().stream()
                    .anyMatch(c -> potentiallyRelated(type, c.modelType(), graph))) {
                contextual.add(type);
                graphContext |= graph;
            }
        }
        boolean local = last.values().stream().anyMatch(c -> !c.metadata().deadlines().isEmpty());
        if (!local && contextual.isEmpty() && claim == null) {
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
        Map<String, Graph<?>> oldGraphs = new LinkedHashMap<>(), newGraphs = new LinkedHashMap<>();
        last.forEach((id, change) -> candidates.put(id, change.modelType()));
        if (!contextual.isEmpty()) {
            boolean connected = graphContext;
            for (CommitAttempt context : List.of(oldContext, newContext)) {
                CommitAttempt.withGraphReads(
                        context,
                        () -> {
                            Map<String, Graph<?>> graphs =
                                    context == oldContext ? oldGraphs : newGraphs;
                            Set<String> visited = new LinkedHashSet<>();
                            for (var changed : last.entrySet()) {
                                if (visited.contains(changed.getKey())) {
                                    continue;
                                }
                                Graph<?> root =
                                        Graphs.lazyRepositoryId(
                                                changed.getKey(),
                                                changed.getValue().modelType(),
                                                repository);
                                List<Graph<?>> selected = Graphs.related(root, connected);
                                for (Graph<?> graph : selected) {
                                    visited.add(graph.id().toString());
                                    if (graph.knownType().isPresent()
                                            && contextual.contains(graph.type())) {
                                        candidates.putIfAbsent(graph.id().toString(), graph.type());
                                        graphs.putIfAbsent(graph.id().toString(), graph);
                                    }
                                }
                            }
                            Graphs.modelValues(
                                    graphs.values().stream()
                                            .map(g -> Graphs.<Object>cast(g))
                                            .toList());
                            return null;
                        });
            }
        }
        List<ModelDeadlineUpdate> updates = new ArrayList<>();
        for (var candidate : candidates.entrySet()) {
            String id = candidate.getKey();
            Class<?> type = candidate.getValue();
            for (Declaration declaration : EntityMetadata.of(type).deadlines()) {
                if (!last.containsKey(id) && !declaration.contextual()) {
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
                if (same(old, next)) {
                    continue;
                }
                String category = declaration.category();
                String scheduleId =
                        next != null && next.hasExplicitScheduleId()
                                ? next.getScheduleId()
                                : ModelDeadlineUpdate.scheduleId(id, category);
                if (next == null) {
                    updates.add(
                            new ModelDeadlineUpdate(
                                    id, category, null, declaration.cancelOnDeletion()));
                    continue;
                }
                String generation =
                        UUID.nameUUIDFromBytes(
                                        (message.getMessageId() + ":" + scheduleId)
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString();
                var provider = Fluxzero.get().userProvider();
                Metadata metadata =
                        next.getMetadata()
                                .with(
                                        DeadlineDelivery.metadata(
                                                new ModelDeadlineClaim(id, scheduleId, generation),
                                                next.getDeadline()));
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
                                generation,
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
                                id, category, prepared, declaration.cancelOnDeletion()));
            }
        }
        attempt.deadlines(updates, claim, local || !updates.isEmpty() || claim != null);
        attempt.finishDeadlineReads();
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

    private boolean same(Schedule left, Schedule right) {
        if (left == right) {
            return true;
        }
        if (left == null
                || right == null
                || !Objects.equals(explicitId(left), explicitId(right))
                || !left.getDeadline().equals(right.getDeadline())
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
