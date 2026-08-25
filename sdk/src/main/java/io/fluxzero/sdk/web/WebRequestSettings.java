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
import lombok.Value;

import java.time.Duration;

/**
 * Configuration settings for a {@link WebRequest} sent via the {@link WebRequestGateway}.
 * <p>
 * By default, requests are published as Fluxzero messages and forwarded by the Fluxzero proxy. Applications can opt
 * into execution by the SDK's native HTTP client instead. Transport retries apply within the configured overall
 * timeout; HTTP error responses are returned without retrying.
 *
 * <p><strong>Example usage:</strong></p>
 * <pre>{@code
 * WebRequestSettings settings = WebRequestSettings.builder()
 *     .httpVersion(HttpVersion.HTTP_2)
 *     .timeout(Duration.ofSeconds(30))
 *     .consumer("google-traffic")
 *     .maxRetries(2)
 *     .build();
 * }</pre>
 *
 * @see WebRequestGateway#sendAndWait(WebRequest, WebRequestSettings)
 */
@Value
@Builder(toBuilder = true)
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
     * Maximum number of additional attempts after a transport failure. Retries share the configured {@link #timeout}
     * and do not apply to completed HTTP responses, including 4xx and 5xx status codes. Values below zero are treated
     * as zero. A transport failure can occur after the remote server accepted a request, so retry non-idempotent calls
     * only when the destination provides suitable deduplication.
     */
    @Default
    int maxRetries = 0;
}
