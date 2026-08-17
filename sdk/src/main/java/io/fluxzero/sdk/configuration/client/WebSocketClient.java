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
 *
 */

package io.fluxzero.sdk.configuration.client;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.WebSocketEventStoreClient;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.persisting.keyvalue.client.WebsocketKeyValueClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.persisting.search.client.WebSocketSearchClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.publishing.client.WebsocketGatewayClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.scheduling.client.WebsocketSchedulingClient;
import io.fluxzero.sdk.tracking.client.CachingTrackingClient;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.client.WebsocketTrackingClient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.LZ4;
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.ZSTD;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.BINARY;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.BINARY_V2;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.CBOR;
import static io.fluxzero.common.websocket.WebSocketTransportFormat.JSON;
import static io.fluxzero.sdk.common.websocket.ServiceUrlBuilder.eventSourcingUrl;
import static io.fluxzero.sdk.common.websocket.ServiceUrlBuilder.gatewayUrl;
import static io.fluxzero.sdk.common.websocket.ServiceUrlBuilder.keyValueUrl;
import static io.fluxzero.sdk.common.websocket.ServiceUrlBuilder.schedulingUrl;
import static io.fluxzero.sdk.common.websocket.ServiceUrlBuilder.searchUrl;
import static io.fluxzero.sdk.common.websocket.ServiceUrlBuilder.trackingUrl;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getFirstAvailableProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static java.util.stream.Collectors.toMap;

/**
 * A {@link Client} implementation that connects to the Fluxzero Runtime using WebSocket connections.
 * <p>
 * This client enables full integration with the Fluxzero Runtime by delegating all gateway, tracking, and subsystem
 * communication to remote endpoints defined by the {@link ClientConfig}. It is typically used in production and testing
 * environments where communication with the Fluxzero Runtime is required.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * WebSocketClient client = WebSocketClient.newInstance(
 *     WebSocketClient.ClientConfig.builder()
 *         .runtimeBaseUrl("wss://my.fluxzero.host")
 *         .name("my-service")
 *         .build());
 * Fluxzero fluxzero = Fluxzero.builder().build(client);
 * }</pre>
 *
 * @see io.fluxzero.sdk.configuration.DefaultFluxzero#builder()
 * @see Client
 * @see LocalClient for an in-memory alternative
 */
public class WebSocketClient extends AbstractClient {

    @Getter
    private final ClientConfig clientConfig;

    @Getter(AccessLevel.PRIVATE)
    private final WebSocketClient applicationClient;

    protected WebSocketClient(ClientConfig clientConfig, WebSocketClient applicationClient) {
        this.clientConfig = Objects.requireNonNull(clientConfig, "clientConfig");
        this.clientConfig.validateRuntimeWebSocketCapacity();
        this.applicationClient = applicationClient;
    }

    public static WebSocketClient newInstance(ClientConfig clientConfig) {
        return new WebSocketClient(clientConfig, null);
    }

    @Override
    public String name() {
        return clientConfig.getName();
    }

    @Override
    public String id() {
        return clientConfig.getId();
    }

    @Override
    public String applicationId() {
        return clientConfig.getApplicationId();
    }

    @Override
    public String namespace() {
        return clientConfig.getNamespace();
    }

    @Override
    protected Client createForNamespace(String namespace) {
        if (Objects.equals(namespace(), namespace)) {
            return this;
        }
        var applicationClient = getApplicationClient();
        if (applicationClient != null) {
            return namespace == null ? applicationClient : applicationClient.forNamespace(namespace);
        }
        if (namespace == null) {
            return this;
        }
        return registerNamespaceClient(
                new WebSocketClient(getClientConfig().toBuilder().namespace(namespace).build(), this));
    }

    @Override
    protected GatewayClient createGatewayClient(MessageType messageType, String topic) {
        return new WebsocketGatewayClient(gatewayUrl(messageType, topic, clientConfig), this, messageType, topic);
    }

