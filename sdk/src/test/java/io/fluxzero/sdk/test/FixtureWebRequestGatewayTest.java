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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.publishing.EventGateway;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

class FixtureWebRequestGatewayTest {

    private static final String ENDPOINT = "https://api.example.com/resource";

    @Test
    void synchronousFixtureRoutesNativeAsyncRequestToMockAndRetriesImmediately() {
        RetryingEndpoint endpoint = new RetryingEndpoint();

        TestFixture.create(endpoint)
                .whenApplying(fluxzero -> fluxzero.webRequestGateway().send(
                        WebRequest.get(ENDPOINT).build(), retrySettings()).join())
                .<WebResponse>expectResult(response -> response.getStatus() == 200);

        assertEquals(2, endpoint.attempts.get());
    }

    @Test
    void asynchronousFixtureRoutesNativeBlockingRequestToMockAndRetriesImmediately() {
        RetryingEndpoint endpoint = new RetryingEndpoint();

        TestFixture.createAsync(endpoint)
                .whenApplying(fluxzero -> fluxzero.webRequestGateway().sendAndWait(
                        WebRequest.get(ENDPOINT).build(), retrySettings()))
                .<WebResponse>expectResult(response -> response.getStatus() == 200);

        assertEquals(2, endpoint.attempts.get());
    }

    @Test
    void asynchronousWorkflowUsesFixtureGatewayFromHandlerContext() {
        RetryingEndpoint endpoint = new RetryingEndpoint();

        TestFixture.createAsync(endpoint, new Workflow())
                .whenCommand(new CallEndpoint())
                .expectResult(200);

        assertEquals(2, endpoint.attempts.get());
    }

    @Test
    void asynchronousWorkflowDoesNotRouteUnrelatedCallsThroughFixtureSpies() {
        RetryingEndpoint endpoint = new RetryingEndpoint();
        TestFixture fixture = TestFixture.createAsync(endpoint, new PublishingWorkflow()).spy();
        EventGateway eventGateway = fixture.getFluxzero().eventGateway();

        fixture.whenCommand(new CallEndpoint())
                .expectResult(200);

        assertEquals(2, endpoint.attempts.get());
        verifyNoInteractions(eventGateway);
    }

    private static WebRequestSettings retrySettings() {
        return WebRequestSettings.builder()
                .useNativeHttpClient(true)
                .maxRetries(1)
                .retryableStatusCodes(Set.of(429))
                .retryDelay(Duration.ofDays(1))
                .build();
    }

    private static class RetryingEndpoint {
        private final AtomicInteger attempts = new AtomicInteger();

        @HandleGet(ENDPOINT)
        WebResponse handle() {
            return attempts.getAndIncrement() == 0
                    ? WebResponse.builder().status(429).build()
                    : WebResponse.builder().status(200).build();
        }
    }

    private static class Workflow {
        @HandleCommand
        int handle(CallEndpoint ignored) {
            return Fluxzero.get().webRequestGateway().sendAndWait(
                    WebRequest.get(ENDPOINT).build(), retrySettings()).getStatus();
        }
    }

    private static class PublishingWorkflow {
        @HandleCommand
        int handle(CallEndpoint ignored) {
            Fluxzero.publishEvent(new EndpointCalled());
            return Fluxzero.get().webRequestGateway().sendAndWait(
                    WebRequest.get(ENDPOINT).build(), retrySettings()).getStatus();
        }
    }

    private record CallEndpoint() {
    }

    private record EndpointCalled() {
    }
}
