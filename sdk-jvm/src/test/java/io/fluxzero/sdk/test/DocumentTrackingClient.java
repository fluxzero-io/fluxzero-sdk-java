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

package io.fluxzero.sdk.test;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.ClientDispatchMonitor;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class DocumentTrackingClient implements Client {
    private final Client delegate;
    private final TestFixture.GivenWhenThenInterceptor interceptor;
    private SearchClient searchClient;

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String applicationId() {
        return delegate.applicationId();
    }

    @Override
    public String namespace() {
        return delegate.namespace();
    }

    @Override
    public Client forNamespace(String namespace) {
        Client client = delegate.forNamespace(namespace);
        if (client == delegate) {
            return this;
        }
        return new DocumentTrackingClient(client, interceptor);
    }

    @Override
    public GatewayClient getGatewayClient(MessageType messageType, String topic) {
        return delegate.getGatewayClient(messageType, topic);
    }

    @Override
    public Registration monitorDispatch(ClientDispatchMonitor monitor, MessageType... messageTypes) {
        return delegate.monitorDispatch(monitor, messageTypes);
    }

    @Override
    public TrackingClient getTrackingClient(MessageType messageType, String topic) {
        return delegate.getTrackingClient(messageType, topic);
    }

    @Override
    public EventStoreClient getEventStoreClient() {
        return delegate.getEventStoreClient();
    }

    @Override
    public SchedulingClient getSchedulingClient() {
        return delegate.getSchedulingClient();
    }

    @Override
    public KeyValueClient getKeyValueClient() {
        return delegate.getKeyValueClient();
    }

    @Override
    public SearchClient getSearchClient() {
        if (searchClient == null) {
            searchClient = new DocumentTrackingSearchClient(delegate.getSearchClient(), interceptor);
        }
        return searchClient;
    }

    @Override
    public void shutDown() {
        delegate.shutDown();
    }

    @Override
    public Registration beforeShutdown(Runnable task) {
        return delegate.beforeShutdown(task);
    }

    @Override
    public Client unwrap() {
        return delegate.unwrap();
    }
}
