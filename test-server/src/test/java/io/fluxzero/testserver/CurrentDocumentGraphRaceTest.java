/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.testserver;

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import jakarta.annotation.Nullable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(20)
class CurrentDocumentGraphRaceTest {
    private static Server server;
    private static int port;

    @BeforeAll
    static void start() {
        server = TestServer.startServer(new java.net.InetSocketAddress("127.0.0.1", 0));
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception { server.stop(); }

    @ParameterizedTest
    @CsvSource({"false,create", "true,create", "false,replace", "true,replace", "false,delete", "true,delete"})
    void currentRootRemainsCoherentAcrossConcurrentDocumentChanges(boolean explicitCurrent, String change) {
        String namespace = "current-document-" + UUID.randomUUID();
        var client = new GateClient(config(namespace, "reader"));
        try (Fluxzero reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(WebSocketClient.newInstance(config(namespace, "writer")))) {
            if (!change.equals("create")) { write(writer, 1); }
            client.afterHead.set(() -> write(writer, change.equals("delete") ? null : change.equals("create") ? 1 : 2));
            Graph<Document> graph = reader.apply(fc -> explicitCurrent
                    ? Fluxzero.loadCurrentGraph("doc", Document.class) : Fluxzero.loadGraph("doc", Document.class));
            Document expected = change.equals("delete") || explicitCurrent && change.equals("create")
                    ? null : new Document("doc", change.equals("replace") ? 2 : 1);
            assertEquals(expected, graph.get());
            assertEquals(expected, graph.get());
        }
    }

    private static WebSocketClient.ClientConfig config(String namespace, String name) {
        return WebSocketClient.ClientConfig.builder().name(name).namespace(namespace)
                .runtimeBaseUrl("ws://127.0.0.1:" + port).build();
    }

    private static void write(Fluxzero app, Integer version) {
        app.apply(fc -> { Fluxzero.assertAndApply(new SetDocument("doc", version)); return null; });
    }

    @Model(persistence = ModelPersistence.DOCUMENT)
    record Document(@EntityId String id, int version) {}
    record SetDocument(String id, Integer version) {
        @Apply Document apply(@Nullable Document previous) { return version == null ? null : new Document(id, version); }
    }

    private static class GateClient extends WebSocketClient {
        final AtomicReference<Runnable> afterHead = new AtomicReference<>();
        GateClient(ClientConfig config) { super(config, null); }
        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        Object result;
                        try { result = method.invoke(delegate, arguments); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                        if (method.getName().equals("getModelEvents")
                            && ((GetModelEvents) arguments[0]).getRequests().stream()
                                .anyMatch(request -> request.getModelId().equals("doc"))) {
                            Runnable write = afterHead.getAndSet(null);
                            if (write != null) { write.run(); }
                        }
                        return result;
                    });
        }
    }
}
