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
import java.util.Set;

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
}
