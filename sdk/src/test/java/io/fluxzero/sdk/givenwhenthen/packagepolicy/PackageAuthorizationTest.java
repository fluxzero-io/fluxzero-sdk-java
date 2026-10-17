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

import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.ProtectedOperations;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.admin.AdminOperations;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.admin.member.MemberOperations;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.admin.open.PublicChildOperations;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.admin.operator.OperatorOperations;
import io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.inherited.InheritedOperations;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthenticatedException;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;
import org.junit.jupiter.api.Test;

class PackageAuthorizationTest {
    private MockUser user;

    @Test
    void protectedChildQueryRejectsAnonymousUserDespitePublicParent() {
        fixture().whenQuery(new ProtectedOperations.Request()).expectExceptionalResult(UnauthenticatedException.class);
    }

    @Test
    void protectedChildCommandRejectsAnonymousUserDespitePublicParent() {
        fixture().whenCommand(new ProtectedOperations.Request()).expectExceptionalResult(UnauthenticatedException.class);
    }

    @Test
    void protectedChildHandlerRejectsAnonymousUserDespitePublicPayload() {
        fixture(new ProtectedOperations.Handler()).whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthenticatedException.class);
    }

    @Test
    void protectedChildAcceptsAuthenticatedUsers() {
        user = new MockUser();
        fixture().whenQuery(new ProtectedOperations.Request()).expectResult("handled");
        fixture(new ProtectedOperations.Handler()).whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void publicParentRemainsAccessibleWithoutUser() {
        fixture().whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void publicChildOverridesProtectedParents() {
        fixture().whenQuery(new PublicChildOperations.Request()).expectResult("handled");
        fixture(new PublicChildOperations.Handler()).whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void unrelatedPackageAnnotationsDoNotStopPolicyInheritance() {
        fixture().whenQuery(new InheritedOperations.Request()).expectExceptionalResult(UnauthenticatedException.class);
        fixture(new InheritedOperations.Handler()).whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthenticatedException.class);
    }

    @Test
    void inheritedPolicyAcceptsAuthenticatedUsers() {
        user = new MockUser();
        fixture().whenQuery(new InheritedOperations.Request()).expectResult("handled");
        fixture(new InheritedOperations.Handler()).whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void packageRoleRejectsAuthenticatedUsersWithoutTheRole() {
        user = new MockUser("member");
        fixture().whenQuery(new AdminOperations.Request()).expectExceptionalResult(UnauthorizedException.class);
        fixture(new AdminOperations.Handler()).whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void packageRoleAcceptsUsersWithTheRole() {
        user = new MockUser("admin");
        fixture().whenQuery(new AdminOperations.Request()).expectResult("handled");
        fixture(new AdminOperations.Handler()).whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void childMetaRoleDoesNotAcceptTheParentRole() {
        user = new MockUser("admin");
        fixture().whenQuery(new OperatorOperations.Request()).expectExceptionalResult(UnauthorizedException.class);
        fixture(new OperatorOperations.Handler()).whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void childMetaRoleDoesNotRequireTheParentRole() {
        user = new MockUser("operator");
        fixture().whenQuery(new OperatorOperations.Request()).expectResult("handled");
        fixture(new OperatorOperations.Handler()).whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void forbiddenRoleInSamePackageStillApplies() {
        user = new MockUser("operator", "suspended");
        fixture().whenQuery(new OperatorOperations.Request()).expectExceptionalResult(UnauthorizedException.class);
        fixture(new OperatorOperations.Handler()).whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void childUserRequirementOverridesParentRoleRequirement() {
        user = new MockUser("member");
        fixture().whenQuery(new MemberOperations.Request()).expectResult("handled");
        fixture(new MemberOperations.Handler()).whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void childUserRequirementStillRejectsAnonymousUsers() {
        fixture().whenQuery(new MemberOperations.Request()).expectExceptionalResult(UnauthenticatedException.class);
        fixture(new MemberOperations.Handler()).whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthenticatedException.class);
    }

    @Test
    void payloadClassCanMakeAProtectedPackageOperationPublic() {
        fixture().whenQuery(new ProtectedOperations.PublicRequest()).expectResult("handled");
    }

    @Test
    void payloadClassCanRequireASpecificRole() {
        user = new MockUser("member");
        fixture().whenQuery(new ProtectedOperations.OwnerRequest()).expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void payloadClassAcceptsItsRequiredRole() {
        user = new MockUser("owner");
        fixture().whenQuery(new ProtectedOperations.OwnerRequest()).expectResult("handled");
    }

    @Test
    void handlerClassCanMakeAnInheritedMethodPublic() {
        fixture(new ProtectedOperations.PublicHandler()).whenQuery(new PublicOperations.Request())
                .expectResult("handled");
    }

    @Test
    void handlerClassCanRequireASpecificRole() {
        user = new MockUser("member");
        fixture(new ProtectedOperations.OwnerHandler()).whenQuery(new PublicOperations.Request())
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void handlerClassAcceptsItsRequiredRole() {
        user = new MockUser("owner");
        fixture(new ProtectedOperations.OwnerHandler()).whenQuery(new PublicOperations.Request()).expectResult("handled");
    }

    @Test
    void methodCanOverrideClassAndPackagePoliciesToAllowAnonymousUsers() {
        fixture(new ProtectedOperations.MethodOverrides()).whenQuery(new PublicOperations.Request())
                .expectResult("handled");
    }

    @Test
    void methodRoleDoesNotAcceptTheClassRole() {
        user = new MockUser("owner");
        fixture(new ProtectedOperations.MethodOverrides()).whenQuery("request")
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void methodRoleDoesNotRequireTheClassRole() {
        user = new MockUser("admin");
        fixture(new ProtectedOperations.MethodOverrides()).whenQuery("request").expectResult("handled");
    }

    @Test
    void asynchronousDispatchKeepsPolicyPrecedenceAndRechecksTheCurrentUser() {
        TestFixture.createAsync(DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(() -> user)),
                                new PublicOperations.Handler())
                .whenCommand(new ProtectedOperations.Request())
                .expectExceptionalResult(UnauthenticatedException.class)
                .andThen().given(fc -> user = new MockUser("operator"))
                .whenQuery(new OperatorOperations.Request()).expectResult("handled")
                .andThen().given(fc -> user = new MockUser("admin"))
                .whenQuery(new OperatorOperations.Request()).expectExceptionalResult(UnauthorizedException.class)
                .andThen().given(fc -> user = null)
                .whenQuery(new PublicChildOperations.Request()).expectResult("handled");
    }

    private TestFixture fixture(Object... handlers) {
        return TestFixture.create(DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(() -> user)),
                                  handlers.length == 0 ? new Object[]{new PublicOperations.Handler()} : handlers);
    }
}
