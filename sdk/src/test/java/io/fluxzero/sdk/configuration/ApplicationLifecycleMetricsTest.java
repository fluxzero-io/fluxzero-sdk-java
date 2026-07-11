/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.api.ApplicationLifecycleEvent;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.MetricsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.api.ApplicationLifecycleEvent.Phase.STARTED;
import static io.fluxzero.common.api.ApplicationLifecycleEvent.Phase.STOPPING;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationLifecycleMetricsTest {
    private final MetricsGateway metricsGateway = mock(MetricsGateway.class);
    private final Client client = mock(Client.class);
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1234L), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        when(client.applicationId()).thenReturn("application");
        when(client.name()).thenReturn("client");
        when(client.id()).thenReturn("client-id");
        when(client.namespace()).thenReturn("namespace");
    }

    @Test
    void publishesOneConnectedStartupAndMatchingStoppingMetric() throws Exception {
        when(metricsGateway.publish(any(), eq(Metadata.empty()), eq(STORED)))
                .thenReturn(completedFuture(null));
        ApplicationLifecycleMetrics subject =
                new ApplicationLifecycleMetrics(metricsGateway, client, clock, Duration.ofMillis(50));

        subject.start();
        subject.start();
        subject.startupPublication().get(1, TimeUnit.SECONDS);
        subject.stop();
        subject.stop();

        ArgumentCaptor<ApplicationLifecycleEvent> eventCaptor =
                ArgumentCaptor.forClass(ApplicationLifecycleEvent.class);
        verify(metricsGateway, times(2)).publish(eventCaptor.capture(), eq(Metadata.empty()), eq(STORED));
        List<ApplicationLifecycleEvent> events = eventCaptor.getAllValues();
        assertEquals(List.of(STARTED, STOPPING), events.stream().map(ApplicationLifecycleEvent::getPhase).toList());
        assertEquals(events.getFirst().getStartupId(), events.getLast().getStartupId());
        assertEquals("application", events.getFirst().getApplicationId());
        assertEquals("client", events.getFirst().getClientName());
        assertEquals("client-id", events.getFirst().getClientId());
        assertEquals("namespace", events.getFirst().getNamespace());
        assertEquals(1234L, events.getFirst().getStartedAt());
        assertEquals(1234L, events.getLast().getStartedAt());
    }

    @Test
    void startupDoesNotBlockAndStoppingDoesNotReconnectWhenStartupIsPending() throws Exception {
        CompletableFuture<Void> pendingPublication = new CompletableFuture<>();
        when(metricsGateway.publish(any(), eq(Metadata.empty()), eq(STORED))).thenReturn(pendingPublication);
        ApplicationLifecycleMetrics subject =
                new ApplicationLifecycleMetrics(metricsGateway, client, clock, Duration.ofMillis(20));

        assertDoesNotThrow(subject::start);
        verify(metricsGateway, timeout(1_000)).publish(any(), eq(Metadata.empty()), eq(STORED));
        assertFalse(subject.startupPublication().isDone());

        subject.stop();

        verify(metricsGateway).publish(any(), eq(Metadata.empty()), eq(STORED));
        assertThrows(ExecutionException.class,
                     () -> subject.startupPublication().get(1, TimeUnit.SECONDS));
    }

    @Test
    void stoppingPublicationIsBounded() throws Exception {
        CompletableFuture<Void> pendingStoppingPublication = new CompletableFuture<>();
        when(metricsGateway.publish(any(), eq(Metadata.empty()), eq(STORED)))
                .thenReturn(completedFuture(null))
                .thenReturn(pendingStoppingPublication);
        ApplicationLifecycleMetrics subject =
                new ApplicationLifecycleMetrics(metricsGateway, client, clock, Duration.ofMillis(20));
        subject.start();
        subject.startupPublication().get(1, TimeUnit.SECONDS);

        assertTimeout(Duration.ofSeconds(1), subject::stop);

        verify(metricsGateway, times(2)).publish(any(), eq(Metadata.empty()), eq(STORED));
    }
}
