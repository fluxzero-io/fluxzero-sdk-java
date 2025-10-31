package io.fluxzero.sdk.web;

import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

class RestTests {

    @Nested
    class PathParamTests {

        final TestFixture testFixture = TestFixture.create(new Handler());

        @Test
        void testPathParam_String() {
            testFixture.whenGet("/string/123").expectResult("123");
        }

        @Test
        void testPathParam_Number() {
            testFixture.whenGet("/number/123").expectResult(123);
        }

        @Test
        void testPathParam_Non_Number_Fails() {
            testFixture.whenGet("/number/123a").expectExceptionalResult(TimeoutException.class);
        }

        @Test
        void testPathParam_Id() {
            testFixture.whenGet("/id/123").expectResult(new SomeId("123"));
        }

        @Test
        void testPathParam_Path() {
            testFixture.whenGet("/path/123").expectResult(new SomeId("123"));
        }

        static class Handler {
            @HandleGet("string/{foo}")
            Object get(@PathParam String foo) {
                return foo;
            }

            @HandleGet("number/{foo:[0-9]+}")
            Object get(@PathParam int foo) {
                return foo;
            }

            @HandleGet("id/{foo}")
            Object get(@PathParam SomeId foo) {
                return foo;
            }

            @Path("/path/{foo}")
            @HandleGet
            Object getPath(@PathParam SomeId foo) {
                return foo;
            }
        }
    }

    @Nested
    class QueryParamTests {

        final TestFixture testFixture = TestFixture.create(new Handler());

        @Test
        void testPathParam_int() {
            testFixture.whenGet("/?offset=1").expectResult(1);
        }

        static class Handler {

            @HandleGet("/")
            Object parameters(@QueryParam int offset) {
                return offset;
            }
        }
    }

    static class SomeId extends Id<Object> {
        public SomeId(String functionalId) {
            super(functionalId);
        }
    }

}

