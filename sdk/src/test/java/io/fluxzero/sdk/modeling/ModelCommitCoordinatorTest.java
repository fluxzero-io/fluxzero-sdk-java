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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommitCoordinatorTest {

    @Test
    void serializesOverlappingReadSetsAndMarksTheWaiterForReevaluation() {
        ModelCommitCoordinator subject =
                new ModelCommitCoordinator();
        CompletableFuture<String> firstCompletion =
                new CompletableFuture<>();
        AtomicBoolean firstContended =
                new AtomicBoolean();
        AtomicBoolean secondStarted =
                new AtomicBoolean();
        AtomicBoolean secondContended =
                new AtomicBoolean();

        CompletableFuture<String> first =
                subject.coordinate(
                        List.of("order-1"),
                        contended -> {
                            firstContended.set(
                                    contended);
                            return firstCompletion;
                        });
        CompletableFuture<String> second =
                subject.coordinate(
                        List.of(
                                "order-1",
                                "inventory-1"),
                        contended -> {
                            secondStarted.set(true);
                            secondContended.set(
                                    contended);
                            return CompletableFuture
                                    .completedFuture(
                                            "second");
                        });

        assertFalse(firstContended.get());
        assertFalse(secondStarted.get());

        firstCompletion.complete("first");

        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertTrue(secondStarted.get());
        assertTrue(secondContended.get());
    }

    @Test
    void keepsIndependentModelsParallelAndReleasesKeysAfterFailure() {
        ModelCommitCoordinator subject =
                new ModelCommitCoordinator();
        CompletableFuture<String> firstCompletion =
                new CompletableFuture<>();
        AtomicBoolean independentStarted =
                new AtomicBoolean();
        AtomicBoolean successorStarted =
                new AtomicBoolean();

        subject.coordinate(
                List.of("order-1"),
                ignored -> firstCompletion);
        CompletableFuture<String> independent =
                subject.coordinate(
                        List.of("inventory-1"),
                        contended -> {
                            independentStarted
                                    .set(true);
                            assertFalse(contended);
                            return CompletableFuture
                                    .completedFuture(
                                            "independent");
                        });
        CompletableFuture<String> successor =
                subject.coordinate(
                        List.of("order-1"),
                        ignored -> {
                            successorStarted.set(
                                    true);
                            return CompletableFuture
                                    .completedFuture(
                                            "successor");
                        });

        assertTrue(independent.isDone());
        assertTrue(independentStarted.get());
        assertFalse(successorStarted.get());

        firstCompletion.completeExceptionally(
                new IllegalStateException(
                        "expected"));

        assertTrue(successor.isDone());
        assertTrue(successorStarted.get());
    }
}
