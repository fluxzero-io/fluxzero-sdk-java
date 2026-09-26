/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.fluxzero.sdk.publishing;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULT_DELIVERY_GUARANTEE_PROPERTY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandDeliveryTest {
    @ParameterizedTest
    @EnumSource(value = Guarantee.class, names = {"NONE", "SENT", "STORED"})
    void applicationAndNamespaceOwnPolicyForEveryWriteFamily(Guarantee expected) {
        LocalClient client = client();
        LocalClient namespaced = client();
        doReturn(namespaced).when(client).forNamespace("other");
        try (Fluxzero app = application(client, expected);
             Fluxzero other = application(client(), expected == Guarantee.NONE ? Guarantee.STORED : Guarantee.NONE)) {
            other.apply(ignored -> {
                for (String namespace : new String[]{null, "other"}) {
                    app.documentStore().forNamespace(namespace).prepareIndex("value").id("id").collection("documents").indexAndForget();
                    app.messageScheduler().forNamespace(namespace).cancelSchedule("scheduleId");
                    app.resultGateway().forNamespace(namespace).respond("result", "target", 1);
                }
                return null;
            });
            for (LocalClient transport : new LocalClient[]{client, namespaced}) {
                verify(transport.getSearchClient()).index(anyList(), eq(expected), eq(false));
                verify(transport.getSchedulingClient()).cancelSchedule("scheduleId", expected);
                verify(transport.getGatewayClient(MessageType.RESULT)).append(eq(expected), any(SerializedMessage[].class));
            }
        }
    }

    @Test
    void batchWaitsForIgnoredIndexFutureAndPropagatesFailure() throws Exception {
        LocalClient client = client();
        try (Fluxzero app = application(client, Guarantee.STORED)) {
            CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
            SearchClient searchClient = client.getSearchClient();
            doReturn(acknowledgement).when(searchClient).index(anyList(), any(), anyBoolean());
            CountDownLatch issued = new CountDownLatch(1);
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> AsyncCompletionScope.runAndAwaitBeforeCommit(() -> {
                app.documentStore().prepareIndex("value").id("id").collection("documents").indexAndForget();
                issued.countDown();
            }));
            try {
                assertTrue(issued.await(5, TimeUnit.SECONDS));
                assertFalse(batch.isDone());
                acknowledgement.completeExceptionally(new IllegalStateException("index rejected"));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> batch.get(5, TimeUnit.SECONDS));
                assertEquals("index rejected", failure.getCause().getMessage());
            } finally {
                acknowledgement.complete(null);
            }
        }
    }

    @Test
    void explicitDurabilityIsNotWeakenedByApplicationOverride() {
        LocalClient client = client();
        try (Fluxzero app = application(client, Guarantee.NONE)) {
            app.documentStore().prepareIndex("value").id("id").collection("documents").indexAndWait();
            app.keyValueStore().store("key", "value", Guarantee.STORED);
            verify(client.getSearchClient()).index(anyList(), eq(Guarantee.STORED), eq(false));
            verify(client.getKeyValueClient()).putValue(eq("key"), any(), eq(Guarantee.STORED));
        }
    }

    @Test
    void caughtIndexAndWaitFailureDoesNotPoisonSuccessfulRetry() {
        LocalClient client = client();
        try (Fluxzero app = application(client, Guarantee.STORED)) {
            SearchClient searchClient = client.getSearchClient();
            doReturn(CompletableFuture.failedFuture(new IllegalStateException("retry")),
                     CompletableFuture.completedFuture(null))
                    .when(searchClient).index(anyList(), any(), anyBoolean());
            AsyncCompletionScope.runAndAwaitBeforeCommit(() -> {
                var operation = app.documentStore().prepareIndex("value").id("id").collection("documents");
                assertThrows(Exception.class, operation::indexAndWait);
                operation.indexAndWait();
            });
        }
    }

    @Test
    void caughtBlockingKeyValueFailureDoesNotPoisonSuccessfulRetry() {
        LocalClient client = client();
        try (Fluxzero app = application(client, Guarantee.NONE)) {
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            KeyValueClient keyValueClient = client.getKeyValueClient();
            doAnswer(ignored -> AsyncCompletionScope.register(attempts.getAndIncrement() == 0
                    ? CompletableFuture.failedFuture(new IllegalStateException("retry"))
                    : CompletableFuture.completedFuture(null)))
                    .when(keyValueClient).putValue(anyString(), any(), any());
            AsyncCompletionScope.runAndAwaitBeforeCommit(() -> {
                assertThrows(Exception.class, () -> app.keyValueStore().store("key", "value"));
                app.keyValueStore().store("key", "value");
            });
        }
    }

    @Test
    void blockingIndexKeepsNestedSideEffectsInTheBatch() throws Exception {
        LocalClient client = client();
        try (Fluxzero app = application(client, Guarantee.STORED)) {
            CompletableFuture<Void> nestedWrite = new CompletableFuture<>();
            SearchClient searchClient = client.getSearchClient();
            doAnswer(ignored -> {
                // Custom/local clients can invoke handlers before returning their own acknowledgement.
                AsyncCompletionScope.register(nestedWrite);
                return CompletableFuture.completedFuture(null);
            }).when(searchClient).index(anyList(), any(), anyBoolean());
            CountDownLatch indexed = new CountDownLatch(1);
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> AsyncCompletionScope.runAndAwaitBeforeCommit(() -> {
                app.documentStore().prepareIndex("value").id("id").collection("documents").indexAndWait();
                indexed.countDown();
            }));
            try {
                assertTrue(indexed.await(5, TimeUnit.SECONDS));
                assertFalse(batch.isDone());
                nestedWrite.completeExceptionally(new IllegalStateException("nested write rejected"));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> batch.get(5, TimeUnit.SECONDS));
            } finally {
                nestedWrite.complete(null);
            }
        }
    }

    @Test
    void delayedScheduleAcknowledgementPreservesNestedLocalWrites() throws Exception {
        LocalClient client = client();
        io.fluxzero.common.TaskScheduler timer = mock(io.fluxzero.common.TaskScheduler.class);
        when(timer.clock()).thenReturn(java.time.Clock.systemUTC());
        when(timer.schedule(any(java.time.Instant.class), any(io.fluxzero.common.ThrowingRunnable.class)))
                .thenReturn(io.fluxzero.common.Registration.noOp());
        try (Fluxzero app = DefaultFluxzero.builder().disableShutdownHook().makeApplicationInstance(false)
                .replaceTaskScheduler(ignored -> timer).build(client)) {
            CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
            CompletableFuture<Void> nestedWrite = new CompletableFuture<>();
            SchedulingClient scheduler = client.getSchedulingClient();
            SearchClient search = client.getSearchClient();
            doAnswer(invocation -> {
                invocation.callRealMethod();
                return acknowledgement;
            }).when(scheduler).schedule(any(), any(SerializedSchedule[].class));
            doReturn(nestedWrite).when(search).index(anyList(), any(), anyBoolean());
            CountDownLatch handled = new CountDownLatch(1);
            ((io.fluxzero.sdk.tracking.handling.HasLocalHandlers) app.messageScheduler()).registerHandler(new Object() {
                @io.fluxzero.sdk.tracking.handling.LocalHandler
                @io.fluxzero.sdk.tracking.handling.HandleSchedule
                void handle(String ignored) {
                    app.documentStore().index("value", "id", "documents");
                    handled.countDown();
                }
            });
            CountDownLatch issued = new CountDownLatch(1);
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> app.execute(ignored ->
                    AsyncCompletionScope.runAndAwaitBeforeCommit(() -> {
                        app.messageScheduler().schedule(new io.fluxzero.sdk.scheduling.Schedule(
                                "expired", "schedule", java.time.Instant.EPOCH), false, Guarantee.STORED);
                        issued.countDown();
                    })));
            try {
                assertTrue(issued.await(5, TimeUnit.SECONDS));
                acknowledgement.complete(null);
                assertTrue(handled.await(5, TimeUnit.SECONDS));
                assertFalse(batch.isDone());
                nestedWrite.completeExceptionally(new IllegalStateException("nested write rejected"));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> batch.get(5, TimeUnit.SECONDS));
            } finally {
                acknowledgement.complete(null);
                nestedWrite.complete(null);
            }
        }
    }

    private static Fluxzero application(LocalClient client, Guarantee guarantee) {
        return DefaultFluxzero.builder().disableShutdownHook().makeApplicationInstance(false)
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, guarantee.name())))
                .build(client);
    }

    private static LocalClient client() {
        LocalClient client = spy(LocalClient.newInstance());
        doReturn(client).when(client).forNamespace(null);
        doReturn(spy(client.getSearchClient())).when(client).getSearchClient();
        doReturn(spy(client.getKeyValueClient())).when(client).getKeyValueClient();
        doReturn(spy(client.getSchedulingClient())).when(client).getSchedulingClient();
        for (MessageType type : new MessageType[]{MessageType.RESULT, MessageType.WEBRESPONSE, MessageType.EVENT}) {
            GatewayClient gateway = spy(client.getGatewayClient(type));
            doReturn(CompletableFuture.completedFuture(null)).when(gateway).append(any(), any(SerializedMessage[].class));
            doReturn(gateway).when(client).getGatewayClient(type, null);
        }
        return client;
    }
}
