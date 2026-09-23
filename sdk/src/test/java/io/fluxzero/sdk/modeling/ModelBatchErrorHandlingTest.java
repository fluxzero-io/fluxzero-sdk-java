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
package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerHandlingMode;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.ObjectUtils.unwrapException;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class ModelBatchErrorHandlingTest {
    private static final AtomicInteger retryAttempts = new AtomicInteger();

    @AfterEach
    void cleanup() {
        TestFixture.shutDownActiveFixtures();
    }

    static Stream<Arguments> policies() {
        return Stream.of(ConsumerHandlingMode.SYNC, ConsumerHandlingMode.ASYNC).flatMap(mode ->
                Stream.of(ModelCommitPolicy.values()).filter(p -> p != ModelCommitPolicy.DEFAULT)
                        .flatMap(policy -> Stream.of(false, true).map(technical ->
                                Arguments.of(mode, policy, technical))));
    }

    @ParameterizedTest
    @MethodSource("policies")
    void handlesARejectedCommandOnceWithoutRetryingItsBatch(ConsumerHandlingMode mode, ModelCommitPolicy policy,
                                                           boolean technical) {
        List<String> failures = new CopyOnWriteArrayList<>();
        var first = new Create("first", false);
        var rejected = new Create("rejected", true, technical);
        var last = new Create("last", false);
        TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(COMMAND, c -> c.toBuilder()
                        .handlingMode(mode).errorHandler((error, description, retry) -> {
                            failures.add(description);
                            return error;
                        }).build()))
                .withProperty(ModelCommitPolicy.PROPERTY, policy.name())
                .registerHandlers(Create.class)
                .whenExecuting(fc -> {
                    var results = Fluxzero.sendCommands(first, rejected, last);
                    assertNull(results.getFirst().join());
                    var error = results.get(1).handle((result, failure) -> unwrapException(failure)).join();
                    if (technical) {
                        assertInstanceOf(TechnicalException.class, error);
                    } else {
                        assertInstanceOf(IllegalCommandException.class, error);
                        assertEquals("capacity exceeded", error.getMessage());
                    }
                    assertNull(results.getLast().join());
                })
                .expectSuccessfulResult()
                .expectEvents(first, last)
                .expectThat(fc -> {
                    assertEquals(new Item("first"), Fluxzero.loadModel("first", Item.class).get());
                    assertNull(Fluxzero.loadModel("rejected", Item.class).get());
                    assertEquals(new Item("last"), Fluxzero.loadModel("last", Item.class).get());
                    assertEquals(1, failures.size(), failures.toString());
                    assertTrue(failures.getFirst().startsWith("Handler "), failures.toString());
                });
    }

    @ParameterizedTest
    @MethodSource("policies")
    void fireAndForgetStillReportsTheCommandFailure(ConsumerHandlingMode mode, ModelCommitPolicy policy,
                                                    boolean technical) {
        List<String> failures = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> handled = new CompletableFuture<>();
        TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(COMMAND, c -> c.toBuilder()
                        .handlingMode(mode).errorHandler((error, description, retry) -> {
                            failures.add(description);
                            handled.complete(null);
                            return error;
                        }).build()))
                .withProperty(ModelCommitPolicy.PROPERTY, policy.name())
                .registerHandlers(Create.class)
                .whenExecuting(fc -> {
                    Fluxzero.sendAndForgetCommand(new Create("rejected", true, technical));
                    // No command result exists to synchronize with the consumer's asynchronous error handler.
                    handled.get(5, TimeUnit.SECONDS);
                })
                .expectSuccessfulResult()
                .expectNoEvents()
                .expectThat(fc -> {
                    assertNull(Fluxzero.loadModel("rejected", Item.class).get());
                    assertEquals(1, failures.size(), failures.toString());
                    assertTrue(failures.getFirst().startsWith("Handler "), failures.toString());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customRecoveryResultIsNotOverwrittenByTheRejectedCommit(boolean asynchronousRecovery) {
        var calls = new AtomicInteger();
        TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(COMMAND, c -> c.toBuilder()
                        .errorHandler((error, description, retry) -> {
                            calls.incrementAndGet();
                            return asynchronousRecovery ? CompletableFuture.completedFuture("recovered") : "recovered";
                        }).build()), Create.class)
                .whenCommand(new Create("rejected", true))
                .expectResult("recovered")
                .expectNoEvents()
                .expectThat(fc -> {
                    assertEquals(1, calls.get());
                    assertNull(Fluxzero.loadModel("rejected", Item.class).get());
                });
    }

    @ParameterizedTest
    @EnumSource(value = ModelCommitPolicy.class,
            names = {"ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH", "SYNC_AFTER_BATCH", "ASYNC_AFTER_BATCH"})
    void explicitErrorHandlerRetryRetriesOnlyTheRejectedCommand(ModelCommitPolicy policy) {
        retryAttempts.set(0);
        var calls = new AtomicInteger();
        TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(COMMAND, c -> c.toBuilder()
                        .errorHandler((error, description, retry) -> {
                            calls.incrementAndGet();
                            try {
                                return retry.call();
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }).build()))
                .withProperty(ModelCommitPolicy.PROPERTY, policy.name())
                .withProperty(ConsumerConfiguration.UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY,
                              ConsumerConfiguration.DEFAULT_APP_CONSUMER_MODE)
                .registerHandlers(RetryCreate.class, Create.class)
                .whenExecuting(fc -> {
                    var results = Fluxzero.sendCommands(new Create("first", false), new RetryCreate("retry"));
                    results.forEach(CompletableFuture::join);
                })
                .expectSuccessfulResult()
                .expectEvents(new Create("first", false), new RetryCreate("retry"))
                .expectThat(fc -> {
                    assertEquals(1, calls.get());
                    assertEquals(2, retryAttempts.get());
                    assertEquals(new Item("retry"), Fluxzero.loadModel("retry", Item.class).get());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesAnInterceptorsResponseAfterTheCommit(boolean asyncResponse) {
        TestFixture.createAsync(DefaultFluxzero.builder().addHandlerInterceptor((next, invoker) -> message -> {
                    next.apply(message);
                    return asyncResponse ? CompletableFuture.completedFuture("custom response") : "custom response";
                }, COMMAND), Create.class)
                .whenCommand(new Create("custom", false))
                .expectResult("custom response")
                .expectThat(fc -> assertEquals(new Item("custom"), Fluxzero.loadModel("custom", Item.class).get()));
    }

    @Model
    record Item(@EntityId String id) {}

    record Create(String id, boolean reject, boolean technical) {
        Create(String id, boolean reject) {
            this(id, reject, false);
        }

        @AssertLegal
        void validate() {
            if (reject && technical) {
                throw new IllegalStateException("broken handler");
            }
            if (reject) {
                throw new IllegalCommandException("capacity exceeded");
            }
        }

        @Apply
        Item apply() {
            return new Item(id);
        }
    }

    record RetryCreate(String id) {
        @AssertLegal
        void validate() {
            if (retryAttempts.getAndIncrement() == 0) {
                throw new IllegalCommandException("try again");
            }
        }

        @Apply
        Item apply() {
            return new Item(id);
        }
    }
}
