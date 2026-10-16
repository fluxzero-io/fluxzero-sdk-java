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

package io.fluxzero.sdk.givenwhenthen.packagepolicy;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.silent.SilentRequest;
import io.fluxzero.sdk.test.Given;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.ForbidsAnyRole;
import io.fluxzero.sdk.tracking.handling.authentication.ForbidsUser;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SilentAuthorizationTest {
    private MockUser user;

    @Test
    void payloadPackagePolicySkipsUnauthorizedRequests() {
        verifyPayloadRoles(fixture(new RecordingHandler()), new SilentRequest());
    }

    @Test
    void payloadPackagePolicySkipsUnauthorizedRequestsAsynchronously() {
        verifyPayloadRoles(fixture(new RecordingHandler()).async(), new SilentRequest());
    }

    @Test
    void selfHandlingPayloadSkipsUnauthorizedRequests() {
        verifyPayloadRoles(fixture(), new SelfHandlingRequest());
    }

    @Test
    void selfHandlingPayloadSkipsUnauthorizedRequestsInAsyncFixture() {
        verifyPayloadRoles(fixture().async(), new SelfHandlingRequest());
    }

    @Test
    void handlerRolesAreRecheckedWhenUsersChange() {
        verifyHandlerRoles(fixture(new AdminHandler(), new MemberHandler()));
    }

    @Test
    void handlerRolesAreRecheckedWhenUsersChangeAsynchronously() {
        verifyHandlerRoles(fixture(new AdminHandler(), new MemberHandler()).async());
    }

    @Test
    void anonymousHandlerIsNotReusedForAuthenticatedUsers() {
        verifyAnonymousHandler(fixture(new AnonymousHandler(), new AuthenticatedHandler()));
    }

    @Test
    void anonymousHandlerIsNotReusedForAuthenticatedUsersAsynchronously() {
        verifyAnonymousHandler(fixture(new AnonymousHandler(), new AuthenticatedHandler()).async());
    }

    @Test
    void anonymousPayloadRejectsAuthenticatedUsersAfterSuccessfulRequest() {
        verifyAnonymousPayload(fixture(new RecordingHandler()));
    }

    @Test
    void anonymousPayloadRejectsAuthenticatedUsersAfterSuccessfulRequestAsynchronously() {
        verifyAnonymousPayload(fixture(new RecordingHandler()).async());
    }

    @Test
    void throwingAnonymousHandlerStillRejectsAuthenticatedUsers() {
        verifyThrowingAnonymousHandler(fixture(new ThrowingAnonymousHandler()));
    }

    @Test
    void throwingAnonymousHandlerStillRejectsAuthenticatedUsersAsynchronously() {
        verifyThrowingAnonymousHandler(fixture(new ThrowingAnonymousHandler()).async());
    }

    private void verifyPayloadRoles(TestFixture fixture, Object request) {
        Given<?> next = assertSkipped(fixture, request);
        user = new MockUser("admin");
        next = assertHandled(next, request, "handled");
        user = new MockUser("member");
        next = assertSkipped(next, request);
        user = new MockUser("admin");
        assertHandled(next, request, "handled");
    }

    private void verifyHandlerRoles(TestFixture fixture) {
        user = new MockUser("admin");
        Given<?> next = assertHandled(fixture, new PublicOperations.Request(), "admin");
        user = new MockUser("member");
        next = assertHandled(next, new PublicOperations.Request(), "member");
        user = null;
        next = assertSkipped(next, new PublicOperations.Request());
        user = new MockUser("admin");
        assertHandled(next, new PublicOperations.Request(), "admin");
    }

    private void verifyAnonymousHandler(TestFixture fixture) {
        Given<?> next = assertHandled(fixture, new PublicOperations.Request(), "anonymous");
        user = new MockUser("member");
        next = assertHandled(next, new PublicOperations.Request(), "authenticated");
        user = null;
        assertHandled(next, new PublicOperations.Request(), "anonymous");
    }

    private void verifyAnonymousPayload(TestFixture fixture) {
        Given<?> next = assertHandled(fixture, new AnonymousRequest(), "handled");
        user = new MockUser("member");
        next = assertSkipped(next, new AnonymousRequest());
        user = null;
        assertHandled(next, new AnonymousRequest(), "handled");
    }

    private void verifyThrowingAnonymousHandler(TestFixture fixture) {
        Given<?> next = assertHandled(fixture, new PublicOperations.Request(), "anonymous");
        user = new MockUser("member");
        next.whenCommand(new PublicOperations.Request())
                .expectExceptionalResult(UnauthorizedException.class).expectNoEvents()
                .andThen().whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthorizedException.class).expectNoEvents();
    }

    private Given<?> assertHandled(Given<?> fixture, Object request, String result) {
        return fixture.whenCommand(request).expectResult(result).expectOnlyEvents(result).expectNoErrors()
                .andThen().whenQuery(request).expectResult(result).expectOnlyEvents(result).expectNoErrors()
                .andThen();
    }

    private Given<?> assertSkipped(Given<?> fixture, Object request) {
        var responses = new ArrayList<CompletableFuture<?>>();
        return fixture.whenExecuting(fc -> {
                    responses.add(fc.commandGateway().send(request));
                    responses.add(fc.queryGateway().send(request));
                }).expectNoEvents().expectNoErrors()
                .expectThat(fc -> {
                    try {
                        responses.forEach(response -> assertFalse(response.isDone(),
                                "A silently rejected request must not produce a response"));
                    } finally {
                        responses.forEach(response -> response.cancel(false));
                    }
                })
                .andThen();
    }

    private TestFixture fixture(Object... handlers) {
        return TestFixture.create(DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(() -> user)),
                                  handlers);
    }

    private static String recordInvocation(String result) {
        Fluxzero.publishEvent(result);
        return result;
    }

    @RequiresAnyRole(value = "admin", throwIfUnauthorized = false)
    record SelfHandlingRequest() {
        @HandleCommand
        @HandleQuery
        String handle() {
            return recordInvocation("handled");
        }
    }

    @ForbidsUser(throwIfUnauthorized = false)
    record AnonymousRequest() {}

    static class RecordingHandler {
        @HandleCommand
        @HandleQuery
        String handle(Object request) {
            return recordInvocation("handled");
        }
    }

    @RequiresAnyRole(value = "admin", throwIfUnauthorized = false)
    static class AdminHandler {
        @HandleCommand
        @HandleQuery
        String handle(PublicOperations.Request request) {
            return recordInvocation("admin");
        }
    }

    @ForbidsAnyRole(value = "admin", throwIfUnauthorized = false)
    static class MemberHandler {
        @HandleCommand
        @HandleQuery
        String handle(PublicOperations.Request request) {
            return recordInvocation("member");
        }
    }

    static class AnonymousHandler {
        @HandleCommand
        @HandleQuery
        @ForbidsUser(throwIfUnauthorized = false)
        String handle(PublicOperations.Request request) {
            return recordInvocation("anonymous");
        }
    }

    static class AuthenticatedHandler {
        @HandleCommand
        @HandleQuery
        @RequiresUser(throwIfUnauthorized = false)
        String handle(PublicOperations.Request request) {
            return recordInvocation("authenticated");
        }
    }

    static class ThrowingAnonymousHandler {
        @HandleCommand
        @HandleQuery
        @ForbidsUser
        String handle(PublicOperations.Request request) {
            return recordInvocation("anonymous");
        }
    }
}
