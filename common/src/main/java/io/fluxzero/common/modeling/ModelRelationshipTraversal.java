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

import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.search.ModelGraphComposition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Shared breadth-first traversal mechanics for temporal model relationships.
 *
 * <p>Stores remain responsible for selecting relationships at the required boundary and for choosing traversal
 * direction. This class owns frontier progression, visit semantics, graph bounds, edge collection and truncation
 * detection so production and in-memory stores cannot implement those rules independently.
 */
public final class ModelRelationshipTraversal {

    /** Traverses one relationship source without extra targets or per-level result handling. */
    public static <R> Result traverse(
            Collection<String> roots,
            Policy policy,
            RelationshipLoader<R> relationshipLoader,
            Function<R, String> targetSelector,
            Function<R, ModelGraphEdge> edgeMapper) {
        return traverse(
                roots, policy, List.of(relationshipLoader), targetSelector, edgeMapper,
                ignored -> List.of(), null);
    }

    /** Traverses one relationship source with optional extra targets and per-level result handling. */
    public static <R> Result traverse(
            Collection<String> roots,
            Policy policy,
            RelationshipLoader<R> relationshipLoader,
            Function<R, String> targetSelector,
            Function<R, ModelGraphEdge> edgeMapper,
            Function<Collection<String>, Collection<String>> extraTargetLoader,
            BiConsumer<Integer, Collection<String>> levelConsumer) {
        return traverse(
                roots, policy, List.of(relationshipLoader), targetSelector, edgeMapper,
                extraTargetLoader, levelConsumer);
    }

    /** Traverses one or more ordered relationship sources using the supplied storage-specific selectors. */
    public static <R> Result traverse(
            Collection<String> roots,
            Policy policy,
            List<RelationshipLoader<R>> relationshipLoaders,
            Function<R, String> targetSelector,
            Function<R, ModelGraphEdge> edgeMapper,
            Function<Collection<String>, Collection<String>> extraTargetLoader,
            BiConsumer<Integer, Collection<String>> levelConsumer) {
        LinkedHashSet<String> modelIds = new LinkedHashSet<>(roots);
        if (policy.maxModels != ModelGraphComposition.UNBOUNDED
            && modelIds.size() > policy.maxModels) {
            throw new IllegalArgumentException(policy.maxModelsMessage);
        }
        Set<Visit> visits = policy.revisitAtDifferentDepths ? new HashSet<>() : null;
        List<ModelGraphEdge> edges = edgeMapper == null ? List.of() : new ArrayList<>();
        List<String> frontier = List.copyOf(modelIds);
        int depth = 0;
        while (!frontier.isEmpty()
               && (policy.maxDepth == ModelGraphComposition.UNBOUNDED || depth < policy.maxDepth)) {
            depth++;
            List<String> next = new ArrayList<>();
            for (RelationshipLoader<R> loader : relationshipLoaders) {
                for (R relationship : loader.load(frontier)) {
                    if (edgeMapper != null) {
                        edges.add(edgeMapper.apply(relationship));
                    }
                    add(targetSelector.apply(relationship), depth, modelIds, visits, next, policy);
                }
            }
            for (String target : extraTargetLoader.apply(frontier)) {
                add(target, depth, modelIds, visits, next, policy);
            }
            if (levelConsumer != null) {
                levelConsumer.accept(depth, next);
            }
            frontier = List.copyOf(next);
        }
        if (policy.rejectDepthOverflow && !frontier.isEmpty()
            && (hasRelationships(frontier, relationshipLoaders)
                || !extraTargetLoader.apply(frontier).isEmpty())) {
            throw new IllegalArgumentException(policy.maxDepthMessage);
        }
        return new Result(List.copyOf(modelIds), List.copyOf(edges));
    }

    private static <R> boolean hasRelationships(
            Collection<String> frontier,
            List<RelationshipLoader<R>> loaders) {
        for (RelationshipLoader<R> loader : loaders) {
            if (loader.load(frontier).iterator().hasNext()) {
                return true;
            }
        }
        return false;
    }

    private static void add(
            String target,
            int depth,
            Set<String> modelIds,
            Set<Visit> visits,
            List<String> next,
            Policy policy) {
        boolean discovered = visits == null ? modelIds.add(target) : visits.add(new Visit(target, depth));
        if (!discovered) {
            return;
        }
        modelIds.add(target);
        if (policy.maxModels != ModelGraphComposition.UNBOUNDED
            && (modelIds.size() > policy.maxModels || visits != null && visits.size() > policy.maxModels)) {
            throw new IllegalArgumentException(policy.maxModelsMessage);
        }
        next.add(target);
    }

    /** Returns the first reachable cycle, including its repeated closing node, or an empty list. */
    public static List<String> cycle(
            Collection<String> roots,
            Function<String, ? extends Iterable<String>> targets) {
        Map<String, VisitState> visits = new HashMap<>();
        List<String> path = new ArrayList<>();
        Map<String, Integer> pathIndices = new HashMap<>();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        for (String root : roots) {
            if (visits.containsKey(root)) {
                continue;
            }
            push(root, targets, visits, path, pathIndices, stack);
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (!frame.targets.hasNext()) {
                    stack.pop();
                    String completed = path.removeLast();
                    pathIndices.remove(completed);
                    visits.put(completed, VisitState.VISITED);
                    continue;
                }
                String target = frame.targets.next();
                VisitState state = visits.get(target);
                if (state == VisitState.VISITING) {
                    List<String> result = new ArrayList<>(
                            path.subList(pathIndices.get(target), path.size()));
                    result.add(target);
                    return List.copyOf(result);
                }
                if (state == null) {
                    push(target, targets, visits, path, pathIndices, stack);
                }
            }
        }
        return List.of();
    }

    private static void push(
            String modelId,
            Function<String, ? extends Iterable<String>> targets,
            Map<String, VisitState> visits,
            List<String> path,
            Map<String, Integer> pathIndices,
            ArrayDeque<Frame> stack) {
        visits.put(modelId, VisitState.VISITING);
        pathIndices.put(modelId, path.size());
        path.add(modelId);
        Iterable<String> next = targets.apply(modelId);
        stack.push(new Frame((next == null ? List.<String>of() : next).iterator()));
    }

    /** Ordered traversal result, including edges only when an edge mapper was supplied. */
    public record Result(List<String> modelIds, List<ModelGraphEdge> edges) {
        /** Returns all discovered model IDs except the supplied roots. */
        public Set<String> without(Collection<String> roots) {
            LinkedHashSet<String> result = new LinkedHashSet<>(modelIds);
            result.removeAll(roots);
            return Set.copyOf(result);
        }
    }

    /** Traversal bounds and the failure messages associated with exceeding them. */
    public record Policy(
            int maxDepth,
            int maxModels,
            boolean rejectDepthOverflow,
            boolean revisitAtDifferentDepths,
            String maxModelsMessage,
            String maxDepthMessage) {
    }

    /** Supplies the relationships for one breadth-first frontier without requiring materialization. */
    @FunctionalInterface
    public interface RelationshipLoader<R> {
        Iterable<R> load(Collection<String> frontier);
    }

    private record Visit(String modelId, int depth) {
    }

    private record Frame(Iterator<String> targets) {
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    private ModelRelationshipTraversal() {
    }
}
