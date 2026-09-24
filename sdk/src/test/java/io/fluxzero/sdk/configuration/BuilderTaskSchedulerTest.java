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
package io.fluxzero.sdk.configuration;

import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.sdk.configuration.client.LocalClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuilderTaskSchedulerTest {
    @Test
    void replacingSchedulerDoesNotStartAnAbandonedDefaultScheduler() {
        try (var defaults = mockConstruction(InMemoryTaskScheduler.class)) {
            var builder = DefaultFluxzero.builder();
            assertTrue(defaults.constructed().isEmpty(), "An unused builder must not start scheduler resources");
            var replacement = mock(TaskScheduler.class);
            builder.replaceTaskScheduler(clock -> replacement);
            assertSame(replacement, builder.taskScheduler());
            assertTrue(defaults.constructed().isEmpty());
        }
    }

    @Test
    void explicitDefaultAccessRetainsTheSameSchedulerAndApplicationClosesIt() {
        try (var defaults = mockConstruction(InMemoryTaskScheduler.class)) {
            var builder = DefaultFluxzero.builder();
            var scheduler = builder.taskScheduler();
            assertSame(scheduler, builder.taskScheduler());
            assertEquals(1, defaults.constructed().size());
            try (var app = builder.disableShutdownHook().disableKeepalive().disableAutomaticTracking()
                    .disableApplicationLifecycleMetrics().build(LocalClient.newInstance())) {
                assertSame(scheduler, app.taskScheduler());
                verify(scheduler, never()).shutdown();
            }
            verify(scheduler).shutdown();
        }
    }

    @Test
    void buildInitializesTheDefaultWhenNoSchedulerWasRequested() {
        try (var defaults = mockConstruction(InMemoryTaskScheduler.class)) {
            try (var app = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive()
                    .disableAutomaticTracking().disableApplicationLifecycleMetrics().build(LocalClient.newInstance())) {
                assertEquals(1, defaults.constructed().size());
                assertSame(defaults.constructed().getFirst(), app.taskScheduler());
            }
            verify(defaults.constructed().getFirst()).shutdown();
        }
    }
}
