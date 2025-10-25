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

package io.fluxzero.sdk.givenwhenthen;

import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.givenwhenthen.filtercontent.FilteredContent;
import io.fluxzero.sdk.givenwhenthen.filtercontent.PackageFilteredQuery;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.Request;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import org.junit.jupiter.api.Test;

import java.util.Objects;

public class FilterContentTest {

    private MockUser user = new MockUser("standard");
    private final TestFixture testFixture = TestFixture.create(
            DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(() -> user)));

    @Test
    void testMethodLevel() {
        testFixture.whenQuery(new MethodFilteredQuery())
                .expectResult(r -> r.privateField() == null
                                   && Objects.equals(r.publicField(), "public"))
                .andThen()
                .given(fluxzero -> user = new MockUser("admin"))
                .whenQuery(new MethodFilteredQuery())
                .expectResult(r -> Objects.equals(r.privateField(), "private")
                                   && Objects.equals(r.publicField(), "public"));
    }

    @Test
    void testClassLevel() {
        testFixture.whenQuery(new ClassFilteredQuery())
                .expectResult(r -> r.privateField() == null
                                   && Objects.equals(r.publicField(), "public"));
    }

    @Test
    void testPackageLevel() {
        testFixture.whenQuery(new PackageFilteredQuery())
                .expectResult(r -> r.privateField() == null
                                   && Objects.equals(r.publicField(), "public"));
    }

    @Test
    void testMissingUser() {
        user = null;
        testFixture.whenQuery(new MethodFilteredQuery()).expectNoResult();
    }

    abstract static class FilteredQuery implements Request<FilteredContent> {
        @HandleQuery
        protected FilteredContent handle() {
            return new FilteredContent("public", "private");
        }
    }

    static class MethodFilteredQuery extends FilteredQuery {
        @HandleQuery
        @FilterContent
        protected FilteredContent handle() {
            return new FilteredContent("public", "private");
        }
    }

    @FilterContent
    static class ClassFilteredQuery extends FilteredQuery {
    }

}
