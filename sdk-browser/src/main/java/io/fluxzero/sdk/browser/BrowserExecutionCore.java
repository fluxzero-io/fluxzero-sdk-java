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

package io.fluxzero.sdk.browser;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.QueryGateway;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Browser-native Fluxzero execution core backed by in-memory adapters.
 */
public final class BrowserExecutionCore {
    private final BrowserComponentRegistry metadata;
    private final BrowserMessageBus messageBus;
    private final BrowserKeyValueStore keyValueStore = new BrowserKeyValueStore();
    private final BrowserEventStore eventStore = new BrowserEventStore();
    private final BrowserDocumentStore documentStore = new BrowserDocumentStore();
    private final BrowserCodecRegistry codecRegistry = new BrowserCodecRegistry();
    private final BrowserScheduler scheduler;
    private final BrowserWebRouter webRouter = new BrowserWebRouter();
    private final BrowserSocketSimulator socketSimulator = new BrowserSocketSimulator();
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    public BrowserExecutionCore(Clock clock) {
        this(clock, BrowserComponentRegistry.empty());
    }

    public BrowserExecutionCore(Clock clock, BrowserComponentRegistry metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        messageBus = new BrowserMessageBus(Objects.requireNonNull(clock, "clock"));
        scheduler = new BrowserScheduler(clock, messageBus);
        commandGateway = new BrowserCommandGateway(new CoreCommandDispatcher());
        queryGateway = new BrowserQueryGateway(new CoreQueryDispatcher());
    }

    public static BrowserExecutionCore create(Clock clock) {
        return new BrowserExecutionCore(clock);
    }

    public static BrowserExecutionCore create(Clock clock, BrowserComponentRegistry metadata) {
        return new BrowserExecutionCore(clock, metadata);
    }

    public BrowserComponentRegistry metadata() {
        return metadata;
    }

    public BrowserMessageBus messageBus() {
        return messageBus;
    }

    public BrowserKeyValueStore keyValueStore() {
        return keyValueStore;
    }

    public BrowserEventStore eventStore() {
        return eventStore;
    }

    public BrowserDocumentStore documentStore() {
        return documentStore;
    }

    public BrowserCodecRegistry codecRegistry() {
        return codecRegistry;
    }

    public BrowserScheduler scheduler() {
        return scheduler;
    }

    public BrowserWebRouter webRouter() {
        return webRouter;
    }

    public BrowserSocketSimulator socketSimulator() {
        return socketSimulator;
    }

    public CommandGateway commandGateway() {
        return commandGateway;
    }

    public QueryGateway queryGateway() {
        return queryGateway;
    }

    public Object publish(MessageType messageType, Object payload) {
        return messageBus.dispatch(messageType, payload);
    }

    public void register(String componentName, MessageType messageType, String payloadTypeName, BrowserHandler handler) {
        Objects.requireNonNull(componentName, "componentName");
        Objects.requireNonNull(messageType, "messageType");
        BrowserRouteMetadata route = metadata.findRoute(componentName, messageType, payloadTypeName);
        if (route == null) {
            throw new IllegalArgumentException(
                    "No browser route metadata found for " + componentName + " and " + messageType);
        }
        register(route, handler);
    }

    public void register(BrowserRouteMetadata route, BrowserHandler handler) {
        messageBus.register(BrowserHandlerRegistration.from(route, handler));
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("metadata", metadataSnapshot());
        snapshot.put("messageBus", messageBus.snapshot());
        snapshot.put("keyValue", keyValueStore.snapshot());
        snapshot.put("events", eventStore.snapshot());
        snapshot.put("documents", documentStore.snapshot());
        snapshot.put("codecs", codecRegistry.snapshot());
        snapshot.put("scheduler", scheduler.snapshot());
        snapshot.put("web", webRouter.snapshot());
        snapshot.put("socket", socketSimulator.snapshot());
        return snapshot;
    }

    private Map<String, Object> metadataSnapshot() {
        return metadata.snapshot();
    }

    private final class CoreCommandDispatcher implements CommandDispatcher {
        @Override
        public Object dispatch(Object command) {
            return messageBus.dispatch(MessageType.COMMAND, command);
        }

        @Override
        public void dispatchAndForget(Object command) {
            messageBus.dispatch(MessageType.COMMAND, command);
        }
    }

    private final class CoreQueryDispatcher implements QueryDispatcher {
        @Override
        public Object dispatch(Object query) {
            return messageBus.dispatch(MessageType.QUERY, query);
        }
    }
}
