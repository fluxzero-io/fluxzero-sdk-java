package io.fluxzero.sdk.web;

import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

        @Test
        void testGetValidatedPathParam_valid() {
            testFixture.whenGet("/validatedPathParam/5").expectResult("validatedPathParam:5");
        }

        @Test
        void testGetValidatedPathParam_invalid() {
            testFixture.whenGet("/validatedPathParam/0").expectExceptionalResult(ValidationException.class);
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

            @HandleGet("/validatedPathParam/{id}")
            String validatedPathParam(@PathParam @NotNull @Positive Integer id) {
                return "validatedPathParam:" + id;
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

