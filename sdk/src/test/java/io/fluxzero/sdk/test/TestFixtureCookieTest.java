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

import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.api.Test;

import java.net.HttpCookie;
import java.util.List;

class TestFixtureCookieTest {

    @Test
    void removesAllStoredCookies() {
        fixture().withCookie("preference", "dark")
                .givenPost("/cookies", "user")
                .withoutCookies()
                .whenGet("/cookies").expectResult(List.of());
    }

    @Test
    void removesSelectedNamesAndPreservesOthers() {
        fixture().withCookie("first", "one").withCookie("second", "two")
                .withCookie("third", "three")
                .withoutCookies("first", "third", "first", "unknown")
                .whenGet("/cookies").expectResult(List.of("second=two"));
    }

    @Test
    void removesEveryDomainAndPathForTheName() {
        var root = new HttpCookie("session", "root");
        root.setDomain("example.com");
        root.setPath("/");
        var account = new HttpCookie("session", "account");
        account.setDomain("login.example.com");
        account.setPath("/account");
        fixture().withCookie(root).withCookie(account).withCookie("preference", "dark")
                .withoutCookies("session")
                .whenGet("/cookies").expectResult(List.of("preference=dark"));
    }

    @Test
    void acceptsAnEmptyCookieStore() {
        fixture().withoutCookies("missing").withoutCookies()
                .whenGet("/cookies").expectResult(List.of());
    }

    @Test
    void keepsProcessingCookiesFromLaterAsyncResponses() {
        TestFixture.createAsync(new CookieEndpoint()).givenPost("/cookies", "first")
                .whenGet("/cookies").expectResult(List.of("session=first"))
                .andThen().withoutCookies("session")
                .whenGet("/cookies").expectResult(List.of())
                .andThen().givenPost("/cookies", "second")
                .whenGet("/cookies").expectResult(List.of("session=second"));
    }

    @Test
    void canRestoreResponseCookiesAfterRemovingThem() {
        var fixture = fixture();
        var response = fixture.whenPost("/cookies", "user").getResult(WebResponse.class);
        fixture.withoutCookies();
        response.getCookies().forEach(fixture::withCookie);
        fixture.whenGet("/cookies").expectResult(List.of("session=user"));
    }

    @Test
    void preservesFixtureCookieHeaders() {
        fixture().withCookie("session", "stored").withHeader("Cookie", "header=value")
                .withoutCookies()
                .whenGet("/cookies").expectResult(List.of("header=value"));
    }

    @Test
    void preservesRequestCookieHeaders() {
        fixture().withCookie("session", "stored").withoutCookies()
                .whenWebRequest(WebRequest.get("/cookies").header("Cookie", "request=value").build())
                .expectResult(List.of("request=value"));
    }

    private TestFixture fixture() {
        return TestFixture.create(new CookieEndpoint());
    }

    private static class CookieEndpoint {
        @HandlePost("/cookies")
        WebResponse setCookie(String value) {
            return WebResponse.builder().cookie(new HttpCookie("session", value)).build();
        }

        @HandleGet("/cookies")
        List<String> cookies(WebRequest request) {
            return request.getCookies().stream().map(cookie -> cookie.getName() + "=" + cookie.getValue()).toList();
        }
    }
}
