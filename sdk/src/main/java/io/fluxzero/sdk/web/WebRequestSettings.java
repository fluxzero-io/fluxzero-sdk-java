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

import io.fluxzero.sdk.publishing.WebRequestGateway;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Configuration settings for a {@link WebRequest} sent via the {@link WebRequestGateway}.
 * <p>
 * By default, requests are published as Fluxzero messages and forwarded by the Fluxzero proxy. Applications can opt
 * into execution by the SDK's native HTTP client instead. Retries after transport failures or configured HTTP response
 * statuses apply within the configured overall timeout.
 *
 * <p><strong>Example usage:</strong></p>
 * <pre>{@code
 * WebRequestSettings settings = WebRequestSettings.builder()
 *     .httpVersion(HttpVersion.HTTP_2)
 *     .timeout(Duration.ofSeconds(30))
 *     .consumer("google-traffic")
 *     .maxRetries(2)
 *     .retryDelay(Duration.ofMillis(250))
 *     .retryableStatusCodes(Set.of(502, 503, 504))
 *     .build();
 * }</pre>
 *
 * @see WebRequestGateway#sendAndWait(WebRequest, WebRequestSettings)
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class WebRequestSettings {

    /**
     * HTTP version to be used for the web request (e.g., HTTP/1.1 or HTTP/2).
     */
    @Default
    HttpVersion httpVersion = HttpVersion.HTTP_1_1;

    /**
     * Maximum duration to wait for the response before timing out.
     */
    @Default
    Duration timeout = Duration.ofMinutes(1);

    /**
     * Name of the consumer responsible for handling the request, typically {@code "forward-proxy"}.
     */
    @Default
    String consumer = "forward-proxy";

    /**
     * Whether the SDK should execute the request directly with its native HTTP client instead of publishing it for the
     * Fluxzero proxy. Native execution requires an absolute HTTP(S) URL and bypasses Fluxzero message logging, local
     * web handlers, dispatch interceptors, and consumer isolation.
     */
    @Default
    boolean useNativeHttpClient = false;

    /**
     * Redirect policy for native HTTP execution. {@link RedirectPolicy#DEFAULT} preserves normal JDK redirects in
     * compatibility mode and resolves to {@link RedirectPolicy#SAME_ORIGIN} for applications that opt into the
     * corresponding versioned default. Same-origin execution verifies scheme, host, and effective port before reusing
     * a request body or authorization header and follows at most five redirects. The same policy is enforced for
     * direct native and proxy-routed requests; fixture-handled requests ignore it.
     */
    @Default
    @NonNull
    RedirectPolicy redirectPolicy = RedirectPolicy.DEFAULT;

    /**
     * Maximum number of additional attempts after a transport failure or a response whose status occurs in
     * {@link #retryableStatusCodes}. Retries share the configured {@link #timeout}. Values below zero are treated as
     * zero. A failure or response can occur after the remote server accepted a request, so retry non-idempotent calls
     * only when the destination provides suitable deduplication.
     */
    @Default
    int maxRetries = 0;

    /**
     * Fixed delay before each retry. Defaults to one second. The delay counts toward {@link #timeout}; no new attempt
     * starts when the delay no longer fits before the request deadline. Negative values are treated as zero.
     */
    @Default
    @NonNull
    Duration retryDelay = Duration.ofSeconds(1);

    /**
     * HTTP response statuses that may trigger a retry when {@link #maxRetries} is positive. Defaults to common
     * transient server failures: 500, 502, 503, and 504. Configure an empty set to retry transport failures only, or
     * provide a custom set for destination-specific response statuses. Other responses are returned immediately.
     */
    @Default
    @NonNull
    Set<Integer> retryableStatusCodes = Set.of(500, 502, 503, 504);

    /**
     * Retry safety policy. LEGACY preserves existing behavior. SAFE permits HTTP-idempotent methods or a single
     * nonblank Idempotency-Key whose deduplication semantics the upstream supports. IDEMPOTENT is an explicit
     * application guarantee. Neither policy enables retries without a positive maxRetries.
     */
    @Default
    @NonNull
    RetryPolicy retryPolicy = RetryPolicy.LEGACY;

    /**
     * Randomize each retry delay uniformly between half and all of retryDelay, within the existing timeout budget.
     * Disabled by default to preserve fixed-delay configurations. Does not change fixture execution time.
     */
    @Default
    boolean retryJitter = false;

    /** Returns the configured retry count after applying this request's safety policy. */
    public int retriesFor(String method, Map<String, List<String>> headers) {
        if (maxRetries <= 0) {
            return 0;
        }
        if (retryPolicy != RetryPolicy.SAFE
            || Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE").contains(method)) {
            return maxRetries;
        }
        List<String> keys = headers.entrySet().stream()
                .filter(e -> "Idempotency-Key".equalsIgnoreCase(e.getKey()))
                .flatMap(e -> e.getValue().stream()).toList();
        return keys.size() == 1 && keys.getFirst() != null && !keys.getFirst().isBlank() ? maxRetries : 0;
    }

    /** Selects the next nonnegative retry delay; callers must still check their remaining timeout budget. */
    public Duration nextRetryDelay() {
        return nextRetryDelay(ThreadLocalRandom.current());
    }

    Duration nextRetryDelay(RandomGenerator random) {
        Duration delay = retryDelay.isNegative() ? Duration.ZERO : retryDelay;
        if (!retryJitter || delay.isZero()) {
            return delay;
        }
        if (delay.getSeconds() < Long.MAX_VALUE / 1_000_000_000L) {
            long nanos = delay.toNanos();
            return Duration.ofNanos(random.nextLong(nanos / 2, nanos + 1));
        }
        // Retain fractional precision without overflowing even for Duration's largest supported values.
        Duration half = delay.dividedBy(2);
        Duration span = delay.minus(half);
        Duration quotient = span.dividedBy(1_000_000);
        Duration remainder = span.minus(quotient.multipliedBy(1_000_000));
        int fraction = random.nextInt(1_000_001);
        return half.plus(quotient.multipliedBy(fraction)).plus(remainder.multipliedBy(fraction).dividedBy(1_000_000));
    }
}
