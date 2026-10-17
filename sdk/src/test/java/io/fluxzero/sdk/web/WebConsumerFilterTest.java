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
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.Request;
import io.fluxzero.sdk.web.path.ClassPathHandler;
import io.fluxzero.sdk.web.path.PackagePathHandler;
import io.fluxzero.sdk.web.path.subpath.ExternalUrlPathHandler;
import io.fluxzero.sdk.web.path.subpath.ResetPathHandler;
import io.fluxzero.sdk.web.path.subpath.SubPathHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.MessageType.WEBRESPONSE;
import static io.fluxzero.sdk.web.HttpRequestMethod.*;
import static io.fluxzero.sdk.tracking.BatchInterceptor.preservingConsumerFilter;
import static org.junit.jupiter.api.Assertions.*;

class WebConsumerFilterTest {
    @ParameterizedTest
    @MethodSource("routes")
    void selectionAgreesWithRealRoutes(Object target, String method, String url, boolean expected) {
        var handler = new DefaultHandlerFactory(WEBREQUEST, HandlerDecorator.noOp,
                List.of(new MessageParameterResolver(), new WebParamParameterResolver()),
                MethodInvocationValidator.noOp(), ignored -> null, null, false, new JacksonSerializer())
                .createHandler(target, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        var message = request(method, url);
        assertEquals(expected, handler.getInvoker(new JacksonSerializer().deserializeMessage(message, WEBREQUEST))
                .isPresent(), "actual handler selection");
        assertTrue(handler.getConsumerFilter().routeRestricted());
        assertEquals(expected, handler.getConsumerFilter().compile().test(message), "server selection");
    }

    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of(new Plain(), GET, "/plain/a?x=y", true),
                Arguments.of(new Plain(), GET, "plain/a", true),
                Arguments.of(new Plain(), HEAD, "/plain/a/", true),
                Arguments.of(new Plain(), OPTIONS, "/plain/a", true),
                Arguments.of(new Plain(), POST, "/plain/a", false),
                Arguments.of(new Plain(), GET, "/elsewhere", false),
                Arguments.of(new Plain(), GET, "/plain/a%2Fb+z#fragment", true),
                Arguments.of(new Plain(), GET, "/items/abc/detail", true),
                Arguments.of(new Plain(), GET, "/items/abc", true),
                Arguments.of(new Plain(), GET, "/items/123", false),
                Arguments.of(new ImplicitRoot(), GET, "/", true),
                Arguments.of(new ImplicitRoot(), HEAD, "", true),
                Arguments.of(new ImplicitRoot(), OPTIONS, "/?x=y", true),
                Arguments.of(new ImplicitRoot(), GET, "/other", false),
                Arguments.of(new OriginRoot(), GET, "https://example.com", true),
                Arguments.of(new OriginRoot(), HEAD, "https://example.com?x=y", true),
                Arguments.of(new OriginRoot(), OPTIONS, "https://example.com/#fragment", true),
                Arguments.of(new OriginRoot(), GET, "https://other.example", false),
                Arguments.of(new Root(), GET, "/", true),
                Arguments.of(new Root(), GET, "", true),
                Arguments.of(new Root(), GET, "?x=y", true),
                Arguments.of(new PackagePathHandler(), GET, "/package/get", true),
                Arguments.of(new ClassPathHandler(), GET, "/class/get", true),
                Arguments.of(new ClassPathHandler(), GET, "/method/get", true),
                Arguments.of(new ClassPathHandler(), GET, "/package/class/get", false),
                Arguments.of(new SubPathHandler(), GET, "/package/sub/class/get", true),
                Arguments.of(new ResetPathHandler(), GET, "/get-reset", true),
                Arguments.of(new ExternalUrlPathHandler(), GET, "https://example.com/from-path/get-via-path?x=y", true),
                Arguments.of(new ExternalUrlPathHandler(), GET, "https://other.example/from-path/get-via-path", false),
                Arguments.of(new BoundPath("/bound"), GET, "/bound/item", true),
                Arguments.of(new BoundPath("/bound"), GET, "/item", false),
                Arguments.of(new Site(), GET, "/index.html", true),
                Arguments.of(new MountedSite(), GET, "/site/index.html", true),
                Arguments.of(new MountedSite(), GET, "/site/deep/spa/path", true),
                Arguments.of(new MountedSite(), HEAD, "/site/index.html", true),
                Arguments.of(new MountedSite(), OPTIONS, "/site/index.html", true),
                Arguments.of(new MountedSite(), POST, "/site/index.html", false),
                Arguments.of(new MountedSite(), GET, "/outside/index.html", false));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void staticRoutesAndFallbackWorkWithAndWithoutPath(boolean async, boolean mounted) {
        var configuration = new AtomicReference<ConsumerConfiguration>();
        var builder = builder(configuration);
        var fixture = async ? TestFixture.createAsync(builder, mounted ? new MountedSite() : new Site())
                : TestFixture.create(builder, mounted ? new MountedSite() : new Site());
        String root = mounted ? "/site" : "";
        try (var app = fixture.getFluxzero()) {
            fixture.whenGet(root + "/api/ping").expectResult("pong").expectNoErrors();
            fixture.whenGet(root + "/index.html").expectWebResult(r -> r.getStatus() == 200
                    && r.<String>getPayloadAs(String.class).contains("Hello, World!")).expectNoErrors();
            fixture.whenGet(root + "/about").expectWebResult(r -> r.getStatus() == 200).expectNoErrors();
            fixture.whenGet(root + "/deep/spa/path").expectWebResult(r -> r.getStatus() == 200
                    && r.<String>getPayloadAs(String.class).contains("Hello, World!")).expectNoErrors();
            fixture.whenWebRequest(WebRequest.builder().method(HEAD).url(root + "/index.html").build())
                    .expectWebResult(r -> r.getStatus() == 200 && r.getPayload() == null).expectNoErrors();
            fixture.whenWebRequest(WebRequest.builder().method(OPTIONS).url(root + "/index.html").build())
                    .expectWebResult(r -> r.getStatus() == 204 && r.getHeader("Allow").contains(HEAD)).expectNoErrors();
            if (async) {
                assertTrue(configuration.get().getConsumerBlueprint().getFilter().routeRestricted());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void socketsAndStaticContentCoexistWithoutLosingLifecycleInputs(boolean async, boolean mounted, boolean endpoint)
            throws Exception {
        var handled = new ConcurrentHashMap<String, CompletableFuture<Void>>();
        var probe = new SessionProbe();
        var configuration = new AtomicReference<ConsumerConfiguration>();
        var builder = builder(configuration).addDispatchInterceptor((message, type, topic) -> {
            if (message.getPayload() instanceof SocketRequest request) {
                probe.request.set(request);
            }
            return message;
        }, WEBRESPONSE);
        builder = builder.addBatchInterceptor(preservingConsumerFilter((consumer, tracker) -> batch -> {
            consumer.accept(batch);
            batch.getMessages().forEach(m -> {
                var completion = handled.get(m.getMessageId());
                if (completion != null) completion.complete(null);
            });
        }), WEBREQUEST);
        Object target = endpoint ? (mounted ? MountedEndpoint.class : Endpoint.class)
                : (mounted ? new MountedCombined() : new Combined());
        var fixture = async ? TestFixture.createAsync(builder, target) : TestFixture.create(builder, target);
        fixture = fixture.withBean(probe);
        String root = mounted ? "/site" : "";
        try (var app = fixture.getFluxzero()) {
            fixture.whenGet(root + "/index.html").expectWebResult(r -> r.getStatus() == 200).expectNoErrors();
            oneWaySocket(fixture, async, handled, socket(WS_HANDSHAKE, root + "/socket"));
            var opened = fixture.whenWebRequest(socket(WS_OPEN, root + "/socket"));
            if (async) opened.expectWebResponses("open").expectNoErrors();
            else opened.expectResult(WebResponse.builder().payload("open").build()).expectNoErrors();
            var message = fixture.whenWebRequest(socket(WS_MESSAGE, root + "/socket"));
            if (async) message.expectWebResponses("echo").expectNoErrors();
            else message.expectResult(WebResponse.builder().payload("echo").build()).expectNoErrors();
            assertTrue(probe.session.get().isOpen());
            fixture.whenExecuting(fc -> probe.response.set(probe.session.get()
                    .sendRequest(new ReplyRequest(), Duration.ofSeconds(30)).toCompletableFuture())).expectNoErrors();
            assertNotNull(probe.request.get());
            oneWaySocket(fixture, async, handled, socket(WS_MESSAGE, "/unmatched-response-path").toBuilder()
                    .payload(SocketResponse.success(probe.request.get().getRequestId(), "reply")).build());
            assertEquals("reply", probe.response.get().get(5, TimeUnit.SECONDS),
                         "session-correlated responses must survive a different request path");
            oneWaySocket(fixture, async, handled, socket(WS_PONG, root + "/socket"));
            if (endpoint) {
                oneWaySocket(fixture, async, handled, socket(WS_PONG, "/unmatched-pong-path"));
            }
            oneWaySocket(fixture, async, handled, socket(WS_CLOSE, "/unmatched-close-path"));
            assertFalse(probe.session.get().isOpen(), "an unmatched close must still clean up the session");
            fixture.whenGet(root + "/deep/spa/path").expectWebResult(r -> r.getStatus() == 200).expectNoErrors();
            if (!endpoint) {
                fixture.whenGet(root + "/api/ping").expectResult("pong").expectNoErrors();
            }
            if (async) {
                var filter = configuration.get().getConsumerBlueprint().getFilter();
                assertTrue(filter.routeRestricted());
                assertTrue(filter.compile().test(request(WS_CLOSE, "/unmatched-close-path")));
                assertTrue(filter.compile().test(request(WS_MESSAGE, "/unmatched-response-path")));
                assertEquals(endpoint, filter.compile().test(request(WS_PONG, "/unmatched-pong-path")));
                assertFalse(filter.compile().test(request(POST, root + "/index.html")));
                if (mounted) {
                    assertFalse(filter.compile().test(request(GET, "/outside")));
                    assertFalse(filter.compile().test(request(OPTIONS, "/outside")));
                }
            }
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

    private static FluxzeroBuilder builder(AtomicReference<ConsumerConfiguration> configuration) {
        return DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics()
                .addBatchInterceptor(preservingConsumerFilter((consumer, tracker) -> {
                    configuration.set(tracker.getConfiguration());
                    return consumer;
                }), WEBREQUEST);
    }

    private static WebRequest socket(String method, String url) {
        return WebRequest.builder().method(method).url(url).metadata(Metadata.of("sessionId", "filter-session")).build();
    }

    private static SerializedMessage request(String method, String url) {
        return io.fluxzero.sdk.web.WebRequestRoute.update(new SerializedMessage(new Data<>(new byte[0], null, 0, "unknown"),
                Metadata.of("method", method, "url", url), UUID.randomUUID().toString(), System.currentTimeMillis()));
    }

    static class Plain {
        @HandleGet("/plain/{id}") String plain() { return "plain"; }
        @HandleGet("/items/{id:[a-z]+}[/{part}]") String optional() { return "optional"; }
    }
    @Path("https://example.com")
    static class OriginRoot { @HandleGet String root() { return "root"; } }
    static class ImplicitRoot { @HandleGet String root() { return "root"; } }
    static class Root { @HandleGet("/") String root() { return "root"; } }
    static class BoundPath {
        @Path("") final String root;
        BoundPath(String root) { this.root = root; }
        @HandleGet("item") String get() { return "bound"; }
    }
    @ServeStatic(resourcePath = "classpath:/web/static")
    static class Site { @HandleGet("api/ping") String ping() { return "pong"; } }
    @Path("/site") @ServeStatic(resourcePath = "classpath:/web/static")
    static class MountedSite extends Site {}
    @ServeStatic(resourcePath = "classpath:/web/static")
    static class Combined extends Site {
        @HandleSocketOpen("socket") String open(SocketSession session, @Autowired SessionProbe probe) {
            probe.session.set(session);
            return "open";
        }
        @HandleSocketMessage("socket") String message() { return "echo"; }
        @HandleSocketPong("socket") void pong() {}
    }
    @Path("/site") @ServeStatic(resourcePath = "classpath:/web/static")
    static class MountedCombined extends Combined {}
    static class SessionProbe {
        final AtomicReference<SocketSession> session = new AtomicReference<>();
        final AtomicReference<SocketRequest> request = new AtomicReference<>();
        final AtomicReference<CompletableFuture<String>> response = new AtomicReference<>();
    }
    record ReplyRequest() implements Request<String> {}
    @SocketEndpoint(aliveCheck = @SocketEndpoint.AliveCheck(false))
    @ServeStatic(resourcePath = "classpath:/web/static")
    static class Endpoint extends Combined {}
    @Path("/site") @ServeStatic(resourcePath = "classpath:/web/static")
    static class MountedEndpoint extends Endpoint {}
}
