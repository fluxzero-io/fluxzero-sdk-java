/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.api.RequestResult;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static java.lang.Math.max;

public class CommandIdempotencyStore implements AutoCloseable {
    static final int DEFAULT_RETAINED_COMMAND_RESULTS_PER_CLIENT = 16_384;
    static final int DEFAULT_INACTIVE_CLIENT_TTL_SECONDS = 600;
    static final String COMMAND_RESULT_CACHE_SIZE_PROPERTY = "websocket.commandResultCacheSize";
    static final String INACTIVE_CLIENT_TTL_SECONDS_PROPERTY = "websocket.commandResultInactiveClientTtlSeconds";

    private final int retainedResultsPerClient;
    private final Duration inactiveClientTtl;
    private final TaskScheduler cleanupScheduler;
    private final Map<String, ClientCommandResults> clients = new ConcurrentHashMap<>();

    public CommandIdempotencyStore() {
        this(getIntegerProperty(COMMAND_RESULT_CACHE_SIZE_PROPERTY, DEFAULT_RETAINED_COMMAND_RESULTS_PER_CLIENT),
             Duration.ofSeconds(getIntegerProperty(
                     INACTIVE_CLIENT_TTL_SECONDS_PROPERTY, DEFAULT_INACTIVE_CLIENT_TTL_SECONDS)),
             new InMemoryTaskScheduler("CommandIdempotencyStore-cleanup"));
    }

    CommandIdempotencyStore(int retainedResultsPerClient, Duration inactiveClientTtl,
                            TaskScheduler cleanupScheduler) {
        this.retainedResultsPerClient = max(0, retainedResultsPerClient);
        this.inactiveClientTtl = inactiveClientTtl.isNegative() ? Duration.ZERO : inactiveClientTtl;
        this.cleanupScheduler = cleanupScheduler;
    }

    boolean isEnabled() {
        return retainedResultsPerClient > 0;
    }

    void registerSession(String clientId, String sessionId) {
        if (isEnabled()) {
            clientResults(clientId).registerSession(sessionId);
        }
    }

    void unregisterSession(String clientId, String sessionId) {
        if (!isEnabled()) {
            return;
        }
        ClientCommandResults client = clients.get(clientId);
        if (client != null) {
            client.unregisterSession(sessionId, () -> cleanupInactiveClient(clientId, client));
        }
    }

    CommandRegistration register(String clientId, long requestId) {
        return clientResults(clientId).register(requestId);
    }

    int clientCount() {
        return clients.size();
    }

    void executeExpiredCleanupTasks() {
        cleanupScheduler.executeExpiredTasks();
    }

    private ClientCommandResults clientResults(String clientId) {
        return clients.computeIfAbsent(clientId, ignored -> new ClientCommandResults(
                retainedResultsPerClient, inactiveClientTtl, cleanupScheduler));
    }

    private void cleanupInactiveClient(String clientId, ClientCommandResults client) {
        if (client.isInactive()) {
            clients.remove(clientId, client);
        }
    }

    @Override
    public void close() {
        cleanupScheduler.shutdown();
    }

    record CommandRegistration(boolean newCommand, CommandResult result) {
    }

    static class CommandResult {
        private final AtomicReference<Object> state = new AtomicReference<>(
                new CompletableFuture<Optional<RequestResult>>());

        boolean isDone() {
            return !(state.get() instanceof CompletableFuture<?>);
        }

        @SuppressWarnings("unchecked")
        void complete(Optional<RequestResult> result) {
            Object current = state.get();
            if (current instanceof CompletableFuture<?> future && state.compareAndSet(current, result)) {
                ((CompletableFuture<Optional<RequestResult>>) future).complete(result);
            }
        }

        @SuppressWarnings("unchecked")
        void whenComplete(BiConsumer<Optional<RequestResult>, Throwable> action) {
            Object current = state.get();
            if (current instanceof CompletableFuture<?> future) {
                ((CompletableFuture<Optional<RequestResult>>) future).whenComplete(action);
            } else {
                action.accept((Optional<RequestResult>) current, null);
            }
        }
    }

    private static class ClientCommandResults {
        private final int retainedResults;
        private final Duration inactiveClientTtl;
        private final TaskScheduler cleanupScheduler;
        private final Set<String> activeSessionIds = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<Long, CommandResult> results = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> completedResults = new ConcurrentLinkedQueue<>();
        private final AtomicReference<Registration> cleanupRegistration = new AtomicReference<>();

        private ClientCommandResults(int retainedResults, Duration inactiveClientTtl,
                                     TaskScheduler cleanupScheduler) {
            this.retainedResults = retainedResults;
            this.inactiveClientTtl = inactiveClientTtl;
            this.cleanupScheduler = cleanupScheduler;
        }

        private void registerSession(String sessionId) {
            activeSessionIds.add(sessionId);
            Optional.ofNullable(cleanupRegistration.getAndSet(null)).ifPresent(Registration::cancel);
        }

        private void unregisterSession(String sessionId, Runnable cleanupTask) {
            activeSessionIds.remove(sessionId);
            if (activeSessionIds.isEmpty()) {
                Registration nextCleanup = cleanupScheduler.schedule(inactiveClientTtl, cleanupTask::run);
                Optional.ofNullable(cleanupRegistration.getAndSet(nextCleanup)).ifPresent(Registration::cancel);
            }
        }

        private boolean isInactive() {
            return activeSessionIds.isEmpty();
        }

        private CommandRegistration register(long requestId) {
            CommandResult result = new CommandResult();
            CommandResult existing = results.putIfAbsent(requestId, result);
            if (existing != null) {
                return new CommandRegistration(false, existing);
            }
            result.whenComplete((response, error) -> {
                completedResults.add(requestId);
                trimCompletedResults();
            });
            trimCompletedResults();
            return new CommandRegistration(true, result);
        }

        private void trimCompletedResults() {
            while (results.size() > retainedResults) {
                Long requestId = completedResults.poll();
                if (requestId == null) {
                    return;
                }
                Optional.ofNullable(results.get(requestId))
                        .filter(CommandResult::isDone)
                        .ifPresent(result -> results.remove(requestId, result));
            }
        }
    }
}
