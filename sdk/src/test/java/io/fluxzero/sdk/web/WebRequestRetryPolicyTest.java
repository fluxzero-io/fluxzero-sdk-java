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

package io.fluxzero.sdk.web;

import io.fluxzero.common.api.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebRequestRetryPolicyTest {
    private final WebRequestSettings safe = WebRequestSettings.builder()
            .maxRetries(2).retryPolicy(RetryPolicy.SAFE).build();

    @Test
    void safeRetriesRequireIdempotentMethodOrOneStableKey() {
        for (String method : List.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE")) {
            assertEquals(2, safe.retriesFor(method, Map.of()));
        }
        for (String method : List.of("POST", "PATCH", "get", "CUSTOM")) {
            assertEquals(0, safe.retriesFor(method, Map.of()));
            assertEquals(2, safe.retriesFor(method, Map.of("idempotency-key", List.of("stable-key"))));
        }
        assertEquals(0, safe.retriesFor("POST", Map.of("Idempotency-Key", List.of(" "))));
        assertEquals(0, safe.retriesFor("POST", Map.of("Idempotency-Key", List.of("a", "b"))));
        assertEquals(0, safe.retriesFor("POST", Map.of("Idempotency-Key", List.of("a"),
                                                     "idempotency-key", List.of("a"))));
    }

    @Test
    void compatibilityAndExplicitApplicationGuaranteePreserveConfiguredRetries() {
        for (RetryPolicy policy : List.of(RetryPolicy.LEGACY, RetryPolicy.IDEMPOTENT)) {
            assertEquals(2, safe.toBuilder().retryPolicy(policy).build().retriesFor("POST", Map.of()));
        }
        assertEquals(0, safe.toBuilder().maxRetries(-1).build().retriesFor("GET", Map.of()));
    }

    @Test
    void oldSettingsUseLegacyPolicyAndFixedDelayWhileNewSettingsRoundTrip() {
        var old = Metadata.of("settings", "{\"maxRetries\":2}").get("settings", WebRequestSettings.class);
        assertEquals(RetryPolicy.LEGACY, old.getRetryPolicy());
        assertFalse(old.isRetryJitter());
        assertEquals(2, old.retriesFor("POST", Map.of()));
        var configured = safe.toBuilder().retryJitter(true).build();
        assertEquals(configured, Metadata.of("settings", configured).get("settings", WebRequestSettings.class));
    }

    @Test
    void jitterVariesEvenForSubMillisecondDelays() {
        for (Duration delay : List.of(Duration.ofNanos(1), Duration.ofNanos(100), Duration.ofMillis(1))) {
            var random = new java.util.Random(123);
            var settings = safe.toBuilder().retryDelay(delay).retryJitter(true).build();
            var values = new java.util.HashSet<Duration>();
            for (int i = 0; i < 30; i++) {
                values.add(settings.nextRetryDelay(random));
            }
            assertTrue(values.size() > 1);
        }
    }

    @Test
    void jitterRemainsNonnegativeAndWithinConfiguredDelayWithoutOverflow() {
        for (Duration delay : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(200),
                                      Duration.ofSeconds(Long.MAX_VALUE))) {
            var settings = safe.toBuilder().retryDelay(delay).retryJitter(true).build();
            for (int i = 0; i < 100; i++) {
                Duration actual = settings.nextRetryDelay();
                assertTrue(actual.compareTo(delay.dividedBy(2)) >= 0);
                assertTrue(actual.compareTo(delay) <= 0);
            }
            assertEquals(delay, settings.toBuilder().retryJitter(false).build().nextRetryDelay());
        }
        assertEquals(Duration.ZERO, safe.toBuilder().retryDelay(Duration.ofSeconds(-1))
                .retryJitter(true).build().nextRetryDelay());
    }
}
