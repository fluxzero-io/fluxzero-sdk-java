package io.fluxzero.sdk.web;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Value;
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

    @Nested
    class BodyParamTests {

        final TestFixture testFixture = TestFixture.create(new Handler());

        @Test
        void testBodyParam_defaultNames() {
            testFixture.whenPost("/bookings", java.util.Map.of(
                    "hotelId", "hotel-1",
                    "roomId", "room-2",
                    "details", java.util.Map.of("guestName", "Jane Doe", "nights", 1)))
                    .expectResult("hotel-1:room-2:Jane Doe");
        }

        @Test
        void testBodyParam_typedResult() {
            testFixture.whenPost("/typed-bookings", java.util.Map.of(
                    "hotelId", "hotel-1",
                    "roomId", "room-9",
                    "details", java.util.Map.of("guestName", "Jane Doe", "nights", 3)))
                    .expectResult(new BookingRequest(new HotelId("hotel-1"), new RoomId("room-9"),
                                                     new BookingDetails("Jane Doe", 3)));
        }

        @Test
        void testBodyParam_explicitPath() {
            testFixture.whenPost("/bookings/guest", java.util.Map.of(
                    "booking", java.util.Map.of("details", java.util.Map.of("guestName", "Nested Jane"))))
                    .expectResult("Nested Jane");
        }

        @Test
        void testBodyParamFallsBackAfterQuery() {
            TestFixture.create().whenApplying(fc -> DefaultWebRequestContext.getWebRequestContext(
                            new DeserializingMessage(
                                    WebRequest.builder().method("POST").url("/bookings").payload(java.util.Map.of(
                                            "hotelId", "hotel-from-body")).build(),
                                    MessageType.WEBREQUEST, fc.serializer()))
                    .getParameter("hotelId", WebParameterSource.QUERY, WebParameterSource.BODY)
                    .as(HotelId.class)).expectResult(new HotelId("hotel-from-body"));
        }

        static class Handler {
            @HandlePost("/bookings")
            String create(@BodyParam SomeId hotelId, @BodyParam SomeId roomId, @BodyParam BookingDetails details) {
                return hotelId.getFunctionalId() + ":" + roomId.getFunctionalId() + ":" + details.getGuestName();
            }

            @HandlePost("/typed-bookings")
            BookingRequest createTyped(@BodyParam HotelId hotelId,
                                       @BodyParam RoomId roomId,
                                       @BodyParam BookingDetails details) {
                return new BookingRequest(hotelId, roomId, details);
            }

            @HandlePost("/bookings/guest")
            String guest(@BodyParam("booking.details.guestName") String guestName) {
                return guestName;
            }
        }
    }

    @Nested
    class FormParamTests {

        final TestFixture testFixture = TestFixture.create(new Handler());

        @Test
        void testFormParam_rawStringBody_absoluteUrl() {
            testFixture.whenApplying(fc -> DefaultWebRequestContext.getWebRequestContext(
                            new DeserializingMessage(
                                    WebRequest.post("https://mock-google.test/token")
                                            .contentType("application/x-www-form-urlencoded")
                                            .body("grant_type=authorization_code&code=google-code"
                                                  + "&client_id=google-managed-client"
                                                  + "&client_secret=google-managed-secret"
                                                  + "&redirect_uri=https%3A%2F%2Fauth.fluxzero.test"
                                                  + "%2Flogin%2Fcallback%2Fgoogle")
                                            .build(),
                                    MessageType.WEBREQUEST, fc.serializer()))
                    .getFormParameter("client_id").as(String.class))
                    .expectResult("google-managed-client");
        }

        @Test
        void testFormParam_rawStringBody_viaWebRequestGatewaySendAndWait() {
            testFixture.whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                                    WebRequest.post("https://mock-google.test/token")
                                            .contentType("application/x-www-form-urlencoded")
                                            .body("grant_type=authorization_code&code=google-code"
                                                  + "&client_id=google-managed-client"
                                                  + "&client_secret=google-managed-secret"
                                                  + "&redirect_uri=https%3A%2F%2Fauth.fluxzero.test"
                                                  + "%2Flogin%2Fcallback%2Fgoogle")
                                            .build())
                            .getPayloadAs(String.class))
                    .expectResult("google-managed-client|google-managed-secret|https://auth.fluxzero.test/login"
                                  + "/callback/google|google-code");
        }

        @Path("https://mock-google.test")
        static class Handler {
            @HandlePost("/token")
            String token(@FormParam("client_id") String clientId,
                         @FormParam("client_secret") String clientSecret,
                         @FormParam("redirect_uri") String redirectUri,
                         @FormParam("code") String code) {
                return clientId + "|" + clientSecret + "|" + redirectUri + "|" + code;
            }
        }
    }

    static class SomeId extends Id<Object> {
        public SomeId(String functionalId) {
            super(functionalId);
        }
    }

    static class HotelId extends Id<Object> {
        public HotelId(String functionalId) {
            super(functionalId);
        }
    }

    static class RoomId extends Id<Object> {
        public RoomId(String functionalId) {
            super(functionalId);
        }
    }

    @Value
    static class BookingRequest {
        HotelId hotelId;
        RoomId roomId;
        BookingDetails details;
    }

    @Value
    static class BookingDetails {
        String guestName;
        int nights;
    }

}