    @Override
    protected TrackingClient createTrackingClient(MessageType messageType, String topic) {
        TrackingClientConfig trackingConfig = clientConfig.getTrackingConfigs().get(messageType);
        WebsocketTrackingClient wsClient =
                new WebsocketTrackingClient(trackingUrl(messageType, topic, clientConfig), this, messageType, topic);
        return trackingConfig.getCacheSize() > 0
                ? new CachingTrackingClient(wsClient, trackingConfig.getCacheSize()) : wsClient;
    }

    @Override
    protected EventStoreClient createEventStoreClient() {
        return new WebSocketEventStoreClient(eventSourcingUrl(clientConfig), this);
    }

    @Override
    protected SchedulingClient createSchedulingClient() {
        return new WebsocketSchedulingClient(schedulingUrl(clientConfig), this);
    }

    @Override
    protected KeyValueClient createKeyValueClient() {
        return new WebsocketKeyValueClient(keyValueUrl(clientConfig), this);
    }

    @Override
    protected SearchClient createSearchClient() {
        return new WebSocketSearchClient(searchUrl(clientConfig), this);
    }

    /**
     * Configuration class for creating a {@link WebSocketClient}.
     * <p>
     * This configuration defines identifiers, service URLs, compression settings, and session allocations for various
     * message types. It is immutable and can be modified fluently via its {@code #toBuilder()} method.
     */
    @Value
    @Builder(toBuilder = true)
    public static class ClientConfig {
        static final int DEFAULT_MAX_IN_FLIGHT_WEBSOCKET_BYTES = 16 * 1024 * 1024;
        static final String MAX_IN_FLIGHT_WEBSOCKET_BYTES_PROPERTY = "FLUXZERO_MAX_IN_FLIGHT_WEBSOCKET_BYTES";
        static final int DEFAULT_MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES = 3;
        static final int DEFAULT_MAX_CONCURRENT_RUNTIME_RESULT_COMPLETIONS = 8;
        static final int DEFAULT_MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES = 128;
        static final long DEFAULT_MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES = 64L * 1024 * 1024;
        static final String MAX_CONCURRENT_RUNTIME_MESSAGES_PROPERTY =
                "fluxzero.runtime.ingress.maxConcurrency";
        static final String MAX_RETAINED_RUNTIME_MESSAGES_PROPERTY =
                "fluxzero.runtime.ingress.maxRetainedMessages";
        static final String MAX_RETAINED_RUNTIME_BYTES_PROPERTY =
                "fluxzero.runtime.ingress.maxRetainedBytes";
        static final String MAX_CONCURRENT_RUNTIME_RESULT_COMPLETIONS_PROPERTY =
                "fluxzero.runtime.ingress.maxCompletionConcurrency";
        static final String RUNTIME_INGRESS_STALL_CLOSE_TIMEOUT_PROPERTY =
                "fluxzero.runtime.ingress.stallCloseTimeout";
        static final String MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_ALIAS =
                "fluxzero.websocket.runtime.maxConcurrency";
        static final String MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES_ALIAS =
                "fluxzero.websocket.runtime.maxRetainedMessages";
        static final String MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES_ALIAS =
                "fluxzero.websocket.runtime.maxRetainedBytes";
        static final String MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY =
                "FLUXZERO_WEBSOCKET_RUNTIME_MAX_CONCURRENCY";
        static final String MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY =
                "FLUXZERO_WEBSOCKET_RUNTIME_MAX_RETAINED_MESSAGES";
        static final String MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES_PROPERTY =
                "FLUXZERO_WEBSOCKET_RUNTIME_MAX_RETAINED_BYTES";

        /**
         * The base URL for all Fluxzero Runtime services, typically starting with {@code wss://}. Defaults to property
         * {@code FLUXZERO_BASE_URL}.
         */
        @Default
        @NonNull
        String runtimeBaseUrl = getFirstAvailableProperty("FLUXZERO_BASE_URL", "FLUX_BASE_URL");

        /**
         * The name of the application. Defaults to property {@code FLUXZERO_APPLICATION_NAME}.
         */
        @Default
        @NonNull
        String name = getFirstAvailableProperty("FLUXZERO_APPLICATION_NAME", "FLUX_APPLICATION_NAME");

