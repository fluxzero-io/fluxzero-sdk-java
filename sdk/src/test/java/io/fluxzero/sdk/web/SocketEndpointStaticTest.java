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
package io.fluxzero.sdk.web;

import io.fluxzero.common.Guarantee;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.sdk.web.HttpRequestMethod.*;
import static org.junit.jupiter.api.Assertions.*;

class SocketEndpointStaticTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void servesFilesAlongsidePerSessionEndpointHandlers(boolean async, boolean mounted) throws Exception {
        var handled = new ConcurrentHashMap<String, CompletableFuture<Void>>();
        var probe = new SessionProbe();
        var builder = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics()
                .addBatchInterceptor((consumer, tracker) -> batch -> {
                    consumer.accept(batch);
                    batch.getMessages().forEach(m -> {
                        var completion = handled.get(m.getMessageId());
                        if (completion != null) completion.complete(null);
                    });
                }, WEBREQUEST);
        Object type = mounted ? MountedEndpoint.class : Endpoint.class;
        var fixture = async ? TestFixture.createAsync(builder, type) : TestFixture.create(builder, type);
        fixture = fixture.withBean(probe);
        String root = mounted ? "/site" : "";
        try (var app = fixture.getFluxzero()) {
            fixture.whenGet(root + "/index.html").expectWebResult(r -> r.getStatus() == 200).expectNoErrors();
            fixture.whenWebRequest(socket(WS_OPEN, root + "/socket")).expectNoErrors();
            var response = fixture.whenWebRequest(socket(WS_MESSAGE, root + "/socket"));
            if (async) response.expectWebResponses("echo").expectNoErrors();
            else response.expectResult(WebResponse.builder().payload("echo").build()).expectNoErrors();
            assertTrue(probe.session.get().isOpen());
            oneWaySocket(fixture, async, handled, socket(WS_CLOSE, "/unmatched-close-path"));
            assertFalse(probe.session.get().isOpen());
            fixture.whenGet(root + "/deep/spa/path").expectWebResult(r -> r.getStatus() == 200).expectNoErrors();
        }
    }

    private static void oneWaySocket(TestFixture fixture, boolean async,
                                     ConcurrentMap<String, CompletableFuture<Void>> handled, WebRequest request)
            throws Exception {
        if (!async) {
            fixture.whenWebRequest(request).expectNoErrors();
            return;
        }
        var completion = new CompletableFuture<Void>();
        handled.put(request.getMessageId(), completion);
        try {
            var app = fixture.getFluxzero();
            app.client().getGatewayClient(WEBREQUEST)
                    .append(Guarantee.STORED, request.serialize(app.serializer())).join();
            completion.get(5, TimeUnit.SECONDS);
            fixture.whenNothingHappens().expectNoErrors();
        } finally {
            handled.remove(request.getMessageId());
        }
    }

    private static WebRequest socket(String method, String url) {
        return WebRequest.builder().method(method).url(url).metadata(
                io.fluxzero.common.api.Metadata.of("sessionId", "static-endpoint-session")).build();
    }

    static class SessionProbe { final AtomicReference<SocketSession> session = new AtomicReference<>(); }
    @SocketEndpoint(aliveCheck = @SocketEndpoint.AliveCheck(false))
    @ServeStatic(resourcePath = "classpath:/web/static")
    static class Endpoint {
        @HandleSocketOpen("socket") String open(SocketSession session, @Autowired SessionProbe probe) {
            probe.session.set(session);
            return "open";
        }
        @HandleSocketMessage("socket") String message() { return "echo"; }
    }
    @Path("/site") @ServeStatic(resourcePath = "classpath:/web/static")
    static class MountedEndpoint extends Endpoint {}
}
