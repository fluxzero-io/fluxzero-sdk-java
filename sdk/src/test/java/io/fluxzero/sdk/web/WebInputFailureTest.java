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

package io.fluxzero.sdk.web;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.test.Then;
import io.fluxzero.sdk.test.When;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WebInputFailureTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void malformedTypedInputReturnsBadRequestWithoutInvokingEndpoint(boolean async, boolean explicitUserProvider) {
        var processed = new AtomicReference<CompletableFuture<Void>>();
        var mappedResult = new AtomicReference<Object>();
        var builder = DefaultFluxzero.builder().replaceWebResponseMapper((result, metadata) -> {
            mappedResult.set(result);
            return new DefaultWebResponseMapper().map(result, metadata);
        }).configureDefaultConsumer(MessageType.WEBREQUEST,
                c -> c.toBuilder().awaitAsyncResults(true).batchInterceptor((consumer, tracker) -> batch -> {
                    var completion = processed.get();
                    consumer.accept(batch);
                    if (completion != null && !batch.getMessages().isEmpty()) {
                        completion.complete(null);
                    }
                }).build());
        if (explicitUserProvider) {
            builder.registerUserProvider(new FixedUserProvider(new MockUser()));
        }
        var endpoint = new Endpoint();
        var fixture = async ? TestFixture.createAsync(builder, endpoint) : TestFixture.create(builder, endpoint);
        try (var app = fixture.getFluxzero()) {
            send(fixture, async, processed, "{\"date\":\"2026-01-20\",\"count\":1}")
                    .expectWebResponse(response -> response.getStatus() == 200 && "accepted".equals(response.getPayload()))
                    .expectNoErrors();
            assertEquals(1, endpoint.invocations.get());
            var responses = new ArrayList<Object>();
            responses.add(WebResponse.builder().status(200).payload("accepted").build());
            for (String body : List.of("{", "{\"date\":\"102026-01-20\",\"count\":1}",
                                       "{\"date\":\"2026-01-20\",\"count\":\"invalid\"}")) {
                responses.add(WebResponse.builder().status(400)
                                      .payload("The request could not be read. Please check its format.").build());
                send(fixture, async, processed, body).expectOnlyWebResponses(responses.toArray());
                assertInstanceOf(DeserializationException.class, mappedResult.get());
                assertEquals(1, endpoint.invocations.get());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedHandlerFailureRemainsInternalServerError(boolean async) {
        var endpoint = new Object() {
            @HandleGet("/failure")
            void fail() { throw new IllegalArgumentException("internal detail"); }
        };
        var fixture = async ? TestFixture.createAsync(endpoint) : TestFixture.create(endpoint);
        try (var app = fixture.getFluxzero()) {
            fixture.whenGet("/failure").expectError(IllegalArgumentException.class)
                    .expectWebResponse(r -> r.getStatus() == 500 && "An unexpected error occurred.".equals(r.getPayload()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidFormValueReturnsBadRequest(boolean async) {
        var endpoint = new Endpoint();
        var fixture = async ? TestFixture.createAsync(endpoint) : TestFixture.create(endpoint);
        try (var app = fixture.getFluxzero()) {
            fixture.whenWebRequest(WebRequest.post("/typed").contentType("application/x-www-form-urlencoded")
                                           .payload("date=invalid&count=1").build())
                    .expectError(DeserializationException.class)
                    .expectWebResponse(r -> r.getStatus() == 400
                            && "The request could not be read. Please check its format.".equals(r.getPayload()));
            assertEquals(0, endpoint.invocations.get());
        }
    }

    private static Then<?> send(When phase,
                                                      boolean async, AtomicReference<CompletableFuture<Void>> processed, String body) {
        if (!async) {
            return phase.whenWebRequest(request(body));
        }
        // Match the proxy wire envelope: raw JSON bytes have no Java payload type.
        return phase.whenApplying(fc -> {
            var request = request(body);
            var wire = new SerializedMessage(new Data<>(body.getBytes(StandardCharsets.UTF_8),
                                                        null, 0, "application/json"),
                                             request.getMetadata(), request.getMessageId(), request.getTimestamp().toEpochMilli());
            wire.setSource("web-input-test");
            wire.setRequestId(1);
            var completion = new CompletableFuture<Void>();
            processed.set(completion);
            fc.client().getGatewayClient(MessageType.WEBREQUEST).append(Guarantee.STORED, wire).join();
            completion.get(5, TimeUnit.SECONDS);
            return null;
        });
    }

    private static WebRequest request(String body) {
        return WebRequest.builder().method(HttpRequestMethod.POST).url("/typed")
                .header("Content-Type", "application/json").payload(body.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    record Input(LocalDate date, int count) {}

    static class Endpoint {
        final AtomicInteger invocations = new AtomicInteger();

        @HandlePost("/typed")
        String handle(Input input) {
            invocations.incrementAndGet();
            return "accepted";
        }
    }
}