        /**
         * The application identifier. May be {@code null} if not explicitly configured. Defaults to property
         * {@code FLUXZERO_APPLICATION_ID}.
         */
        @Default
        String applicationId = getFirstAvailableProperty("FLUXZERO_APPLICATION_ID", "FLUX_APPLICATION_ID");

        /**
         * A unique ID for the client instance. Defaults to {@code FLUXZERO_TASK_ID} or a randomly generated UUID.
         */
        @NonNull
        @Default
        String id = Optional.ofNullable(getFirstAvailableProperty(
                "FLUXZERO_TASK_ID", "FLUX_TASK_ID")).orElseGet(UUID.randomUUID()::toString);

        /**
         * Ordered list of compression algorithms the client supports for websocket communication, with the preferred
         * algorithm first. Should not be empty.
         */
        @Default
        List<CompressionAlgorithm> supportedCompressionAlgorithms = List.of(ZSTD, LZ4);

        /**
         * Ordered list of websocket transport formats the client supports, with the preferred format first.
         * <p>
         * {@link WebSocketTransportFormat#CBOR} keeps {@code byte[]} payloads as native binary values.
         * {@link WebSocketTransportFormat#JSON} remains the compatibility fallback for older runtimes.
         */
        @Default
        List<WebSocketTransportFormat> supportedTransportFormats = List.of(BINARY_V2, BINARY, CBOR, JSON);

        /**
         * Maximum number of encoded websocket bytes that may be in-flight per client before senders apply backpressure.
         * Defaults to {@code FLUXZERO_MAX_IN_FLIGHT_WEBSOCKET_BYTES}, or 16 MiB when unset.
         */
        @Default
        int maxInFlightWebSocketBytes = getIntegerProperty(MAX_IN_FLIGHT_WEBSOCKET_BYTES_PROPERTY,
                                                           DEFAULT_MAX_IN_FLIGHT_WEBSOCKET_BYTES);

        /**
         * Maximum number of complete SDK runtime messages decoded or waiting for bounded result-dispatcher admission
         * concurrently per WebSocket session.
         * Defaults to {@code fluxzero.runtime.ingress.maxConcurrency}, its legacy WebSocket aliases, or {@code 3}
         * when unset. This limit does not serialize result completions; configure
         * {@link #maxConcurrentRuntimeResultCompletions} separately for that.
         */
        @Default
        int maxConcurrentRuntimeWebSocketMessages = firstIntegerProperty(
                DEFAULT_MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES, MAX_CONCURRENT_RUNTIME_MESSAGES_PROPERTY,
                MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_ALIAS,
                MAX_CONCURRENT_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY);

        /**
         * Maximum number of SDK runtime messages retained per WebSocket session across fragment assembly, compressed
         * pending work, decode/admission and admitted functional processing. Defaults to
         * {@code fluxzero.runtime.ingress.maxRetainedMessages}, its legacy WebSocket aliases, or {@code 128} when
         * unset.
         */
        @Default
        int maxRetainedRuntimeWebSocketMessages = firstIntegerProperty(
                DEFAULT_MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES, MAX_RETAINED_RUNTIME_MESSAGES_PROPERTY,
                MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES_ALIAS,
                MAX_RETAINED_RUNTIME_WEBSOCKET_MESSAGES_PROPERTY);

        /**
         * Maximum compressed wire bytes retained by the SDK runtime-data dispatcher per WebSocket session. A single
         * larger message may proceed while it is the only retained message. Defaults to
         * {@code fluxzero.runtime.ingress.maxRetainedBytes}, its legacy WebSocket aliases, or 64 MiB when unset.
         */
        @Default
        long maxRetainedRuntimeWebSocketBytes = firstLongProperty(
                DEFAULT_MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES, MAX_RETAINED_RUNTIME_BYTES_PROPERTY,
                MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES_ALIAS,
                MAX_RETAINED_RUNTIME_WEBSOCKET_BYTES_PROPERTY);

