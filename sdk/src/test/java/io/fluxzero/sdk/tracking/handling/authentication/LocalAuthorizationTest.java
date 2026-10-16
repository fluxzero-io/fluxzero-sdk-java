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

package io.fluxzero.sdk.tracking.handling.authentication;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.silent.SilentRequest;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.fluxzero.sdk.publishing.DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class LocalAuthorizationTest {
    private User user;
    private final List<String> invocations = new ArrayList<>();

    @Test
    void roleFilteredHandlersFollowTheUserOfEachRequest() {
        try (Fluxzero fluxzero = createFluxzero(new AdminHandler(), new MemberHandler())) {
            user = new MockUser("admin");
            assertResponse(fluxzero, new Request(), "admin");
            user = new MockUser("member");
            assertResponse(fluxzero, new Request(), "member");
            user = null;
            assertSkipped(fluxzero, new Request());
            user = new MockUser("admin");
            assertResponse(fluxzero, new Request(), "admin");
            assertEquals(List.of("admin", "admin", "member", "member", "admin", "admin"), invocations);
        }
    }

    @Test
    void silentRoleFilterDoesNotDiscardAuthorizedRequestsWithoutDataProtection() {
        try (Fluxzero fluxzero = createFluxzero(DefaultFluxzero.builder().disableDataProtection(), new AdminHandler())) {
            user = new MockUser("admin");
            assertResponse(fluxzero, new Request(), "admin");
            user = new MockUser("member");
            assertSkipped(fluxzero, new Request());
            user = new MockUser("admin");
            assertResponse(fluxzero, new Request(), "admin");
        }
    }

    @Test
    void anonymousHandlerDoesNotRemainEligibleWhenAUserLogsIn() {
        try (Fluxzero fluxzero = createFluxzero(new AnonymousHandler())) {
            assertResponse(fluxzero, new Request(), "anonymous");
            user = new MockUser();
            assertSkipped(fluxzero, new Request());
            user = null;
            assertResponse(fluxzero, new Request(), "anonymous");
        }
    }

    @Test
    void throwingAnonymousHandlerDoesNotRemainEligibleWhenAUserLogsIn() {
        try (Fluxzero fluxzero = createFluxzero(new ThrowingAnonymousHandler())) {
            assertResponse(fluxzero, new Request(), "anonymous");
            user = new MockUser();
            assertThrows(UnauthorizedException.class, () -> fluxzero.commandGateway().sendAndWait(new Request()));
            assertThrows(UnauthorizedException.class, () -> fluxzero.queryGateway().sendAndWait(new Request()));
            assertEquals(List.of("anonymous", "anonymous"), invocations);
            user = null;
            assertResponse(fluxzero, new Request(), "anonymous");
        }
    }

    @Test
    void silentPayloadPolicyRejectsRequestsBeforeInvokingTheHandler() {
        try (Fluxzero fluxzero = createFluxzero(new PublicHandler())) {
            assertSkipped(fluxzero, new SilentRequest());
            user = new MockUser("admin");
            assertResponse(fluxzero, new SilentRequest(), "handled");
            user = new MockUser("member");
            assertSkipped(fluxzero, new SilentRequest());
            user = new MockUser("admin");
            assertResponse(fluxzero, new SilentRequest(), "handled");
        }
    }

    @Test
    void emptyRolePolicyStillRejectsAnonymousRequests() {
        try (Fluxzero fluxzero = createFluxzero(new PublicHandler())) {
            user = new MockUser();
            assertResponse(fluxzero, new EmptyRoleRequest(), "handled");
            user = null;
            assertSkipped(fluxzero, new EmptyRoleRequest());
        }
    }

    @Test
    void selfHandlingPayloadChecksTheCurrentUserBeforeUsingItsPreparedHandler() {
        try (Fluxzero fluxzero = createFluxzero()) {
            user = new MockUser("admin");
            assertEquals("self", fluxzero.commandGateway().sendAndWait(new SelfHandlingRequest()));
            assertEquals("self", fluxzero.queryGateway().sendAndWait(new SelfHandlingRequest()));
            user = null;
            assertSkipped(fluxzero, new SelfHandlingRequest());
            user = new MockUser("admin");
            assertResponse(fluxzero, new SelfHandlingRequest(), "self");
        }
    }

    @Test
    void roleFilteredHandlerSupportsRawRequestsAndRequestsWithMetadata() {
        try (Fluxzero fluxzero = createFluxzero(new AdminHandler())) {
            user = new MockUser("admin");
            assertEquals("admin", fluxzero.commandGateway().sendAndWait(new Request()));
            assertEquals("admin", fluxzero.queryGateway().sendAndWait(new Request()));
            assertResponse(fluxzero, new Message(new Request()).addMetadata("origin", "test"), "admin");
            user = new MockUser("member");
            assertSkipped(fluxzero, new Request());
            user = new MockUser("admin");
            assertResponse(fluxzero, new Request(), "admin");
        }
    }

    @Test
    void publicPayloadRemainsAccessibleWithoutAUser() {
        try (Fluxzero fluxzero = createFluxzero(new PublicHandler())) {
            assertResponse(fluxzero, new PublicRequest(), "handled");
        }
    }

    private Fluxzero createFluxzero(Object... handlers) {
        return createFluxzero(DefaultFluxzero.builder(), handlers);
    }

    private Fluxzero createFluxzero(FluxzeroBuilder builder, Object... handlers) {
        Fluxzero fluxzero = builder.registerUserProvider(new FixedUserProvider(() -> user))
                .replacePropertySource(source -> new SimplePropertySource(Map.of(SHUTDOWN_TIMEOUT_PROPERTY, "0"))
                        .andThen(source))
                .build(LocalClient.newInstance(null));
        for (Object handler : handlers) {
            fluxzero.commandGateway().registerHandler(handler);
            fluxzero.queryGateway().registerHandler(handler);
        }
        return fluxzero;
    }

    private void assertResponse(Fluxzero fluxzero, Object request, String expected) {
        var command = fluxzero.commandGateway().send(request);
        assertTrue(command.isDone(), "The authorized local command must produce a result");
        assertEquals(expected, command.join());
        var query = fluxzero.queryGateway().send(request);
        assertTrue(query.isDone(), "The authorized local query must produce a result");
        assertEquals(expected, query.join());
        assertNull(User.getCurrent(), "Handling must restore the caller's user context");
    }

    private void assertSkipped(Fluxzero fluxzero, Object request) {
        int previousInvocations = invocations.size();
        var command = fluxzero.commandGateway().send(request);
        var query = fluxzero.queryGateway().send(request);
        try {
            assertFalse(command.isDone(), "A rejected command must not produce a response");
            assertFalse(query.isDone(), "A rejected query must not produce a response");
            assertEquals(previousInvocations, invocations.size());
            assertNull(User.getCurrent());
        } finally {
            command.cancel(false);
            query.cancel(false);
        }
    }

    private String recordInvocation(String value) {
        invocations.add(value);
        return value;
    }

    record Request() {}

    @RequiresAnyRole(value = "admin", throwIfUnauthorized = false)
    record SelfHandlingRequest() {
        @HandleCommand
        @HandleQuery
        String handle() {
            return "self";
        }
    }

    @RequiresAnyRole
    record EmptyRoleRequest() {}

    @NoUserRequired
    record PublicRequest() {}

    @LocalHandler
    class AdminHandler {
        @HandleCommand
        @HandleQuery
        @RequiresAnyRole(value = "admin", throwIfUnauthorized = false)
        String handle(Request request) {
            return recordInvocation("admin");
        }
    }

    @LocalHandler
    class MemberHandler {
        @HandleCommand
        @HandleQuery
        @ForbidsAnyRole(value = "admin", throwIfUnauthorized = false)
        String handle(Request request) {
            return recordInvocation("member");
        }
    }

    @LocalHandler
    class AnonymousHandler {
        @HandleCommand
        @HandleQuery
        @ForbidsUser(throwIfUnauthorized = false)
        String handle(Request request) {
            return recordInvocation("anonymous");
        }
    }

    @LocalHandler
    class ThrowingAnonymousHandler {
        @HandleCommand
        @HandleQuery
        @ForbidsUser
        String handle(Request request) {
            return recordInvocation("anonymous");
        }
    }

    @LocalHandler
    class PublicHandler {
        @HandleCommand
        @HandleQuery
        String handle(Object request) {
            return recordInvocation("handled");
        }
    }
}
