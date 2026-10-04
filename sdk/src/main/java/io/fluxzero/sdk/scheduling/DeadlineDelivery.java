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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsWithDeadlines;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDeadlineClaim;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.LocalHandlerInput;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace;

/** Internal guard for the existing command and schedule delivery paths. */
public final class DeadlineDelivery implements HandlerInterceptor {
    private static final String MODEL = "$deadlineModel",
            ID = "$deadlineId",
            GENERATION = "$deadlineGeneration",
            DUE = "$deadlineAt";

    private static final class Execution {
        final ModelDeadlineClaim claim;
        final AtomicBoolean modelOperation = new AtomicBoolean();

        Execution(ModelDeadlineClaim claim) {
            this.claim = claim;
        }
    }

    public static Metadata metadata(ModelDeadlineClaim claim, Instant deadline) {
        return Metadata.empty()
                .with(MODEL, claim.modelId())
                .with(ID, claim.scheduleId())
                .with(GENERATION, claim.generation())
                .with(DUE, deadline.toEpochMilli());
    }

    static void preserveGuard(SerializedMessage target, Metadata source) {
        target.setMetadata(
                target.getMetadata()
                        .with(MODEL, source.get(MODEL))
                        .with(ID, source.get(ID))
                        .with(GENERATION, source.get(GENERATION))
                        .with(DUE, source.get(DUE)));
    }

    public static ModelDeadlineClaim claim(DeserializingMessage message) {
        Execution execution = message.getContext(Execution.class).orElse(null);
        if (execution != null) {
            return execution.claim;
        }
        Metadata metadata = message.getMetadata();
        return metadata.containsKey(GENERATION)
                ? new ModelDeadlineClaim(
                        metadata.get(MODEL), metadata.get(ID), metadata.get(GENERATION))
                : null;
    }

    /** Keeps a raw scheduled handler's claim on its one atomic Model operation. */
    public static void inherit(DeserializingMessage message) {
        DeserializingMessage source = DeserializingMessage.getCurrent();
        if (source != null && source != message) {
            source.getContext(Execution.class)
                    .ifPresent(e -> message.putContext(Execution.class, e));
        }
    }

    public static void beginModelOperation(DeserializingMessage message) {
        inherit(message);
        message.getContext(Execution.class)
                .ifPresent(
                        e -> {
                            if (!e.modelOperation.compareAndSet(false, true)) {
                                throw new IllegalStateException(
                                        "A deadline handler may perform one atomic Model"
                                                + " operation");
                            }
                        });
    }

    public static boolean current(DeserializingMessage message) {
        ModelDeadlineClaim claim = claim(message);
        return claim == null
                || Fluxzero.get()
                        .client()
                        .forNamespace(getConsumerNamespace(message))
                        .getEventStoreClient()
                        .checkModelDeadline(claim)
                        .join();
    }

    /** Aborts an obsolete delivery before user code continues after a rejected atomic operation. */
    public static final class Obsolete extends RuntimeException {
        public Obsolete() {
            super("Obsolete Model deadline", null, false, false);
        }
    }

    public static void requireCurrent(DeserializingMessage message) {
        if (!current(message)) {
            throw new Obsolete();
        }
    }

    @Override
    public boolean supportsPreparation() {
        return true;
    }

    @Override
    public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
        return (message, descriptor, combiner, next) ->
                handle(
                        message,
                        descriptor.getMethod() == null,
                        () -> next.apply(message, message, descriptor, combiner));
    }

    @Override
    public PreparedHandlerInputInterceptor prepareInput(HandlerDescriptor handler) {
        return (input, descriptor, next) -> {
            if (input instanceof LocalHandlerInput local && !local.containsMetadata(GENERATION)) {
                return next.apply(input, descriptor);
            }
            return handle(
                    input.getMessage(),
                    descriptor.getMethod() == null,
                    () -> next.apply(input, descriptor));
        };
    }

    @Override
    public Function<DeserializingMessage, Object> interceptHandling(
            Function<DeserializingMessage, Object> next, HandlerInvoker invoker) {
        return message -> handle(message, invoker.getMethod() == null, () -> next.apply(message));
    }

    private Object handle(DeserializingMessage message, boolean automatic, Supplier<Object> next) {
        ModelDeadlineClaim claim = claim(message);
        if (claim == null || message.getPayload() instanceof ScheduledCommand) {
            return next.get();
        }
        String due = message.getMetadata().get(DUE);
        if (due != null
                        && Fluxzero.currentTime()
                                .isBefore(Instant.ofEpochMilli(Long.parseLong(due)))
                || !current(message)) {
            return null;
        }
        Execution execution = new Execution(claim);
        message.putContext(Execution.class, execution);
        EventStoreClient client =
                Fluxzero.get()
                        .client()
                        .forNamespace(getConsumerNamespace(message))
                        .getEventStoreClient();
        try {
            Object result;
            if (automatic) {
                // Automatic Model handlers own batch completion and consume the claim in the Model
                // commit.
                result = next.get();
            } else {
                Object[] returned = new Object[1];
                AsyncCompletionScope.runAndAwait(
                        () -> {
                            returned[0] = next.get();
                            if (returned[0] instanceof CompletionStage<?> stage) {
                                AsyncCompletionScope.register(stage.toCompletableFuture());
                            }
                        });
                result = returned[0];
            }
            if (result instanceof CompletionStage<?> stage) {
                return stage.thenCompose(
                                value ->
                                        finish(execution, message, client)
                                                .thenApply(ignored -> value))
                        .handle(
                                (value, failure) ->
                                        failure == null ? value : suppressObsolete(failure));
            }
            finish(execution, message, client).join();
            return result;
        } catch (RuntimeException failure) {
            return suppressObsolete(failure);
        }
    }

    private static Object suppressObsolete(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof Obsolete) {
            return null;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new CompletionException(failure);
    }

    private static CompletableFuture<Void> finish(
            Execution execution, DeserializingMessage message, EventStoreClient client) {
        if (execution.modelOperation.get()) {
            return CompletableFuture.completedFuture(null);
        }
        CommitModels base =
                new CommitModels(
                        message.getMessageId() + ":deadline",
                        -1L,
                        List.of(),
                        List.of(new ModelCommitStep(null, false, List.of())),
                        ModelConflictPolicy.ACCEPT,
                        Guarantee.STORED,
                        true);
        return client.commitModels(new CommitModelsWithDeadlines(base, List.of(), execution.claim))
                .thenAccept(
                        result -> {
                            if (!result.isAccepted() && !result.isObsoleteDeadline()) {
                                throw new IllegalStateException("Deadline completion was rejected");
                            }
                        });
    }
}
