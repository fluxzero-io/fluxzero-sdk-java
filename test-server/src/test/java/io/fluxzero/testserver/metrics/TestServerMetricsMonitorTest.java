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

package io.fluxzero.testserver.metrics;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.tracking.MessageStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestServerMetricsMonitorTest {

    @Test
    void observesMetricsUntilRegistrationIsCancelled() {
        MessageStore store = mock(MessageStore.class);
        when(store.append(any(SerializedMessage[].class))).thenReturn(CompletableFuture.completedFuture(null));
        var worker = Executors.newSingleThreadExecutor();
        var metricsLog = new DefaultMetricsLog(store, worker);
        var observedEvent = new AtomicReference<>();
        var observedMetadata = new AtomicReference<Metadata>();
        var observationCount = new AtomicInteger();
        Registration registration = TestServerMetricsMonitor.monitor((event, metadata) -> {
            observedEvent.set(event);
            observedMetadata.set(metadata);
            observationCount.incrementAndGet();
        });
        try {
            metricsLog.registerMetrics("connected", Metadata.of("clientId", "client-1")).join();

            assertEquals("connected", observedEvent.get());
            assertEquals("client-1", observedMetadata.get().get("clientId"));
            assertEquals("FluxzeroTestServer", observedMetadata.get().get("$applicationId"));

            registration.cancel();
            metricsLog.registerMetrics("ignored").join();
            assertEquals(1, observationCount.get());
        } finally {
            registration.cancel();
            worker.shutdownNow();
        }
    }
}
