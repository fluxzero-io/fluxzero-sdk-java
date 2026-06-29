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

package io.fluxzero.sdk.tracking.metrics;

import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;

class TrackerMonitorTest {
    @Test
    void trackerMetricsPublished() {
        TestFixture.createAsync(new CustomConsumerHandler()).whenEvent("test")
                .<ProcessBatchEvent>expectMetric(
                        e -> e.getConsumer().equals("custom-consumer") && e.getBatchSize() == 1)
                .expectMetrics(HandleMessageEvent.class);
    }

    @Test
    void blockHandlerMetrics() {
        TestFixture.createAsync(new HandlerMetricsBlockedHandler())
                .whenEvent("test").expectNoMetricsLike(HandleMessageEvent.class);
    }

    @Test
    void blockBatchMetrics() {
        TestFixture.createAsync(new BatchMetricsBlockedHandler()).whenEvent("test")
                .expectNoMetricsLike(HandleMessageEvent.class)
                .expectNoMetricsLike(ProcessBatchEvent.class);
    }

    @Test
    void blockDispatchMetrics() {
        TestFixture.createAsync(new DispatchMetricsBlockedHandler()).whenEvent("test")
                .expectNoMetricsLike(HandleMessageEvent.class)
                .expectNoMetricsLike(ProcessBatchEvent.class);
    }

    @Consumer(name = "custom-consumer")
    static class CustomConsumerHandler {
        @HandleEvent
        void handle(String ignored) {
        }
    }

    @Consumer(name = "MetricsBlocked-consumer", handlerInterceptors = DisableMetrics.class)
    static class HandlerMetricsBlockedHandler {
        @HandleEvent
        void handle(String ignored) {
        }
    }

    @Consumer(name = "MetricsBlocked-consumer", batchInterceptors = DisableMetrics.class)
    static class BatchMetricsBlockedHandler {
        @HandleEvent
        void handle(String ignored) {
        }
    }

    @Consumer(name = "MetricsBlocked-consumer", dispatchInterceptors = DisableMetrics.class)
    static class DispatchMetricsBlockedHandler {
        @HandleEvent
        void handle(String ignored) {
        }
    }
}
