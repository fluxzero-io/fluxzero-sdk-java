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

import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** SDK shorthands for relationship checks an application could express with the same Graph reads. */
final class ParentConstraintPlan {
    private final DefaultModelRepository repository;
    private final Supplier<List<Class<?>>> knownTypes;
    private final java.util.function.IntSupplier knownTypeCount;
    private volatile ProtectedCatalog protectedCatalog = new ProtectedCatalog(-1, List.of());

    ParentConstraintPlan(
            DefaultModelRepository repository,
            Supplier<List<Class<?>>> knownTypes,
            java.util.function.IntSupplier knownTypeCount) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.knownTypes = Objects.requireNonNull(knownTypes, "knownTypes");
        this.knownTypeCount = Objects.requireNonNull(knownTypeCount, "knownTypeCount");
    }

    CommitAttempt evaluate(CommitAttempt attempt) {
        if (!requiresValidation(attempt)) {
            return attempt;
        }
        LinkedHashMap<String, FinalChange> changes = finalChanges(attempt);
        if (changes.isEmpty()) {
            return attempt;
        }
        LinkedHashSet<String> applyReads = new LinkedHashSet<>();
        CommitAttempt validationContext = attempt.validationContext();
        Set<String> previousReads = validationContext.collectReads(applyReads);
        try {
            CommitAttempt.withGraphReads(validationContext, () -> {
                Validation validation = new Validation(validationContext, changes);
                validation.validateChangedChildren();
                validation.validateDeletedParents();
                return null;
            });
        } finally {
            validationContext.collectReads(previousReads);
        }
        attempt.finishValidationReads(applyReads);
        return attempt;
    }

    boolean requiresValidation(CommitAttempt attempt) {
        boolean deletion = false;
        for (CommitAttempt.Step step : attempt.steps()) {
            for (Change change : step.changes()) {
                if (change.after() == null && change.before() != null) {
                    deletion = true;
                }
                if (change.after() != null
                    && !change.metadata().constrainedParentReferences().isEmpty()) {
                    return true;
                }
            }
        }
        return deletion && !protectedReferences().isEmpty();
    }

    private List<ProtectedReference> protectedReferences() {
        int typeCount = knownTypeCount.getAsInt();
        ProtectedCatalog current = protectedCatalog;
        if (current.knownTypeCount() == typeCount) {
            return current.references();
        }
        synchronized (this) {
            current = protectedCatalog;
            if (current.knownTypeCount() != typeCount) {
                List<Class<?>> types = knownTypes.get();
                List<ProtectedReference> references = new ArrayList<>();
                for (Class<?> childType : types) {
                    for (EntityMetadata.ParentReference reference : EntityMetadata.of(childType).parentReferences()) {
                        if (reference.preventParentDeletion() || reference.required()) {
                            references.add(new ProtectedReference(childType, reference));
                        }
                    }
                }
                current = new ProtectedCatalog(typeCount, List.copyOf(references));
                protectedCatalog = current;
            }
            return current.references();
        }
    }

    private static LinkedHashMap<String, FinalChange> finalChanges(CommitAttempt attempt) {
        LinkedHashMap<String, FinalChange> result = new LinkedHashMap<>();
        for (CommitAttempt.Step step : attempt.steps()) {
            for (Change change : step.changes()) {
                result.compute(change.modelId(), (ignored, previous) -> previous == null
                        ? new FinalChange(change.modelId(), change.modelType(), change.metadata(),
                                          change.before(), change.after())
                        : new FinalChange(change.modelId(), change.modelType(), change.metadata(),
                                          previous.before(), change.after()));
            }
        }
        return result;
    }

    private final class Validation {
        private final CommitAttempt attempt;
        private final Map<String, FinalChange> changes;
        private final Map<ParentKey, Graph<?>> parents = new LinkedHashMap<>();
        private final Map<Scope, ScopeState> scopes = new LinkedHashMap<>();

        private Validation(CommitAttempt attempt, Map<String, FinalChange> changes) {
            this.attempt = attempt;
            this.changes = changes;
        }

        private void validateChangedChildren() {
            for (FinalChange child : changes.values()) {
                if (child.after() == null) { continue; }
                for (EntityMetadata.ParentReference reference
                        : child.metadata().constrainedParentReferences()) {
                    Collection<?> parentIds = reference.readIds(child.after());
                    if (reference.required() && parentIds.isEmpty()) {
                        throw ModelErrors.parentRequired(reference.parentModelType());
                    }
                    for (Object value : parentIds) {
                        Class<?> parentType = Objects.requireNonNull(reference.parentModelType(value));
                        String parentId = reference.repositoryId(value);
                        if (reference.required() && !exists(parentId, parentType)) {
                            throw ModelErrors.parentNotFound(parentType);
                        }
                        if (reference.maxChildren() >= 0) {
                            ScopeState state = scope(new Scope(
                                    parentId, parentType, child.type(), path(reference)));
                            if (state.finalIds().size() > reference.maxChildren()
                                && state.finalIds().size() > state.baselineCount()) {
                                throw ModelErrors.maxChildrenExceeded(parentType, child.type());
                            }
                        }
                    }
                }
            }
        }

        private void validateDeletedParents() {
            List<ProtectedReference> protectedReferences = null;
            for (FinalChange parent : changes.values()) {
                if (parent.before() == null || parent.after() != null) { continue; }
                if (protectedReferences == null) {
                    protectedReferences = ParentConstraintPlan.this.protectedReferences();
                }
                for (ProtectedReference declaration : protectedReferences) {
                    EntityMetadata.ParentReference reference = declaration.reference();
                    if (reference.parentModelTypes().stream().noneMatch(
                            type -> EntityMetadata.compatibleTypes(type, parent.type()))) { continue; }
                    ScopeState state = scope(new Scope(
                            parent.id(), parent.type(), declaration.childType(), path(reference)));
                    if (state.finalIds().stream().anyMatch(
                            childId -> referencesParent(childId, declaration, parent))) {
                        throw ModelErrors.parentDeletionBlocked(parent.type(), declaration.childType());
                    }
                }
            }
        }

        private boolean referencesParent(
                String childId, ProtectedReference declaration, FinalChange parent) {
            FinalChange staged = changes.get(childId);
            Object child = staged == null
                    ? model(childId, declaration.childType()).get()
                    : staged.after();
            if (child == null) { return false; }
            EntityMetadata.ParentReference reference = declaration.reference();
            return reference.readIds(child).stream().anyMatch(parentId -> {
                Class<?> referencedType = reference.parentModelType(parentId);
                return referencedType != null
                       && EntityMetadata.compatibleTypes(referencedType, parent.type())
                       && reference.repositoryId(parentId).equals(parent.id());
            });
        }

        private boolean exists(String parentId, Class<?> parentType) {
            FinalChange staged = changes.get(parentId);
            if (staged != null) {
                return staged.after() != null && parentType.isAssignableFrom(staged.type());
            }
            return parent(parentId, parentType).isPresent();
        }

        private ScopeState scope(Scope scope) {
            return scopes.computeIfAbsent(scope, key -> {
                String modelName = repository.modelName(key.childType());
                LinkedHashSet<String> baseline = parent(key.parentId(), key.parentType())
                        .children(key.path(), modelName, false).stream()
                        .map(child -> child.id().toString())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                LinkedHashSet<String> result = new LinkedHashSet<>(baseline);
                for (FinalChange child : changes.values()) {
                    if (!modelName.equals(repository.modelName(child.type()))) { continue; }
                    if (related(child.id(), child.type(), child.before(), key)) {
                        result.remove(child.id());
                    }
                    if (related(child.id(), child.type(), child.after(), key)) {
                        result.add(child.id());
                    }
                }
                return new ScopeState(baseline.size(), Set.copyOf(result));
            });
        }

        private boolean related(String childId, Class<?> childType, Object value, Scope scope) {
            if (value == null) { return false; }
            return EntityMetadata.of(childType).parentRelationships(childId, value).stream().anyMatch(relationship ->
                    relationship.parentId().equals(scope.parentId())
                    && Objects.equals(relationship.pathInParent(), scope.path())
                    && (relationship.parentType() == null
                        || relationship.parentType().equals(scope.parentType())));
        }

        private Graph<?> parent(String parentId, Class<?> parentType) {
            return model(parentId, parentType);
        }

        private Graph<?> model(String modelId, Class<?> modelType) {
            return parents.computeIfAbsent(new ParentKey(modelId, modelType), key -> attempt.trackGraph(
                    repository.loadGraph(
                            key.id(), key.type(),
                            ModelReadBoundary.state(
                                    attempt.readStateIndex(), !attempt.readsDurableStateOnly()),
                            Graph.Options.DEFAULT),
                    repository));
        }
    }

    private static String path(EntityMetadata.ParentReference reference) {
        return reference.pathInParent().isEmpty() ? null : reference.pathInParent();
    }

    private record FinalChange(
            String id, Class<?> type, EntityMetadata metadata, Object before, Object after) {}
    private record ParentKey(String id, Class<?> type) {}
    private record Scope(String parentId, Class<?> parentType, Class<?> childType, String path) {}
    private record ScopeState(int baselineCount, Set<String> finalIds) {}
    private record ProtectedReference(Class<?> childType, EntityMetadata.ParentReference reference) {}
    private record ProtectedCatalog(int knownTypeCount, List<ProtectedReference> references) {}
}