        /**
         * Maximum number of runtime request results whose SDK completion logic and synchronous customer future
         * continuations may run concurrently per client. Large result batches are submitted incrementally and share
         * this bound with individual responses. The existing worker policy uses virtual threads on Java 25 and newer
         * and a lazily populated fixed platform-thread pool on Java 21 through 24. Defaults to
         * {@code fluxzero.runtime.ingress.maxCompletionConcurrency}, or {@code 8} when unset.
         */
        @Default
        int maxConcurrentRuntimeResultCompletions = firstIntegerProperty(
                DEFAULT_MAX_CONCURRENT_RUNTIME_RESULT_COMPLETIONS,
                MAX_CONCURRENT_RUNTIME_RESULT_COMPLETIONS_PROPERTY);

        /**
         * Optional duration without functional runtime-ingress completion after which a locally stalled session is
         * closed. {@link Duration#ZERO} (the default) disables stall-triggered close; local backpressure itself never
         * closes a healthy session. The property value uses ISO-8601 duration syntax, for example {@code PT30S}.
         */
        @Default
        Duration runtimeIngressStallCloseTimeout = firstDurationProperty(
                Duration.ZERO, RUNTIME_INGRESS_STALL_CLOSE_TIMEOUT_PROPERTY);

        /**
         * Maximum payload bytes per physical WebSocket binary frame. Larger logical Fluxzero messages are sent with
         * native WebSocket continuation frames while preserving per-session ordering.
         */
        @Default
        int maxWebSocketFragmentBytes = WebsocketSession.DEFAULT_MAX_BINARY_FRAGMENT_BYTES;

        /**
         * Maximum time a websocket send operation may remain incomplete before the session is considered broken.
         * A non-positive value disables send timeouts.
         */
        @Default
        Duration webSocketSendTimeout = Duration.ofSeconds(30);

        /**
         * Returns the most preferred compression algorithm supported by the client.
         */
        @NonNull
        public CompressionAlgorithm getPreferredCompressionAlgorithm() {
            return supportedCompressionAlgorithms.getFirst();
        }

        /**
         * Number of WebSocket sessions allocated for the event sourcing subsystem. Defaults to {@code 2}.
         */
        @Default
        int eventSourcingSessions = 2;

        /**
         * Number of WebSocket sessions allocated for the key-value store subsystem. Defaults to {@code 2}.
         */
        @Default
        int keyValueSessions = 2;

        /**
         * Number of WebSocket sessions allocated for the search subsystem. Defaults to {@code 2}.
         */
        @Default
        int searchSessions = 2;

        /**
         * Map defining how many WebSocket gateway sessions to allocate per {@link MessageType}. Defaults to
         * {@link #defaultGatewaySessions()}.
         */
        @Default
        Map<MessageType, Integer> gatewaySessions = defaultGatewaySessions();

        /**
         * Configuration map for tracking clients per {@link MessageType}. Defaults to
         * {@link #defaultTrackingSessions()}.
         */
        @Default
        Map<MessageType, TrackingClientConfig> trackingConfigs = defaultTrackingSessions();

        /**
         * How long to wait for a ping response before timing out. Defaults to {@code 15 seconds}.
         */
        @Default
        Duration pingTimeout = Duration.ofSeconds(15);

        /**
         * The delay between automatic ping messages. Defaults to {@code 10 seconds}.
         */
        @Default
        Duration pingDelay = Duration.ofSeconds(10);

        /**
         * How long a websocket connection attempt may take before it times out. Defaults to {@code 10 seconds}.
         */
        @Default
        Duration connectionTimeout = Duration.ofSeconds(10);

        /**
         * Whether to disable sending metrics from this client.
         */
        boolean disableMetrics;

        /**
         * Optional project identifier. If set, it will be included in all communication with the Runtime.
         * <p>
         * If not set, the namespace configured for the application will be used.
         */
        @Default
        String namespace = getFirstAvailableProperty("FLUXZERO_NAMESPACE", "FLUXZERO_PROJECT_ID", "FLUX_PROJECT_ID");

        /**
         * Optional type filter that restricts the types of messages tracked by this client.
         */
        String typeFilter;

