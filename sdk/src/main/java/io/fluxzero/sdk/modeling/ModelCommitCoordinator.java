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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Orders local default-ACCEPT model commits only when their read sets overlap. Acquisition is atomic for the complete
 * read set, so multi-model commits cannot deadlock. The authoritative runtime conflict check remains necessary for
 * remote writers.
 */
final class ModelCommitCoordinator {
    private static final int LOCK_COUNT = 1_024;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails =
            new ConcurrentHashMap<>();
    private final ReentrantLock[] locks =
            java.util.stream.IntStream.range(0, LOCK_COUNT)
                    .mapToObj(ignored -> new ReentrantLock())
                    .toArray(ReentrantLock[]::new);

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
        if (modelIds.size() == 1) {
            return coordinateSingle(
                    modelIds.iterator().next(),
                    contentionObserver,
                    operation);
        }
        List<String> keys =
                List.copyOf(
                        new LinkedHashSet<>(
                                modelIds));
        if (keys.isEmpty()) {
            return invoke(operation, false);
        }

        CompletableFuture<Void> release = new CompletableFuture<>();
        List<CompletableFuture<Void>> predecessors =
                register(keys, release);
        boolean contended = !predecessors.isEmpty();
        CompletableFuture<Void> predecessor =
                CompletableFuture.allOf(
                        predecessors.toArray(
                                CompletableFuture[]::new));
        try {
            contentionObserver.accept(
                    contended);
        } catch (Throwable failure) {
            release(keys, release);
            return CompletableFuture.failedFuture(failure);
        }
        if (!contended) {
            return releaseAfter(
                    invoke(operation, false), keys,
                    release);
        }
        CompletableFuture<T> result =
                predecessor.handle(
                                (ignored, failure) ->
                                        null)
                        .thenCompose(ignored ->
                                             invoke(
                                                     operation,
                                                     true));
        return releaseAfter(result, keys, release);
    }

    private <T> CompletableFuture<T> coordinateSingle(
            String key,
            Consumer<Boolean> contentionObserver,
            Function<Boolean, CompletableFuture<T>> operation) {
        CompletableFuture<Void> release =
                new CompletableFuture<>();
        CompletableFuture<Void> predecessor =
                tails.put(key, release);
        boolean contended = predecessor != null;
        try {
            contentionObserver.accept(contended);
        } catch (Throwable failure) {
            release(key, release);
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<T> result = contended
                ? predecessor.handle(
                                (ignored, failure) -> null)
                        .thenCompose(ignored ->
                                invoke(operation, true))
                : invoke(operation, false);
        return result.whenComplete(
                (ignored, failure) ->
                        release(key, release));
    }

    private List<CompletableFuture<Void>> register(
            List<String> keys,
            CompletableFuture<Void> release) {
        int[] lockIndices = lockIndices(keys);
        for (int lockIndex : lockIndices) {
            locks[lockIndex].lock();
        }
        try {
            LinkedHashSet<CompletableFuture<Void>> predecessors =
                    new LinkedHashSet<>();
            for (String key : keys) {
                CompletableFuture<Void> predecessor =
                        tails.put(key, release);
                if (predecessor != null) {
                    predecessors.add(predecessor);
                }
            }
            return predecessors.isEmpty()
                    ? List.of()
                    : new ArrayList<>(predecessors);
        } finally {
            for (int index = lockIndices.length - 1;
                 index >= 0;
                 index--) {
                locks[lockIndices[index]].unlock();
            }
        }
    }

    private static int[] lockIndices(
            List<String> keys) {
        return keys.stream()
                .mapToInt(ModelCommitCoordinator::lockIndex)
                .distinct()
                .sorted()
                .toArray();
    }

    private static int lockIndex(
            String key) {
        int hash = key.hashCode();
        return (hash ^ hash >>> 16) & (LOCK_COUNT - 1);
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

    private <T> CompletableFuture<T> releaseAfter(
            CompletableFuture<T> result,
            List<String> keys,
            CompletableFuture<Void> release) {
        return result.whenComplete(
                (ignored, failure) ->
                        release(keys, release));
    }

    private void release(
            List<String> keys,
            CompletableFuture<Void> release) {
        keys.forEach(key -> tails.remove(key, release));
        release.complete(null);
    }

    private void release(
            String key,
            CompletableFuture<Void> release) {
        tails.remove(key, release);
        release.complete(null);
    }

}
