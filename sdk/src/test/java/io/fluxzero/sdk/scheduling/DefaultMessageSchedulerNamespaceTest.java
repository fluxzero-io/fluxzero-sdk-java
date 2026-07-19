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
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMessageSchedulerNamespaceTest {

    @Test
    void customNamespaceSchedulesLocalDelivery() {
        Client applicationClient = mock(Client.class);
        Client customClient = mock(Client.class);
        SchedulingClient schedulingClient = mock(SchedulingClient.class);
        HandlerRegistry localHandlers = mock(HandlerRegistry.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        when(customClient.namespace()).thenReturn("tenant");
        when(customClient.forNamespace(null)).thenReturn(applicationClient);
        when(customClient.getSchedulingClient()).thenReturn(schedulingClient);
        when(localHandlers.hasLocalHandlers()).thenReturn(true);
        when(taskScheduler.clock()).thenReturn(Clock.systemUTC());
        when(schedulingClient.schedule(any(Guarantee.class), any(SerializedSchedule[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        DefaultMessageScheduler scheduler = new DefaultMessageScheduler(
                customClient, new JacksonSerializer(), DispatchInterceptor.noOp, DispatchInterceptor.noOp,
                taskScheduler, localHandlers);

        scheduler.schedule(new Schedule("payload", "schedule", Instant.now().plusSeconds(60)),
                           false, Guarantee.STORED).join();

        verify(taskScheduler).schedule(any(Instant.class), any());
    }
}
