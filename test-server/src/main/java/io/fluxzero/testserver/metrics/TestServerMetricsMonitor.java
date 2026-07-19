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
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Lightweight observation hook for embedded test-server users.
 */
@Slf4j
public final class TestServerMetricsMonitor {
    private static final Map<UUID, BiConsumer<Object, Metadata>> listeners = new ConcurrentHashMap<>();

    private TestServerMetricsMonitor() {
    }

    public static Registration monitor(BiConsumer<Object, Metadata> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        UUID id = UUID.randomUUID();
        listeners.put(id, listener);
        return () -> listeners.remove(id);
    }

    static void record(Object event, Metadata metadata) {
        listeners.values().forEach(listener -> {
            try {
                listener.accept(event, metadata);
            } catch (Throwable e) {
                log.warn("Failed to notify test-server metrics listener", e);
            }
        });
    }
}
