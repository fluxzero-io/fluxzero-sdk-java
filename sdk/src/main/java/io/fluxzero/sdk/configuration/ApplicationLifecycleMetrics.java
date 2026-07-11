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
import io.fluxzero.sdk.common.SdkVersion;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.MetricsGateway;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.api.ApplicationLifecycleEvent.Phase.STARTED;
import static io.fluxzero.common.api.ApplicationLifecycleEvent.Phase.STOPPING;

@Slf4j
final class ApplicationLifecycleMetrics {
    static final Duration STOPPING_PUBLICATION_TIMEOUT = Duration.ofSeconds(1);

    private final MetricsGateway metricsGateway;
    private final Client client;
    private final Clock clock;
    private final Duration stoppingPublicationTimeout;
    private final String startupId = UUID.randomUUID().toString();
    private final long startedAt;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CompletableFuture<Void> startupPublication = new CompletableFuture<>();
    private volatile Thread startupThread;

    ApplicationLifecycleMetrics(MetricsGateway metricsGateway, Client client, Clock clock) {
        this(metricsGateway, client, clock, STOPPING_PUBLICATION_TIMEOUT);
    }

    ApplicationLifecycleMetrics(MetricsGateway metricsGateway, Client client, Clock clock,
                                Duration stoppingPublicationTimeout) {
        this.metricsGateway = metricsGateway;
        this.client = client;
        this.clock = clock;
        this.stoppingPublicationTimeout = stoppingPublicationTimeout;
        this.startedAt = clock.millis();
    }

    void start() {
        if (started.compareAndSet(false, true)) {
            startupThread = Thread.ofVirtual().name("fluxzero-application-start-metric").start(() -> {
                if (stopping.get()) {
                    startupPublication.cancel(false);
                    return;
                }
                try {
                    metricsGateway.publish(event(STARTED), Metadata.empty(), STORED).get();
                    startupPublication.complete(null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    startupPublication.completeExceptionally(e);
                } catch (Throwable e) {
                    startupPublication.completeExceptionally(e);
                    if (!stopping.get()) {
                        log.warn("Failed to publish Fluxzero application startup metric", e);
                    }
                }
            });
        }
    }

    void stop() {
        if (stopping.compareAndSet(false, true)) {
            if (startupPublication.isDone()
                && !startupPublication.isCompletedExceptionally()
                && !startupPublication.isCancelled()) {
                try {
                    metricsGateway.publish(event(STOPPING), Metadata.empty(), STORED)
                            .get(stoppingPublicationTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    log.debug("Could not publish Fluxzero application stopping metric before shutdown", e);
                }
            }
            Thread thread = startupThread;
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }
    }

    CompletableFuture<Void> startupPublication() {
        return startupPublication;
    }

    private ApplicationLifecycleEvent event(ApplicationLifecycleEvent.Phase phase) {
        return new ApplicationLifecycleEvent(
                phase, startupId, client.applicationId(), client.name(), client.id(), client.namespace(),
                SdkVersion.version().orElse(null), startedAt, clock.millis());
    }
}
