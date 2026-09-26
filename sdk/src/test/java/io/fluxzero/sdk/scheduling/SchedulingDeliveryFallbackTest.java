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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.scheduling.client.WebsocketSchedulingClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULT_DELIVERY_GUARANTEE_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulingDeliveryFallbackTest {
    static Stream<Guarantee> overrides() {
        return Stream.of(null, Guarantee.NONE, Guarantee.SENT, Guarantee.STORED);
    }

    @ParameterizedTest
    @MethodSource("overrides")
    void directClientRetainsSentUnlessExplicitlyOverridden(Guarantee override) {
        Map<String, String> properties = new HashMap<>(Map.of(
                "FLUXZERO_BASE_URL", "ws://localhost", "FLUXZERO_APPLICATION_NAME", "test-app"));
        if (override != null) {
            properties.put(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, override.name());
        }
        var config = WebSocketClient.ClientConfig.fromProperties(new SimplePropertySource(properties));
        for (var selected : new WebSocketClient.ClientConfig[]{config, config.toBuilder().namespace("other").build()}) {
            var owner = WebSocketClient.newInstance(selected);
            try (var client = new CapturingClient(owner)) {
                Guarantee expected = override == null ? Guarantee.SENT : override;
                client.schedule().join();
                assertEquals(expected, client.lastGuarantee);
                client.cancelSchedule("id").join();
                assertEquals(expected, client.lastGuarantee);
                client.schedule(Guarantee.NONE).join();
                assertEquals(Guarantee.NONE, client.lastGuarantee);
            } finally {
                owner.shutDown();
            }
        }
    }

    private static class CapturingClient extends WebsocketSchedulingClient {
        Guarantee lastGuarantee;

        CapturingClient(WebSocketClient client) {
            super(URI.create("ws://localhost"), client, false);
        }

        @Override
        protected CompletableFuture<Void> sendCommand(Command command) {
            lastGuarantee = command.getGuarantee();
            return CompletableFuture.completedFuture(null);
        }
    }
}
