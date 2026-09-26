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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.TestTask;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULT_DELIVERY_GUARANTEE_PROPERTY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulingCompletionTest {
    @Test
    void initializationWaitsForStoredEvenWhenApplicationUsesNone() throws Exception {
        LocalClient client = client();
        SchedulingClient transport = client.getSchedulingClient();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        when(transport.schedule(eq(Guarantee.STORED), any(SerializedSchedule[].class))).thenReturn(acknowledgement);
        try (Fluxzero app = application(client)) {
            Schedule schedule = new Schedule("periodic", "id", Instant.now().plusSeconds(60));
            try (var task = new TestTask(() -> app.execute(ignored -> new SchedulingInterceptor()
                    .initializePeriodicSchedule(app.messageScheduler(), schedule, null)),
                                        () -> acknowledgement.complete(null))) {
                task.awaitBlockedIn(AsyncCompletionScope.class, "await", Duration.ofSeconds(2));
                verify(transport).schedule(eq(Guarantee.STORED), any(SerializedSchedule[].class));
                acknowledgement.complete(null);
                task.awaitCompletion(Duration.ofSeconds(2));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void caughtRescheduleFailureDoesNotPoisonBatchIncludingParentBoundWrites(boolean parentBound) throws Exception {
        LocalClient client = client();
        SchedulingClient transport = client.getSchedulingClient();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        when(transport.bindScheduleParents(anyList())).thenReturn(CompletableFuture.completedFuture(Map.of("parent", 1L)));
        when(transport.schedule(any(), any(SerializedSchedule[].class)))
                .thenAnswer(ignored -> AsyncCompletionScope.register(acknowledgement));
        when(transport.scheduleBoundToParents(any(), anyMap(), any(SerializedSchedule[].class)))
                .thenAnswer(ignored -> AsyncCompletionScope.register(acknowledgement));
        try (Fluxzero app = application(client)) {
            Metadata metadata = Metadata.of(Schedule.scheduleIdMetadataKey, "id");
            if (parentBound) {
                metadata = metadata.with(ScheduleParents.METADATA_KEY, new String[]{"parent"});
            }
            DeserializingMessage source = new DeserializingMessage(new Message("periodic", metadata), SCHEDULE, app.serializer());
            try (var task = new TestTask(() -> app.execute(ignored -> AsyncCompletionScope.runAndAwaitBeforeCommit(
                    () -> new SchedulingInterceptor().handleResult(Duration.ofMinutes(1), source, Instant.now(), null))),
                                        () -> acknowledgement.complete(null))) {
                task.awaitBlockedIn(AsyncCompletionScope.class, "await", Duration.ofSeconds(2));
                if (parentBound) {
                    verify(transport).scheduleBoundToParents(eq(Guarantee.STORED), anyMap(), any(SerializedSchedule[].class));
                } else {
                    verify(transport).schedule(eq(Guarantee.STORED), any(SerializedSchedule[].class));
                }
                acknowledgement.completeExceptionally(new IllegalStateException("schedule rejected"));
                task.awaitCompletion(Duration.ofSeconds(2));
            }
        }
    }

    @Test
    void periodicCancellationWaitsForSentAndPropagatesFailure() throws Exception {
        LocalClient client = client();
        SchedulingClient transport = client.getSchedulingClient();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        when(transport.cancelSchedule("id", Guarantee.SENT)).thenReturn(acknowledgement);
        try (Fluxzero app = application(client)) {
            DeserializingMessage source = new DeserializingMessage(new Message("periodic",
                    Metadata.of(Schedule.scheduleIdMetadataKey, "id")), SCHEDULE, app.serializer());
            try (var task = new TestTask(() -> app.execute(ignored -> assertThrows(SchedulerException.class,
                    () -> new SchedulingInterceptor().handleExceptionalResult(new CancelPeriodic(), source, Instant.now(), null))),
                                        () -> acknowledgement.complete(null))) {
                task.awaitBlockedIn(AsyncCompletionScope.class, "await", Duration.ofSeconds(2));
                verify(transport).cancelSchedule("id", Guarantee.SENT);
                acknowledgement.completeExceptionally(new IllegalStateException("cancel rejected"));
                task.awaitCompletion(Duration.ofSeconds(2));
            }
        }
    }

    private static LocalClient client() {
        LocalClient client = spy(LocalClient.newInstance());
        doReturn(client).when(client).forNamespace(null);
        doReturn(mock(SchedulingClient.class)).when(client).getSchedulingClient();
        return client;
    }

    private static Fluxzero application(LocalClient client) {
        return DefaultFluxzero.builder().disableShutdownHook().makeApplicationInstance(false)
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "NONE")))
                .build(client);
    }
}
