package io.fluxzero.sdk.givenwhenthen;

import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.HandleWeb;
import io.fluxzero.sdk.web.PathParam;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.POST;

class GivenWhenThenWebTest {

    @Nested
    class WhenTests {
        private final TestFixture testFixture = TestFixture.create(new Handler());

        @Test
        void testPostString_substitutePlaceholders() {
            testFixture.whenPost("/string", "body")
                    .expectResult("val1")
                    .andThen()
                    .whenPost("/followUp/{var1}", null)
                    .expectResult("val1val2")
                    .andThen()
                    .whenPost("/followUp/{var1}/{val2}", null)
                    .expectResult("val1val1val2");
        }

        @Test
        void testPostString_substituteMultiplePlaceholders() {
            testFixture.whenPost("/string", "body")
                    .asWebParameter("var1")
                    .andThen()
                    .whenPost("/string2", "body")
                    .asWebParameter("var2")
                    .andThen()
                    .whenPost("/followUp/{var1}/{var2}")
                    .expectResult("val1val2");
        }

        private static class Handler {
            @HandleWeb(value = "/get", method = GET)
            String get() {
                return "get";
            }

            @HandleWeb(value = "/string", method = POST)
            String post() {
                return "val1";
            }

            @HandleWeb(value = "/string2", method = POST)
            String post2() {
                return "val2";
            }

            @HandlePost("/error")
            void postForError(String body) {
                throw new IllegalCommandException("error: " + body);
            }

            @HandlePost("/followUp/{var1}")
            String followUp(@PathParam String var1) {
                return var1 + "val2";
            }

            @HandlePost("/followUp/{var1}/{var2}")
            String followUp2(@PathParam String var1, @PathParam String var2) {
                return var1 + var2;
            }
        }
    }
}