        /**
         * Returns a new {@code ClientConfig} with a modified gateway session count for the specified message type.
         */
        public ClientConfig withGatewaySessions(MessageType messageType, int count) {
            HashMap<MessageType, Integer> config = new HashMap<>(gatewaySessions);
            config.put(messageType, count);
            return toBuilder().gatewaySessions(config).build();
        }

        /**
         * Returns a new {@code ClientConfig} with a modified tracking config for the specified message type.
         */
        public ClientConfig withTrackingConfig(MessageType messageType, TrackingClientConfig trackingConfig) {
            HashMap<MessageType, TrackingClientConfig> config = new HashMap<>(trackingConfigs);
            config.put(messageType, trackingConfig);
            return toBuilder().trackingConfigs(config).build();
        }

        private void validateRuntimeWebSocketCapacity() {
            if (maxConcurrentRuntimeWebSocketMessages < 1) {
                throw new IllegalArgumentException("maxConcurrentRuntimeWebSocketMessages must be at least 1");
            }
            if (maxRetainedRuntimeWebSocketMessages < maxConcurrentRuntimeWebSocketMessages) {
                throw new IllegalArgumentException(
                        "maxRetainedRuntimeWebSocketMessages must be at least maxConcurrentRuntimeWebSocketMessages");
            }
            if (maxRetainedRuntimeWebSocketBytes < 1) {
                throw new IllegalArgumentException("maxRetainedRuntimeWebSocketBytes must be positive");
            }
            if (maxConcurrentRuntimeResultCompletions < 1) {
                throw new IllegalArgumentException("maxConcurrentRuntimeResultCompletions must be at least 1");
            }
            if (runtimeIngressStallCloseTimeout == null || runtimeIngressStallCloseTimeout.isNegative()) {
                throw new IllegalArgumentException("runtimeIngressStallCloseTimeout must not be negative");
            }
        }

        private static int firstIntegerProperty(int defaultValue, String... names) {
            String value = getFirstAvailableProperty(names);
            return value == null ? defaultValue : Integer.parseInt(value.trim());
        }

        private static long firstLongProperty(long defaultValue, String... names) {
            String value = getFirstAvailableProperty(names);
            return value == null ? defaultValue : Long.parseLong(value.trim());
        }

        private static Duration firstDurationProperty(Duration defaultValue, String... names) {
            String value = getFirstAvailableProperty(names);
            return value == null ? defaultValue : Duration.parse(value.trim());
        }

        private static Map<MessageType, Integer> defaultGatewaySessions() {
            return Arrays.stream(MessageType.values()).collect(toMap(Function.identity(), t -> 1));
        }

        private static Map<MessageType, TrackingClientConfig> defaultTrackingSessions() {
            return Arrays.stream(MessageType.values()).collect(toMap(Function.identity(), t -> t == MessageType.RESULT
                    ? TrackingClientConfig.builder().cacheSize(0).build() : TrackingClientConfig.builder().build()));
        }
    }

    /**
     * Configuration for a tracking client assigned to a specific {@link MessageType}.
     * <p>
     * This configuration determines how many WebSocket tracking sessions to use, and whether a local message cache
     * should be enabled for more efficient retrieval.
     */
    @Value
    @Builder(toBuilder = true)
    public static class TrackingClientConfig {

        /**
         * Number of parallel tracking sessions to open for the associated message type. Each session can be used to
         * track a different consumer or topic in parallel. Defaults to 1.
         */
        @Default
        int sessions = 1;

        /**
         * The size of the local message cache, used to improve tracking efficiency when multiple trackers are active on
         * the same message type and topic.
         * <p>
         * When {@code cacheSize > 0}, a single central tracker will be responsible for reading the latest messages from
         * the Fluxzero Runtime for a given topic. These messages are cached locally and can be reused by other
         * trackers, significantly reducing round-trips and load on the Fluxzero Runtime.
         * <p>
         * If set to 0, each tracker reads directly from the Fluxzero Runtime independently.
         * <p>
         * This setting is especially useful when many handlers are listening to the same topic concurrently.
         */
        @Default
        int cacheSize = 0;
    }
}
