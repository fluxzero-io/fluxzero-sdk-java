package io.fluxzero.sdk.web;

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.DefaultRequestHandler;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Value;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        final TestFixture testFixture = TestFixture.createAsync(new Handler());

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

        @Test
        void testChunkedBodyParamWaitsForFinalChunkWithoutBlockingTracker() {
            AtomicReference<String> handled = new AtomicReference<>();
            CountDownLatch handledLatch = new CountDownLatch(1);
            TestFixture.createAsync(new Object() {
                @HandlePost("/bodyParam/chunked")
                String post(@BodyParam("message") String message) {
                    handled.set(message);
                    handledLatch.countDown();
                    return message;
                }

                @HandlePost("/ping")
                String ping() {
                    return "pong";
                }
            }).whenApplying(fc -> {
                WebRequest request = WebRequest.builder().method("POST").url("/bodyParam/chunked").payload(new byte[0])
                        .header("Content-Type", "application/json").build();
                SerializedMessage firstChunk = new SerializedMessage(
                        new Data<>("{\"message\":\"hello ".getBytes(), byte[].class.getName(), 0,
                                   "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                SerializedMessage secondChunk = new SerializedMessage(
                        new Data<>("world\"}".getBytes(), byte[].class.getName(), 0, "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "true").with(HasMetadata.FIRST_CHUNK,
                                                                                           "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                try (DefaultRequestHandler requestHandler = new DefaultRequestHandler(fc.client(), MessageType.WEBRESPONSE,
                                                                                      Duration.ofSeconds(5),
                                                                                      "chunked-bodyparam-test")) {
                    firstChunk.setSegment(ConsistentHashing.computeSegment(firstChunk.getMessageId()));
                    AtomicReference<CompletableFuture<Void>> firstDispatch =
                            new AtomicReference<>(CompletableFuture.completedFuture(null));
                    CompletableFuture<SerializedMessage> responseFuture = requestHandler.sendRequest(
                            firstChunk, message -> firstDispatch.set(fc.client().getGatewayClient(MessageType.WEBREQUEST)
                                    .append(Guarantee.SENT, message)), Duration.ofSeconds(5), null);
                    firstDispatch.get().get();
                    assertFalse(handledLatch.await(50, TimeUnit.MILLISECONDS));
                    assertNull(handled.get());
                    assertEquals("pong", fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/ping").build()).getPayloadAs(String.class));
                    secondChunk.setRequestId(firstChunk.getRequestId());
                    secondChunk.setSource(firstChunk.getSource());
                    secondChunk.setSegment(firstChunk.getSegment());
                    fc.client().getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.SENT, secondChunk).get();
                    responseFuture.get();
                    assertTrue(handledLatch.await(1, TimeUnit.SECONDS));
                    assertEquals("hello world", handled.get());
                }
                return null;
            }).expectNoErrors();
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

        final TestFixture testFixture = TestFixture.createAsync(new Handler());

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

        @Test
        void testFormParam_objectBody_viaWebRequestGatewaySendAndWait() {
            testFixture.whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                                    WebRequest.post("https://mock-google.test/token-object")
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

        @Test
        void testFormParam_stillPrefersMatchingFieldOverWholeFormFallback() {
            testFixture.whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                                    WebRequest.post("https://mock-google.test/token-id")
                                            .contentType("application/x-www-form-urlencoded")
                                            .body("clientId=google-managed-client")
                                            .build())
                            .getPayloadAs(String.class))
                    .expectResult("google-managed-client");
        }

        @Test
        void testFormParam_multipartBody_viaWebRequestGatewaySendAndWait() {
            String boundary = "fluxzeroBoundary";
            String body = "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"client_id\"\r\n\r\n"
                          + "google-managed-client\r\n"
                          + "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"client_secret\"\r\n\r\n"
                          + "google-managed-secret\r\n"
                          + "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"redirect_uri\"\r\n\r\n"
                          + "https://auth.fluxzero.test/login/callback/google\r\n"
                          + "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"code\"\r\n\r\n"
                          + "google-code\r\n"
                          + "--" + boundary + "--\r\n";
            testFixture.whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                                    WebRequest.post("https://mock-google.test/token")
                                            .contentType("multipart/form-data; boundary=" + boundary)
                                            .body(body)
                                            .build())
                            .getPayloadAs(String.class))
                    .expectResult("google-managed-client|google-managed-secret|https://auth.fluxzero.test/login"
                                  + "/callback/google|google-code");
        }

        @Test
        void testChunkedFormParamWaitsForFinalChunkWithoutBlockingTracker() {
            AtomicReference<String> handled = new AtomicReference<>();
            CountDownLatch handledLatch = new CountDownLatch(1);
            TestFixture.createAsync(new Object() {
                @HandlePost("/formParam/chunked")
                String post(@FormParam("message") String message) {
                    handled.set(message);
                    handledLatch.countDown();
                    return message;
                }

                @HandlePost("/ping")
                String ping() {
                    return "pong";
                }
            }).whenApplying(fc -> {
                WebRequest request = WebRequest.builder().method("POST").url("/formParam/chunked").payload(new byte[0])
                        .header("Content-Type", "application/x-www-form-urlencoded").build();
                SerializedMessage firstChunk = new SerializedMessage(
                        new Data<>("message=hello+".getBytes(), byte[].class.getName(), 0, "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                SerializedMessage secondChunk = new SerializedMessage(
                        new Data<>("world".getBytes(), byte[].class.getName(), 0, "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "true").with(HasMetadata.FIRST_CHUNK,
                                                                                           "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                try (DefaultRequestHandler requestHandler = new DefaultRequestHandler(fc.client(), MessageType.WEBRESPONSE,
                                                                                      Duration.ofSeconds(5),
                                                                                      "chunked-formparam-test")) {
                    firstChunk.setSegment(ConsistentHashing.computeSegment(firstChunk.getMessageId()));
                    AtomicReference<CompletableFuture<Void>> firstDispatch =
                            new AtomicReference<>(CompletableFuture.completedFuture(null));
                    CompletableFuture<SerializedMessage> responseFuture = requestHandler.sendRequest(
                            firstChunk, message -> firstDispatch.set(fc.client().getGatewayClient(MessageType.WEBREQUEST)
                                    .append(Guarantee.SENT, message)), Duration.ofSeconds(5), null);
                    firstDispatch.get().get();
                    assertFalse(handledLatch.await(50, TimeUnit.MILLISECONDS));
                    assertNull(handled.get());
                    assertEquals("pong", fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/ping").build()).getPayloadAs(String.class));
                    secondChunk.setRequestId(firstChunk.getRequestId());
                    secondChunk.setSource(firstChunk.getSource());
                    secondChunk.setSegment(firstChunk.getSegment());
                    fc.client().getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.SENT, secondChunk).get();
                    responseFuture.get();
                    assertTrue(handledLatch.await(1, TimeUnit.SECONDS));
                    assertEquals("hello world", handled.get());
                }
                return null;
            }).expectNoErrors();
        }

        @Test
        void testChunkedFormObjectParamWaitsForFinalChunkWithoutBlockingTracker() {
            AtomicReference<String> handled = new AtomicReference<>();
            CountDownLatch handledLatch = new CountDownLatch(1);
            TestFixture.createAsync(new Object() {
                @HandlePost("/formParam/object-chunked")
                String post(TokenForm form) {
                    handled.set(form.clientId + "|" + form.clientSecret + "|" + form.redirectUri + "|" + form.code);
                    handledLatch.countDown();
                    return handled.get();
                }

                @HandlePost("/ping")
                String ping() {
                    return "pong";
                }
            }).whenApplying(fc -> {
                WebRequest request = WebRequest.builder().method("POST").url("/formParam/object-chunked")
                        .payload(new byte[0]).header("Content-Type", "application/x-www-form-urlencoded").build();
                SerializedMessage firstChunk = new SerializedMessage(
                        new Data<>("client_id=google-managed-client&client_secret=google-managed-secret"
                                   .getBytes(), byte[].class.getName(), 0, "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                SerializedMessage secondChunk = new SerializedMessage(
                        new Data<>(("&redirect_uri=https%3A%2F%2Fauth.fluxzero.test%2Flogin%2Fcallback%2Fgoogle"
                                    + "&code=google-code").getBytes(), byte[].class.getName(), 0,
                                   "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "true").with(HasMetadata.FIRST_CHUNK,
                                                                                           "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                try (DefaultRequestHandler requestHandler = new DefaultRequestHandler(fc.client(), MessageType.WEBRESPONSE,
                                                                                      Duration.ofSeconds(5),
                                                                                      "chunked-form-object-test")) {
                    firstChunk.setSegment(ConsistentHashing.computeSegment(firstChunk.getMessageId()));
                    AtomicReference<CompletableFuture<Void>> firstDispatch =
                            new AtomicReference<>(CompletableFuture.completedFuture(null));
                    CompletableFuture<SerializedMessage> responseFuture = requestHandler.sendRequest(
                            firstChunk, message -> firstDispatch.set(fc.client().getGatewayClient(MessageType.WEBREQUEST)
                                    .append(Guarantee.SENT, message)), Duration.ofSeconds(5), null);
                    firstDispatch.get().get();
                    assertFalse(handledLatch.await(50, TimeUnit.MILLISECONDS));
                    assertNull(handled.get());
                    assertEquals("pong", fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/ping").build()).getPayloadAs(String.class));
                    secondChunk.setRequestId(firstChunk.getRequestId());
                    secondChunk.setSource(firstChunk.getSource());
                    secondChunk.setSegment(firstChunk.getSegment());
                    fc.client().getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.SENT, secondChunk).get();
                    responseFuture.get();
                    assertTrue(handledLatch.await(1, TimeUnit.SECONDS));
                    assertEquals("google-managed-client|google-managed-secret|https://auth.fluxzero.test/login"
                                 + "/callback/google|google-code", handled.get());
                }
                return null;
            }).expectNoErrors();
        }

        @Test
        void testChunkedMultipartFormParamWaitsForFinalChunkWithoutBlockingTracker() {
            AtomicReference<String> handled = new AtomicReference<>();
            CountDownLatch handledLatch = new CountDownLatch(1);
            TestFixture.createAsync(new Object() {
                @HandlePost("/formParam/multipart")
                String post(@FormParam("message") String message) {
                    handled.set(message);
                    handledLatch.countDown();
                    return message;
                }

                @HandlePost("/ping")
                String ping() {
                    return "pong";
                }
            }).whenApplying(fc -> {
                String boundary = "chunkedBoundary";
                WebRequest request = WebRequest.builder().method("POST").url("/formParam/multipart").payload(new byte[0])
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary).build();
                SerializedMessage firstChunk = new SerializedMessage(
                        new Data<>(("--" + boundary + "\r\n"
                                    + "Content-Disposition: form-data; name=\"message\"\r\n\r\n"
                                    + "hello ").getBytes(), byte[].class.getName(), 0, "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                SerializedMessage secondChunk = new SerializedMessage(
                        new Data<>(("world\r\n--" + boundary + "--\r\n").getBytes(), byte[].class.getName(), 0,
                                   "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "true").with(HasMetadata.FIRST_CHUNK,
                                                                                           "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                try (DefaultRequestHandler requestHandler = new DefaultRequestHandler(fc.client(), MessageType.WEBRESPONSE,
                                                                                      Duration.ofSeconds(5),
                                                                                      "chunked-multipart-formparam-test")) {
                    firstChunk.setSegment(ConsistentHashing.computeSegment(firstChunk.getMessageId()));
                    AtomicReference<CompletableFuture<Void>> firstDispatch =
                            new AtomicReference<>(CompletableFuture.completedFuture(null));
                    CompletableFuture<SerializedMessage> responseFuture = requestHandler.sendRequest(
                            firstChunk, message -> firstDispatch.set(fc.client().getGatewayClient(MessageType.WEBREQUEST)
                                    .append(Guarantee.SENT, message)), Duration.ofSeconds(5), null);
                    firstDispatch.get().get();
                    assertFalse(handledLatch.await(50, TimeUnit.MILLISECONDS));
                    assertNull(handled.get());
                    assertEquals("pong", fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/ping").build()).getPayloadAs(String.class));
                    secondChunk.setRequestId(firstChunk.getRequestId());
                    secondChunk.setSource(firstChunk.getSource());
                    secondChunk.setSegment(firstChunk.getSegment());
                    fc.client().getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.SENT, secondChunk).get();
                    responseFuture.get();
                    assertTrue(handledLatch.await(1, TimeUnit.SECONDS));
                    assertEquals("hello world", handled.get());
                }
                return null;
            }).expectNoErrors();
        }

        @Test
        void testFormParam_multipartFilePart_viaWebRequestGatewaySendAndWait() {
            String boundary = "fileBoundary";
            String fileContents = "%PDF-1.7\nfluxzero test file\n";
            String body = "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"document\"; filename=\"document.pdf\"\r\n"
                          + "Content-Type: application/pdf\r\n\r\n"
                          + fileContents + "\r\n"
                          + "--" + boundary + "--\r\n";
            TestFixture.createAsync(new Object() {
                @HandlePost("/multipart/file")
                String post(@FormParam("document") MultipartFormPart document,
                            @FormParam("document") InputStream stream) throws Exception {
                    return document.getFilename() + "|" + document.getContentType() + "|"
                           + Base64.getEncoder().encodeToString(stream.readAllBytes());
                }
            }).whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/multipart/file")
                                    .contentType("multipart/form-data; boundary=" + boundary)
                                    .body(body.getBytes(StandardCharsets.ISO_8859_1))
                                    .build())
                    .getPayloadAs(String.class))
                    .expectResult("document.pdf|application/pdf|"
                                  + Base64.getEncoder().encodeToString(
                                          fileContents.getBytes(StandardCharsets.ISO_8859_1)));
        }

        @Test
        void testFormParam_multipartFilePartPreservesBoundaryTextInsideFile() {
            String boundary = "fileBoundary";
            String fileContents = "prefix--" + boundary + "--suffix";
            String body = "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"document\"; filename=\"document.pdf\"\r\n"
                          + "Content-Type: application/pdf\r\n\r\n"
                          + fileContents + "\r\n"
                          + "--" + boundary + "--\r\n";
            TestFixture.createAsync(new Object() {
                @HandlePost("/multipart/file-with-boundary-text")
                String post(@FormParam("document") byte[] document) {
                    return new String(document, StandardCharsets.ISO_8859_1);
                }
            }).whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/multipart/file-with-boundary-text")
                                    .contentType("multipart/form-data; boundary=" + boundary)
                                    .body(body.getBytes(StandardCharsets.ISO_8859_1))
                                    .build())
                    .getPayloadAs(String.class))
                    .expectResult(fileContents);
        }

        @Test
        void testChunkedMultipartFilePartWaitsForFinalChunkWithoutBlockingTracker() {
            byte[] fileBytes = "%PDF-1.7\nchunked file\n".getBytes(StandardCharsets.ISO_8859_1);
            String expected = Base64.getEncoder().encodeToString(fileBytes);
            AtomicReference<String> handled = new AtomicReference<>();
            CountDownLatch handledLatch = new CountDownLatch(1);
            TestFixture.createAsync(new Object() {
                @HandlePost("/formParam/multipart-file")
                String post(@FormParam("document") byte[] document) {
                    handled.set(Base64.getEncoder().encodeToString(document));
                    handledLatch.countDown();
                    return handled.get();
                }

                @HandlePost("/ping")
                String ping() {
                    return "pong";
                }
            }).whenApplying(fc -> {
                String boundary = "chunkedFileBoundary";
                WebRequest request = WebRequest.builder().method("POST").url("/formParam/multipart-file")
                        .payload(new byte[0]).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .build();
                SerializedMessage firstChunk = new SerializedMessage(
                        new Data<>(("--" + boundary + "\r\n"
                                    + "Content-Disposition: form-data; name=\"document\"; filename=\"document.pdf\"\r\n"
                                    + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1),
                                   byte[].class.getName(), 0, "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                SerializedMessage secondChunk = new SerializedMessage(
                        new Data<>((new String(fileBytes, StandardCharsets.ISO_8859_1) + "\r\n--" + boundary
                                    + "--\r\n").getBytes(StandardCharsets.ISO_8859_1), byte[].class.getName(), 0,
                                   "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "true").with(HasMetadata.FIRST_CHUNK,
                                                                                           "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                try (DefaultRequestHandler requestHandler = new DefaultRequestHandler(fc.client(), MessageType.WEBRESPONSE,
                                                                                      Duration.ofSeconds(5),
                                                                                      "chunked-multipart-file-test")) {
                    firstChunk.setSegment(ConsistentHashing.computeSegment(firstChunk.getMessageId()));
                    AtomicReference<CompletableFuture<Void>> firstDispatch =
                            new AtomicReference<>(CompletableFuture.completedFuture(null));
                    CompletableFuture<SerializedMessage> responseFuture = requestHandler.sendRequest(
                            firstChunk, message -> firstDispatch.set(fc.client().getGatewayClient(MessageType.WEBREQUEST)
                                    .append(Guarantee.SENT, message)), Duration.ofSeconds(5), null);
                    firstDispatch.get().get();
                    assertFalse(handledLatch.await(50, TimeUnit.MILLISECONDS));
                    assertNull(handled.get());
                    assertEquals("pong", fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/ping").build()).getPayloadAs(String.class));
                    secondChunk.setRequestId(firstChunk.getRequestId());
                    secondChunk.setSource(firstChunk.getSource());
                    secondChunk.setSegment(firstChunk.getSegment());
                    fc.client().getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.SENT, secondChunk).get();
                    responseFuture.get();
                    assertTrue(handledLatch.await(1, TimeUnit.SECONDS));
                    assertEquals(expected, handled.get());
                }
                return null;
            }).expectNoErrors();
        }

        @Test
        void testFormParam_multipleMultipartFilePartsBySameKey() {
            String boundary = "multiFileBoundary";
            String body = "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"document\"; filename=\"one.pdf\"\r\n"
                          + "Content-Type: application/pdf\r\n\r\n"
                          + "first-file\r\n"
                          + "--" + boundary + "\r\n"
                          + "Content-Disposition: form-data; name=\"document\"; filename=\"two.pdf\"\r\n"
                          + "Content-Type: application/pdf\r\n\r\n"
                          + "second-file\r\n"
                          + "--" + boundary + "--\r\n";
            TestFixture.createAsync(new Object() {
                @HandlePost("/multipart/files")
                String post(@FormParam("document") List<MultipartFormPart> documents) {
                    return documents.stream()
                            .map(document -> document.getFilename() + ":" + document.asString(StandardCharsets.ISO_8859_1))
                            .reduce((a, b) -> a + "|" + b).orElse("");
                }
            }).whenApplying(fc -> fc.webRequestGateway().sendAndWait(
                            WebRequest.post("/multipart/files")
                                    .contentType("multipart/form-data; boundary=" + boundary)
                                    .body(body.getBytes(StandardCharsets.ISO_8859_1))
                                    .build())
                    .getPayloadAs(String.class))
                    .expectResult("one.pdf:first-file|two.pdf:second-file");
        }

        @Test
        void testChunkedMultipartMultipleFilePartsBySameKey() {
            AtomicReference<String> handled = new AtomicReference<>();
            CountDownLatch handledLatch = new CountDownLatch(1);
            TestFixture.createAsync(new Object() {
                @HandlePost("/multipart/multi-file-chunked")
                String post(@FormParam("document") List<byte[]> documents) {
                    handled.set(documents.stream().map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
                                        .reduce((a, b) -> a + "|" + b).orElse(""));
                    handledLatch.countDown();
                    return handled.get();
                }
            }).whenApplying(fc -> {
                String boundary = "chunkedMultiBoundary";
                WebRequest request = WebRequest.builder().method("POST").url("/multipart/multi-file-chunked")
                        .payload(new byte[0]).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .build();
                SerializedMessage firstChunk = new SerializedMessage(
                        new Data<>(("--" + boundary + "\r\n"
                                    + "Content-Disposition: form-data; name=\"document\"; filename=\"one.pdf\"\r\n"
                                    + "Content-Type: application/pdf\r\n\r\n"
                                    + "first-file\r\n"
                                    + "--" + boundary + "\r\n"
                                    + "Content-Disposition: form-data; name=\"document\"; filename=\"two.pdf\"\r\n"
                                    + "Content-Type: application/pdf\r\n\r\n")
                                           .getBytes(StandardCharsets.ISO_8859_1), byte[].class.getName(), 0,
                                   "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                SerializedMessage secondChunk = new SerializedMessage(
                        new Data<>(("second-file\r\n--" + boundary + "--\r\n")
                                           .getBytes(StandardCharsets.ISO_8859_1), byte[].class.getName(), 0,
                                   "application/octet-stream"),
                        request.getMetadata().with(HasMetadata.FINAL_CHUNK, "true").with(HasMetadata.FIRST_CHUNK,
                                                                                           "false"),
                        request.getMessageId(), request.getTimestamp().toEpochMilli());
                try (DefaultRequestHandler requestHandler = new DefaultRequestHandler(fc.client(), MessageType.WEBRESPONSE,
                                                                                      Duration.ofSeconds(5),
                                                                                      "chunked-multipart-multi-file-test")) {
                    firstChunk.setSegment(ConsistentHashing.computeSegment(firstChunk.getMessageId()));
                    AtomicReference<CompletableFuture<Void>> firstDispatch =
                            new AtomicReference<>(CompletableFuture.completedFuture(null));
                    CompletableFuture<SerializedMessage> responseFuture = requestHandler.sendRequest(
                            firstChunk, message -> firstDispatch.set(fc.client().getGatewayClient(MessageType.WEBREQUEST)
                                    .append(Guarantee.SENT, message)), Duration.ofSeconds(5), null);
                    firstDispatch.get().get();
                    assertNull(handled.get());
                    secondChunk.setRequestId(firstChunk.getRequestId());
                    secondChunk.setSource(firstChunk.getSource());
                    secondChunk.setSegment(firstChunk.getSegment());
                    fc.client().getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.SENT, secondChunk).get();
                    responseFuture.get();
                    assertTrue(handledLatch.await(1, TimeUnit.SECONDS));
                    assertEquals("first-file|second-file", handled.get());
                }
                return null;
            }).expectNoErrors();
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

            @HandlePost("/token-object")
            String tokenObject(TokenForm form) {
                return form.clientId + "|" + form.clientSecret + "|" + form.redirectUri + "|" + form.code;
            }

            @HandlePost("/token-id")
            String tokenId(@FormParam SomeId clientId) {
                return clientId.getFunctionalId();
            }
        }

        @Value
        static class TokenForm {
            String clientId;
            String clientSecret;
            String redirectUri;
            String code;
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
