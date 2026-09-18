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

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpStubConsumerTest {
    static final String EXTERNAL = "https://stub.example.invalid/check";

    @Test
    void namespacedStubRetainsDispatchInterceptorsAndCallerAuthorization() {
        for (boolean async : new boolean[]{false, true}) {
            for (boolean allowed : new boolean[]{false, true}) {
                var calls = new AtomicInteger();
                var caller = new Caller("caller", allowed);
                var builder = DefaultFluxzero.builder().registerUserProvider(new CallerProvider(caller))
                        .addDispatchInterceptor(
                        (message, type, topic) -> message.addMetadata("stub-check", "intercepted"));
                var stub = new NamespacedStub(calls);
                var fixture = async ? TestFixture.createAsync(builder, new SecuredEndpoint(), stub)
                        : TestFixture.create(builder, new SecuredEndpoint(), stub);
                var result = fixture.whenGetByUser(caller.id(), "/secured");
                if (allowed) {
                    result.expectWebResult(response -> response.getStatus() == 204).expectNoErrors();
                } else if (async) {
                    result.expectWebResult(response -> response.getStatus() == 401);
                } else {
                    result.expectExceptionalResult(UnauthorizedException.class);
                }
                assertEquals(allowed ? 1 : 0, calls.get());
            }
        }
    }

    @Test
    void separateConsumersSupportBlockingHttpStubIncludingLateRegistration() {
        for (boolean async : new boolean[]{false, true}) {
            for (boolean late : new boolean[]{false, true}) {
                var builder = DefaultFluxzero.builder().replacePropertySource(existing -> new SimplePropertySource(Map.of(
                        ConsumerConfiguration.UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY, "perPackage")).andThen(existing));
                var fixture = async ? TestFixture.createAsync(builder, new Endpoint()) : TestFixture.create(builder, new Endpoint());
                if (late) {
                    fixture = fixture.givenWebRequest(WebRequest.get("/ready").build());
                }
                fixture.registerHandlers(List.of(new Stub()))
                        .whenWebRequest(WebRequest.get("/app").build())
                        .<WebResponse>expectResult(response -> response.getStatus() == 204)
                        .expectNoErrors();
            }
        }
    }

    @Test
    void identicalConsumerConfigurationSupportsRegisteringAnotherHandlerAfterTrackingStarts() {
        for (boolean async : new boolean[]{false, true}) {
            var fixture = async ? TestFixture.createAsync(new SharedFirst()) : TestFixture.create(new SharedFirst());
            fixture.givenWebRequest(WebRequest.get("/first").build())
                    .registerHandlers(List.of(new SharedSecond()))
                    .whenWebRequest(WebRequest.get("/second").build())
                    .<WebResponse>expectResult(response -> response.getStatus() == 204)
                    .expectNoErrors();
        }
    }

    @Test
    void samePackageUnconfiguredHttpStubCanTriggerTheExistingDeadlockDiagnostic() {
        var builder = DefaultFluxzero.builder().replacePropertySource(existing -> new SimplePropertySource(Map.of(
                ConsumerConfiguration.UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY, "perPackage")).andThen(existing));
        TestFixture.create(builder, new UnconfiguredEndpoint(), new UnconfiguredStub())
                .whenWebRequest(WebRequest.get("/app").build())
                .verifyExceptionalResult((IllegalStateException failure) ->
                        assertTrue(failure.getMessage().contains("production deadlock risk")));
    }

    @Consumer(name = "app-http")
    static class Endpoint {
        @HandleGet("/ready") WebResponse ready() { return WebResponse.builder().status(204).build(); }
        @HandleGet("/app") WebResponse handle() {
            return Fluxzero.get().webRequestGateway().sendAndWait(WebRequest.get(EXTERNAL).build());
        }
    }
    @Consumer(name = "external-http-stub")
    static class Stub {
        @HandleGet(EXTERNAL) WebResponse handle() { return WebResponse.builder().status(204).build(); }
    }
    static class UnconfiguredEndpoint {
        @HandleGet("/app") WebResponse handle() {
            return Fluxzero.get().webRequestGateway().sendAndWait(WebRequest.get(EXTERNAL).build());
        }
    }
    static class UnconfiguredStub {
        @HandleGet(EXTERNAL) WebResponse handle() { return WebResponse.builder().status(204).build(); }
    }
    @Consumer(name = "shared-http")
    static class SharedFirst {
        @HandleGet("/first") WebResponse handle() { return WebResponse.builder().status(204).build(); }
    }
    @Consumer(name = "shared-http")
    static class SharedSecond {
        @HandleGet("/second") WebResponse handle() { return WebResponse.builder().status(204).build(); }
    }

    @Consumer(name = "secured-app")
    static class SecuredEndpoint {
        @HandleGet("/secured") WebResponse handle(User user) {
            // Deliberate fixture identity; real HTTP authentication needs an explicit credential contract.
            return Fluxzero.get().webRequestGateway().forNamespace("external")
                    .sendAndWait(WebRequest.get(EXTERNAL).build().addUser(user));
        }
    }

    @Consumer(name = "secured-stub", namespace = "external")
    static class NamespacedStub {
        private final AtomicInteger calls;

        NamespacedStub(AtomicInteger calls) { this.calls = calls; }

        @HandleGet(EXTERNAL)
        @RequiresAnyRole("caller")
        WebResponse handle(User user, DeserializingMessage message) {
            assertEquals("caller", user.id());
            assertEquals("external", ClientUtils.getConsumerNamespace(message));
            assertEquals("intercepted", message.getMetadata().get("stub-check"));
            calls.incrementAndGet();
            return WebResponse.builder().status(204).build();
        }
    }

    record Caller(String id, boolean allowed) implements User {
        @Override public String getName() { return id; }
        @Override public boolean hasRole(String role) { return allowed && "caller".equals(role); }
    }

    static class CallerProvider extends AbstractUserProvider {
        private final Caller caller;

        CallerProvider(Caller caller) {
            super(Caller.class);
            this.caller = caller;
        }

        @Override public User getUserById(Object id) { return caller.id().equals(id) ? caller : null; }
        @Override public User getSystemUser() { return null; }
    }
}
