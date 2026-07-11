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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.ApplicationLifecycleEvent;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.LocalClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.fluxzero.common.api.ApplicationLifecycleEvent.Phase.STARTED;
import static io.fluxzero.common.api.ApplicationLifecycleEvent.Phase.STOPPING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationLifecycleMetricsIntegrationTest {

    @Test
    void canDisableLifecycleMetricsExplicitly() {
        LocalClient client = LocalClient.newInstance(null);
        List<SerializedMessage> metrics = new CopyOnWriteArrayList<>();
        client.monitorDispatch((type, topic, namespace, messages) -> metrics.addAll(messages), MessageType.METRICS);

        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .disableTrackingMetrics()
                .disableCacheEvictionMetrics()
                .disableApplicationLifecycleMetrics()
                .disableShutdownHook()
                .disableKeepalive()
                .build(client);
        fluxzero.close(true);

        assertTrue(metrics.isEmpty());
    }

    @Test
    void publishesOneLifecyclePairForEveryRealFluxzeroInstance() throws Exception {
        LocalClient client = LocalClient.newInstance(null);
        List<SerializedMessage> metrics = new CopyOnWriteArrayList<>();
        client.monitorDispatch((type, topic, namespace, messages) -> metrics.addAll(messages), MessageType.METRICS);
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableAutomaticTracking()
                .disableTrackingMetrics()
                .disableCacheEvictionMetrics()
                .disableShutdownHook()
                .disableKeepalive()
                .build(client);

        awaitSize(metrics, 1);
        fluxzero.close(true);
        awaitSize(metrics, 2);

        JacksonSerializer serializer = new JacksonSerializer();
        List<ApplicationLifecycleEvent> events = metrics.stream()
                .map(serializer::<ApplicationLifecycleEvent>deserialize).toList();
        assertEquals(List.of(STARTED, STOPPING), events.stream().map(ApplicationLifecycleEvent::getPhase).toList());
        assertEquals(events.getFirst().getStartupId(), events.getLast().getStartupId());
        assertEquals(client.name(), events.getFirst().getClientName());
        assertEquals(client.id(), events.getFirst().getClientId());
    }

    private static void awaitSize(List<?> values, int expectedSize) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (values.size() < expectedSize && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(values.size() >= expectedSize,
                   () -> "Expected at least " + expectedSize + " lifecycle metrics, got " + values.size());
    }
}
