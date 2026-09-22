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

package io.fluxzero.proxy;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.tracking.client.LocalTrackingClient;
import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.util.Arrays;

/** Exercises actual proxy forwarding and SDK response mapping, without an external runtime or retained fixture history. */
final class HeaderBufferTestSupport implements AutoCloseable {
    final Fluxzero fluxzero;
    private final BenchmarkClient client = new BenchmarkClient();
    final ArrayByteBufferPool pool;
    final Server server;
    final ProxyServer proxy;

    HeaderBufferTestSupport(boolean instrument, boolean ready) {
        fluxzero = DefaultFluxzero.builder().disableAutomaticTracking().disableTrackingMetrics()
                .disableKeepalive().disableShutdownHook().build(client);
        fluxzero.registerHandlers(new Responses());
        pool = instrument ? new HeaderBufferPool() : new ArrayByteBufferPool();
        server = new Server(new QueuedThreadPool(32, 8), null, pool);
        try {
            proxy = ProxyServer.startHttpProxyOnly(server, new ProxyRequestHandler(fluxzero.client()), ready);
        } catch (RuntimeException | Error e) {
            fluxzero.close();
            throw e;
        }
    }

    /** Called only between completed driver batches; never expire outstanding requests. */
    void clearCompletedMessages() {
        client.stores.values().forEach(DiscardableStore::discardCompleted);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void ready(boolean ready) {
        java.util.concurrent.atomic.AtomicReference state = io.fluxzero.common.reflection.ReflectionUtils
                .<java.util.concurrent.atomic.AtomicReference>getFieldValue("lifecycleState", proxy).orElseThrow();
        state.set(Enum.valueOf(((Enum) state.get()).getDeclaringClass(), ready ? "READY" : "STARTING"));
    }

    String url(String path) {
        return "http://127.0.0.1:" + proxy.getPort() + path;
    }

    @Override
    public void close() {
        try {
            proxy.cancel();
        } finally {
            fluxzero.close();
            pool.clear();
        }
    }

    private static final class BenchmarkClient extends LocalClient {
        final java.util.Map<MessageType, DiscardableStore> stores = new java.util.concurrent.ConcurrentHashMap<>();

        BenchmarkClient() { super(null); }

        @Override
        protected io.fluxzero.sdk.publishing.client.GatewayClient createGatewayClient(MessageType type, String topic) {
            if (type != MessageType.WEBREQUEST && type != MessageType.WEBRESPONSE) {
                return super.createGatewayClient(type, topic);
            }
            DiscardableStore store = new DiscardableStore(type);
            stores.put(type, store);
            return new LocalTrackingClient(store, new io.fluxzero.common.tracking.InMemoryPositionStore(), type, topic);
        }
    }

    static final class DiscardableStore extends io.fluxzero.sdk.tracking.client.InMemoryMessageStore {
        DiscardableStore(MessageType type) { super(type, null); }

        synchronized void discardCompleted() {
            // Unlike truncate(), expiry preserves the monotonically advancing nextIndex.
            // The driver calls this only when every request in its batch has completed.
            purgeExpiredMessages(java.time.Duration.ZERO);
        }
    }

    static class Responses {
        private final byte[] largeBody = new byte[2 * 1024 * 1024];
        private final String largeHeader = "h".repeat(ProxyServer.DEFAULT_MAX_HEADER_SIZE + 1);

        Responses() {
            Arrays.fill(largeBody, (byte) 'b');
        }

        @HandleGet("/buffer")
        WebResponse response(WebRequest request) {
            int headerBytes = Integer.parseInt(request.getHeader("X-Header-Bytes"));
            boolean large = "large".equals(request.getHeader("X-Body"));
            return WebResponse.builder().status(200).contentType("application/octet-stream")
                    .header("X-Padding", largeHeader.substring(0, headerBytes))
                    .payload(large ? largeBody : new byte[]{'o', 'k'}).build();
        }
    }
}
