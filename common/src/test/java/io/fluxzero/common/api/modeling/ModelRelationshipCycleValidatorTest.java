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

import io.fluxzero.common.modeling.ModelRelationshipTraversal;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRelationshipCycleValidatorTest {

    @Test
    void traversalCountsRootsTowardsModelLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRelationshipTraversal.<String>traverse(
                        List.of("a", "b"),
                        new ModelRelationshipTraversal.Policy(
                                -1, 1, false, false, "too many", null),
                        ignored -> List.of(), value -> value, null));
    }

    @Test
    void rejectsCycleCreatedInsideOneAtomicStep() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ModelRelationshipCycleValidator.validate(
                        List.of(step(
                                change("a", "b"),
                                change("b", "a"))),
                        ignored -> Map.of()));

        assertTrue(exception.getMessage().contains(
                "a -> b -> a"));
    }

    @Test
    void acceptsAtomicEdgeReversalRegardlessOfTargetOrder() {
        Map<String, Set<String>> current =
                Map.of("b", Set.of("a"));

        assertDoesNotThrow(
                () -> ModelRelationshipCycleValidator.validate(
                        List.of(step(
                                change("a", "b"),
                                change("b"))),
                        childIds -> parents(
                                current, childIds)));
    }

    @Test
    void rejectsTemporaryCycleAtHistoricalSubstepBoundary() {
        Map<String, Set<String>> current =
                Map.of("b", Set.of("a"));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRelationshipCycleValidator.validate(
                        List.of(
                                step(change("a", "b")),
                                step(change("b"))),
                        childIds -> parents(
                                current, childIds)));
    }

    @Test
    void followsStoredParentsInBatchesToFindDeepCycle() {
        Map<String, Set<String>> current =
                Map.of(
                        "b", Set.of("c"),
                        "c", Set.of("d"),
                        "d", Set.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ModelRelationshipCycleValidator.validate(
                        List.of(step(change("d", "b"))),
                        childIds -> parents(
                                current, childIds)));

        assertTrue(exception.getMessage().contains(
                "d -> b -> c -> d"));
    }

    @Test
    void prefetchDoesNotRejectADeepStoredPathCutByTheAtomicStep() {
        Map<String, Set<String>> current =
                new LinkedHashMap<>();
        String child = "b";
        for (int i = 0;
             i <= ModelRelationshipCycleValidator.MAX_DEPTH;
             i++) {
            String parent = "stored-" + i;
            current.put(child, Set.of(parent));
            child = parent;
        }

        assertDoesNotThrow(
                () -> ModelRelationshipCycleValidator.validate(
                        List.of(step(
                                change("a", "b"),
                                change("b"))),
                        childIds -> parents(
                                current, childIds)));
    }

    @Test
    void prefetchesIndependentStepRootsInOneBatch() {
        AtomicInteger loads = new AtomicInteger();
        List<ModelRelationshipCycleValidator.Step> steps =
                java.util.stream.IntStream.range(0, 128)
                        .mapToObj(index -> step(change("child-" + index, "parent-" + index)))
                        .toList();

        ModelRelationshipCycleValidator.validate(
                steps,
                childIds -> {
                    loads.incrementAndGet();
                    return parents(Map.of(), childIds);
                });

        assertEquals(1, loads.get());
    }

    private static ModelRelationshipCycleValidator.Step step(
            ModelRelationshipCycleValidator.Change... changes) {
        return new ModelRelationshipCycleValidator.Step(
                List.of(changes));
    }

    private static ModelRelationshipCycleValidator.Change change(
            String childId, String... parentIds) {
        return new ModelRelationshipCycleValidator.Change(
                childId, Set.of(parentIds));
    }

    private static Map<String, Set<String>> parents(
            Map<String, Set<String>> current,
            Set<String> childIds) {
        Map<String, Set<String>> result =
                new LinkedHashMap<>();
        for (String childId : childIds) {
            result.put(
                    childId,
                    current.getOrDefault(
                            childId, Set.of()));
        }
        return result;
    }
}
