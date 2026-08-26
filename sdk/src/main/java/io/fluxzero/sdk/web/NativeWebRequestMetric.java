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

import lombok.Value;

/**
 * Operational outcome of one native outbound {@link WebRequest}.
 *
 * <p>The normalized request target identifies the original logical request, not a later redirect destination. This
 * value deliberately excludes URI user information, query strings, fragments, headers, bodies, and exception text.
 * The path is included and may therefore contain application-specific information.</p>
 */
@Value
public class NativeWebRequestMetric {
    /**
     * HTTP method of the logical request.
     */
    String method;

    /**
     * Lowercase scheme of the original request, or {@code null} when no valid HTTP(S) origin was available.
     */
    String scheme;

    /**
     * Lowercase hostname of the original request, or {@code null} when no valid HTTP(S) origin was available.
     */
    String hostname;

    /**
     * Effective port of the original request, including {@code 80} for HTTP and {@code 443} for HTTPS when omitted,
     * or {@code null} when no valid HTTP(S) origin was available.
     */
    Integer port;

    /**
     * Raw path of the original request without query or fragment, using {@code /} when the URI has no path, or
     * {@code null} when no valid HTTP(S) request target was available.
     */
    String path;

    /**
     * Final HTTP status, or {@code null} if the request ended in a transport failure or cancellation.
     */
    Integer status;

    /**
     * Safe failure category, or {@code null} when a response was received.
     */
    ErrorCategory errorCategory;

    /**
     * Total elapsed time for the logical request, including redirects, retry delays, and retries.
     */
    long nanosecondDuration;

    /**
     * Number of HTTP attempts. Redirect hops within an attempt do not increment this value.
     */
    int attempts;

    /**
     * Whether the caller cancelled the asynchronous result.
     */
    boolean cancelled;

    /**
     * Whether a redirect was returned but not followed by the configured redirect policy or the JDK client.
     */
    boolean redirectRejected;

    /**
     * Safe transport-failure classification used instead of exception details.
     */
    public enum ErrorCategory {
        TIMEOUT,
        CONNECTION,
        IO,
        INVALID_REQUEST,
        SECURITY,
        CANCELLED,
        OTHER
    }
}
