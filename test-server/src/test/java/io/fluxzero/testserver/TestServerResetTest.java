/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.testserver;

import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.Message;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import java.util.List;
import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@Isolated
class TestServerResetTest {
    @Test void clearsAllStoresAcrossNamespacesAndTopicsWithoutReplacingServer() throws Exception {
        Server first = TestServer.startServer(0), other = TestServer.startServer(0);
        var a = client(first, "first"); var b = client(first, "second"); var isolated = client(other, "first"); var untracked = client(first, "untracked");
        try {
            var message = new Message("retained event").serialize(new JacksonSerializer());
            a.getEventStoreClient().storeEvents("aggregate", List.of(message), false, STORED).get(5, SECONDS);
            b.getGatewayClient(CUSTOM, "topic").append(STORED, message).get(5, SECONDS);
            isolated.getGatewayClient(EVENT).append(STORED, message).get(5, SECONDS);
            untracked.getEventStoreClient().storeEvents("aggregate", List.of(message), false, STORED).get(5, SECONDS);
            var document = new io.fluxzero.common.api.search.SerializedDocument("doc", null, null, "documents",
                    new io.fluxzero.common.api.Data<>("retained".getBytes(java.nio.charset.StandardCharsets.UTF_8), String.class.getName(), 0, "text/plain"),
                    "retained", java.util.Set.of(), java.util.Set.of());
            a.getSearchClient().index(List.of(document), STORED, false).get(5, SECONDS);
            a.getTrackingClient(DOCUMENT, "documents").readFromIndex(0, 10);
            a.getKeyValueClient().putValue("key", message.getData(), STORED).get(5, SECONDS);
            a.getSchedulingClient().schedule(STORED, new io.fluxzero.common.api.scheduling.SerializedSchedule(
                    "future", System.currentTimeMillis() + 60_000, message, false)).get(5, SECONDS);
            var tracking = a.getTrackingClient(EVENT);
            var events = tracking.readFromIndex(0, 10);
            assertEquals(1, events.size());
            tracking.storePosition("consumer", new int[]{0, 128}, events.getFirst().getIndex()).get(5, SECONDS);
            assertFalse(tracking.getPosition("consumer").isNew(new int[]{0, 128}));
            int port = ((ServerConnector) first.getConnectors()[0]).getLocalPort();
            TestServer.truncateData(first);
            assertEquals(port, ((ServerConnector) first.getConnectors()[0]).getLocalPort());
            assertTrue(first.isStarted());
            assertNull(a.getKeyValueClient().getValue("key"));
            assertNull(a.getSchedulingClient().getSchedule("future"));
            assertFalse(a.getSearchClient().fetch(new io.fluxzero.common.api.search.GetDocument("doc", "documents")).isPresent());
            assertTrue(tracking.readFromIndex(0, 10).isEmpty());
            assertTrue(untracked.getTrackingClient(EVENT).readFromIndex(0, 10).isEmpty());
            assertEquals(0, untracked.getEventStoreClient().getEvents("aggregate", -1, 10).count());
            assertTrue(tracking.getPosition("consumer").isNew(new int[]{0, 128}));
            assertTrue(b.getTrackingClient(CUSTOM, "topic").readFromIndex(0, 10).isEmpty());
            assertEquals(1, isolated.getTrackingClient(EVENT).readFromIndex(0, 10).size());
            assertEquals(0, a.getEventStoreClient().getEvents("aggregate", -1, 10).count());
            a.getGatewayClient(EVENT).append(STORED, new Message("after reset").serialize(new JacksonSerializer())).get(5, SECONDS);
            assertEquals(1, tracking.readFromIndex(0, 10).size());
            a.getSearchClient().index(List.of(document), STORED, false).get(5, SECONDS);
            assertTrue(a.getSearchClient().fetch(new io.fluxzero.common.api.search.GetDocument("doc", "documents")).isPresent());
            a.getKeyValueClient().putValue("key", message.getData(), STORED).get(5, SECONDS);
            assertNotNull(a.getKeyValueClient().getValue("key"));
            a.getSchedulingClient().schedule(STORED, new io.fluxzero.common.api.scheduling.SerializedSchedule(
                    "future", System.currentTimeMillis() + 60_000, message, true)).get(5, SECONDS);
            assertNotNull(a.getSchedulingClient().getSchedule("future"));
        } finally { a.shutDown(); b.shutDown(); isolated.shutDown(); untracked.shutDown(); first.stop(); other.stop(); }
        assertThrows(IllegalArgumentException.class, () -> TestServer.truncateData(first));
    }
    private WebSocketClient client(Server server, String namespace) {
        return WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort())
                .namespace(namespace).name("reset-test").disableMetrics(true).build());
    }
}
