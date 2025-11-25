/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.configuration.client;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.application.DefaultPropertySource;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;
import io.fluxzero.sdk.persisting.keyvalue.client.InMemoryKeyValueStore;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.persisting.search.client.CollectionMessageStore;
import io.fluxzero.sdk.persisting.search.client.InMemorySearchStore;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.scheduling.client.LocalSchedulingClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.client.LocalTrackingClient;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.function.Function;

import static io.fluxzero.sdk.common.ClientUtils.memoize;

/**
 * An in-memory {@link Client} implementation used for local development, testing, or isolated environments where no
 * connection to the Fluxzero Runtime is required.
 * <p>
 * This client simulates all major subsystems — including event storage, scheduling, key-value access, and document
 * search — using in-memory data structures with optional message expiration. It is ideal for use cases such as:
 * <ul>
 *   <li>Unit testing message handlers</li>
 *   <li>Running local development instances without Fluxzero Runtime dependencies</li>
 *   <li>Offline simulations and demos</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * The default expiration time for messages and scheduled tasks is 2 minutes, but a custom duration can be specified:
 * <pre>{@code
 * LocalClient client = LocalClient.newInstance(Duration.ofMinutes(5));
 * }</pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Simulates gateway clients for all {@link MessageType}s</li>
 *   <li>Provides in-memory implementations of event store, scheduling, key-value, and search clients</li>
 *   <li>Uses the {@code FLUXZERO_TASK_ID} environment property or the JVM process name as the client ID</li>
 *   <li>Reads {@code FLUXZERO_APPLICATION_NAME} and {@code FLUXZERO_APPLICATION_ID} from environment or system properties</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * Typical usage involves passing this client into the {@code FluxzeroBuilder}:
 * <pre>{@code
 * Fluxzero fluxzero = DefaultFluxzero.builder().build(LocalClient.newInstance());
 * }</pre>
 */
public class LocalClient extends AbstractClient {

    @Getter
    private final Duration messageExpiration;
    private final LocalEventStoreClient eventStore;
    private final LocalSchedulingClient scheduleStore;
    @Getter @Accessors(fluent = true)
    private final String namespace;
    @Getter(AccessLevel.PRIVATE)
    private final LocalClient defaultClient;

    private final Function<String, Client> clientSupplier = memoize(
            namespace -> {
                var defaultClient = getDefaultClient();
                if (defaultClient != null) {
                    if (namespace == null) {
                        return defaultClient;
                    }
                    return defaultClient.forNamespace(namespace);
                }
                if (namespace == null) {
                    return this;
                }
                return new LocalClient(getMessageExpiration(), namespace, this);
            });

    @Getter(lazy = true)
    @Accessors(fluent = true)
    private final String id = DefaultPropertySource.getInstance().get("FLUXZERO_TASK_ID",
                    DefaultPropertySource.getInstance().get("FLUX_TASK_ID",
                            ManagementFactory.getRuntimeMXBean().getName()));

    public static LocalClient newInstance() {
        return new LocalClient(ApplicationProperties.mapProperty(
                "FLUXZERO_LOG_RETENTION", Duration::parse, () -> Duration.ofMinutes(2)));
    }

    public static LocalClient newInstance(Duration messageExpiration) {
        return new LocalClient(messageExpiration);
    }

    protected LocalClient(Duration messageExpiration) {
        this(messageExpiration, null, null);
    }

    protected LocalClient(Duration messageExpiration, String namespace, LocalClient defaultClient) {
        this.messageExpiration = messageExpiration;
        this.eventStore = new LocalEventStoreClient(messageExpiration);
        this.scheduleStore = new LocalSchedulingClient(messageExpiration);
        this.namespace = namespace;
        this.defaultClient = defaultClient;
    }

    @Override
    public String name() {
        return DefaultPropertySource.getInstance().get("FLUXZERO_APPLICATION_NAME",
                        DefaultPropertySource.getInstance().get("FLUX_APPLICATION_NAME", "inMemory"));
    }

    @Override
    public String applicationId() {
        return DefaultPropertySource.getInstance().get("FLUXZERO_APPLICATION_ID",
                        DefaultPropertySource.getInstance().get("FLUX_APPLICATION_ID"));
    }

    @Override
    public Client forNamespace(String namespace) {
        return clientSupplier.apply(namespace);
    }

    @Override
    protected GatewayClient createGatewayClient(MessageType messageType, String topic) {
        return switch (messageType) {
            case NOTIFICATION, EVENT -> eventStore;
            case SCHEDULE -> scheduleStore;
            case DOCUMENT -> new LocalTrackingClient(new CollectionMessageStore((InMemorySearchStore) getSearchClient(),
                                                                                topic), MessageType.DOCUMENT, topic);
            default -> new LocalTrackingClient(messageType, topic, messageExpiration);
        };
    }

    @Override
    protected TrackingClient createTrackingClient(MessageType messageType, String topic) {
        return (TrackingClient) getGatewayClient(messageType, topic);
    }

    @Override
    protected EventStoreClient createEventStoreClient() {
        return (EventStoreClient) getTrackingClient(MessageType.EVENT);
    }

    @Override
    protected SchedulingClient createSchedulingClient() {
        return (SchedulingClient) getTrackingClient(MessageType.SCHEDULE);
    }

    @Override
    protected KeyValueClient createKeyValueClient() {
        return new InMemoryKeyValueStore();
    }

    @Override
    protected InMemorySearchStore createSearchClient() {
        return new InMemorySearchStore(messageExpiration);
    }
}
