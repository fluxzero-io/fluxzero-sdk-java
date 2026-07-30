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

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Orders local default-ACCEPT model commits only when their read sets overlap. Acquisition is atomic for the complete
 * read set, so multi-model commits cannot deadlock. The authoritative runtime conflict check remains necessary for
 * remote writers.
 */
final class ModelCommitCoordinator {
    private final Map<String, CompletableFuture<Void>>
            tails = new HashMap<>();

    <T> CompletableFuture<T> coordinate(
            Collection<String> modelIds,
            Function<Boolean, CompletableFuture<T>> operation) {
        return coordinate(modelIds, ignored -> {
        }, operation);
    }

    <T> CompletableFuture<T> coordinate(
            Collection<String> modelIds,
            Consumer<Boolean> contentionObserver,
            Function<Boolean, CompletableFuture<T>> operation) {
        Objects.requireNonNull(
                modelIds, "modelIds");
        Objects.requireNonNull(
                contentionObserver, "contentionObserver");
        Objects.requireNonNull(operation, "operation");
        List<String> keys =
                List.copyOf(
                        new LinkedHashSet<>(
                                modelIds));
        if (keys.isEmpty()) {
            return invoke(operation, false);
        }

        CompletableFuture<Void> release =
                new CompletableFuture<>();
        CompletableFuture<Void> predecessor;
        boolean contended;
        synchronized (tails) {
            List<CompletableFuture<Void>> predecessors =
                    keys.stream()
                            .map(tails::get)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
            contended = !predecessors.isEmpty();
            predecessor =
                    CompletableFuture.allOf(
                            predecessors.toArray(
                                    CompletableFuture[]::new));
            keys.forEach(key ->
                                 tails.put(
                                         key, release));
        }

        boolean reevaluate = contended;
        try {
            contentionObserver.accept(contended);
        } catch (Throwable failure) {
            synchronized (tails) {
                keys.forEach(key ->
                                     tails.remove(
                                             key,
                                             release));
            }
            release.complete(null);
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<T> result =
                predecessor.handle(
                                (ignored, failure) ->
                                        null)
                        .thenCompose(ignored ->
                                             invoke(
                                                     operation,
                                                     reevaluate));
        return result.whenComplete(
                (ignored, failure) -> {
                    synchronized (tails) {
                        keys.forEach(key ->
                                             tails.remove(
                                                     key,
                                                     release));
                    }
                    release.complete(null);
                });
    }

    private static <T> CompletableFuture<T> invoke(
            Function<Boolean, CompletableFuture<T>> operation,
            boolean contended) {
        try {
            return Objects.requireNonNull(
                    operation.apply(contended),
                    "Coordinated model commit returned null");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(
                    failure);
        }
    }
}
