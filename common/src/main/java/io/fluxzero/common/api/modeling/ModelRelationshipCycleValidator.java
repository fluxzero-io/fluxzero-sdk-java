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

package io.fluxzero.common.api.modeling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates ordered relationship changes against a current parent graph without loading unrelated descendants.
 * Changes in one step become visible atomically; separate steps are validated independently.
 */
public final class ModelRelationshipCycleValidator {
    static final int MAX_DEPTH = 1_024;
    static final int MAX_MODELS = 100_000;

    private ModelRelationshipCycleValidator() {
    }

    /**
     * Validates ordered atomic relationship steps.
     */
    public static void validate(List<Step> steps, ParentLoader parentLoader) {
        if (steps.isEmpty()) {
            return;
        }
        Map<String, Set<String>> storedParents = new HashMap<>();
        Map<String, Set<String>> overrides = new HashMap<>();
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        steps.stream().flatMap(step -> step.changes().stream())
                .filter(Change::checkForCycle).map(Change::parentIds).forEach(roots::addAll);
        loadStoredParents(roots, storedParents, parentLoader);
        for (Step step : steps) {
            LinkedHashSet<String> changedChildren = new LinkedHashSet<>();
            for (Change change : step.changes()) {
                overrides.put(change.childId(), change.parentIds());
                if (change.checkForCycle()) {
                    changedChildren.add(change.childId());
                }
            }
            validateStep(changedChildren, overrides, storedParents, parentLoader);
        }
    }

    private static void validateStep(
            Set<String> changedChildren,
            Map<String, Set<String>> overrides,
            Map<String, Set<String>> storedParents,
            ParentLoader parentLoader) {
        if (changedChildren.isEmpty()) {
            return;
        }
        LinkedHashSet<String> reachable = new LinkedHashSet<>(changedChildren);
        if (reachable.size() > MAX_MODELS) {
            throw new ValidationException(
                    "Model relationship cycle validation exceeds maxModels " + MAX_MODELS);
        }
        List<String> frontier = List.copyOf(changedChildren);
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (int depth = 0; !frontier.isEmpty(); depth++) {
            LinkedHashSet<String> toLoad = new LinkedHashSet<>();
            for (String childId : frontier) {
                if (!overrides.containsKey(childId) && !storedParents.containsKey(childId)) {
                    toLoad.add(childId);
                }
            }
            loadStoredParents(toLoad, storedParents, parentLoader);
            LinkedHashSet<String> next = new LinkedHashSet<>();
            for (String childId : frontier) {
                Set<String> parents =
                        overrides.getOrDefault(childId, storedParents.getOrDefault(childId, Set.of()));
                graph.put(childId, parents);
                if (depth >= MAX_DEPTH && !parents.isEmpty()) {
                    throw new ValidationException(
                            "Model relationship cycle validation exceeds maxDepth " + MAX_DEPTH);
                }
                for (String parentId : parents) {
                    if (reachable.add(parentId)) {
                        if (reachable.size() > MAX_MODELS) {
                            throw new ValidationException(
                                    "Model relationship cycle validation exceeds maxModels " + MAX_MODELS);
                        }
                        next.add(parentId);
                    }
                }
            }
            frontier = List.copyOf(next);
        }
        detectCycle(changedChildren, graph);
    }

    private static void loadStoredParents(
            Set<String> childIds,
            Map<String, Set<String>> storedParents,
            ParentLoader parentLoader) {
        if (childIds.isEmpty()) {
            return;
        }
        Map<String, Set<String>> loaded = parentLoader.load(Set.copyOf(childIds));
        childIds.forEach(childId -> storedParents.put(childId, immutableIds(loaded.get(childId))));
    }

    private static void detectCycle(Set<String> roots, Map<String, Set<String>> graph) {
        Map<String, Visit> visits = new HashMap<>();
        ArrayList<String> path = new ArrayList<>();
        Map<String, Integer> pathIndices = new HashMap<>();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        for (String root : roots) {
            if (visits.containsKey(root)) {
                continue;
            }
            push(root, graph, visits, path, pathIndices, stack);
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (!frame.parents.hasNext()) {
                    stack.pop();
                    String completed = path.removeLast();
                    pathIndices.remove(completed);
                    visits.put(completed, Visit.VISITED);
                    continue;
                }
                String parentId = frame.parents.next();
                Visit visit = visits.get(parentId);
                if (visit == Visit.VISITING) {
                    int cycleStart = pathIndices.get(parentId);
                    ArrayList<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
                    cycle.add(parentId);
                    throw new ValidationException(
                            "Model relationship cycle detected: " + String.join(" -> ", cycle));
                }
                if (visit == null) {
                    push(parentId, graph, visits, path, pathIndices, stack);
                }
            }
        }
    }

    private static void push(
            String modelId,
            Map<String, Set<String>> graph,
            Map<String, Visit> visits,
            List<String> path,
            Map<String, Integer> pathIndices,
            ArrayDeque<Frame> stack) {
        visits.put(modelId, Visit.VISITING);
        pathIndices.put(modelId, path.size());
        path.add(modelId);
        stack.push(new Frame(graph.getOrDefault(modelId, Set.of()).iterator()));
    }

    private static Set<String> immutableIds(Collection<String> values) {
        return values == null || values.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    /**
     * One historically visible relationship boundary.
     */
    public record Step(List<Change> changes) {
        public Step {
            changes = List.copyOf(changes);
        }
    }

    /**
     * Complete desired parent-ID set for one changed child.
     */
    public record Change(String childId, Set<String> parentIds, boolean checkForCycle) {
        public Change(String childId, Set<String> parentIds) {
            this(childId, parentIds, true);
        }

        public Change {
            if (childId == null || childId.isBlank()) {
                throw new IllegalArgumentException("Model relationship childId must not be blank");
            }
            parentIds = immutableIds(parentIds);
        }
    }

    /**
     * Batch-loads current parents for child IDs. Missing children are interpreted as parentless.
     */
    @FunctionalInterface
    public interface ParentLoader {
        Map<String, Set<String>> load(Set<String> childIds);
    }

    /**
     * Signals that the proposed relationship boundary could not be proven acyclic.
     */
    public static class ValidationException extends IllegalArgumentException {
        public ValidationException(String message) {
            super(message);
        }
    }

    private enum Visit {
        VISITING,
        VISITED
    }

    private record Frame(Iterator<String> parents) {
    }
}
