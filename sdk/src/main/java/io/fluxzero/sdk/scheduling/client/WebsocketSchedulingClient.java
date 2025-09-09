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

package io.fluxzero.sdk.scheduling.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.scheduling.CancelSchedule;
import io.fluxzero.common.api.scheduling.GetSchedule;
import io.fluxzero.common.api.scheduling.GetScheduleResult;
import io.fluxzero.common.api.scheduling.Schedule;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.scheduling.MessageScheduler;
import jakarta.websocket.ClientEndpoint;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket-based implementation of the {@link SchedulingClient} interface that communicates with the Fluxzero Runtime.
 * <p>
 * This client is responsible for scheduling, cancelling, and querying deferred messages (schedules) over a WebSocket
 * connection. It acts as the transport layer for the {@link io.fluxzero.sdk.scheduling.MessageScheduler}.
 *
 * <p>Usage is typically indirect via high-level APIs like {@code Fluxzero.schedule(...)} or the
 * {@link MessageScheduler}. Direct interaction with this client is uncommon in most application code.
 *
 * @see SchedulingClient
 * @see MessageScheduler
 * @see SerializedSchedule
 */
@ClientEndpoint
public class WebsocketSchedulingClient extends AbstractWebsocketClient implements SchedulingClient {

    /**
     * Constructs a scheduling client connected to the given endpoint URL.
     *
     * @param endPointUrl The endpoint URL of the Fluxzero Runtime scheduling gateway.
     * @param client      The Flux {@link WebSocketClient} configuration to use.
     */
    public WebsocketSchedulingClient(String endPointUrl, WebSocketClient client) {
        this(URI.create(endPointUrl), client);
    }

    /**
     * Constructs a scheduling client connected to the given endpoint URI.
     *
     * @param endpointUri The URI of the scheduling gateway.
     * @param client      The Flux {@link WebSocketClient} configuration to use.
     */
    public WebsocketSchedulingClient(URI endpointUri, WebSocketClient client) {
        this(endpointUri, client, true);
    }

    /**
     * Constructs a scheduling client connected to the given endpoint URI with an option to enable or disable metrics
     * tracking.
     *
     * @param endpointUri The URI of the scheduling gateway.
     * @param client      The WebSocket client configuration.
     * @param sendMetrics Whether to send metrics about schedule operations.
     */
    public WebsocketSchedulingClient(URI endpointUri, WebSocketClient client, boolean sendMetrics) {
        super(endpointUri, client, sendMetrics, client.getClientConfig()
                .getGatewaySessions().get(MessageType.SCHEDULE));
    }

    @Override
    public CompletableFuture<Void> schedule(Guarantee guarantee, SerializedSchedule... schedules) {
        return sendCommand(new Schedule(Arrays.asList(schedules), guarantee));
    }

    @Override
    public CompletableFuture<Void> cancelSchedule(String scheduleId, Guarantee guarantee) {
        return sendCommand(new CancelSchedule(scheduleId, guarantee));
    }

    @Override
    public SerializedSchedule getSchedule(String scheduleId) {
        return this.<GetScheduleResult>sendAndWait(new GetSchedule(scheduleId)).getSchedule();
    }
}
