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

/**
 * Redirect policy for native outbound {@link WebRequest} execution.
 */
public enum RedirectPolicy {
    /**
     * Uses the application default. Compatibility defaults allow normal JDK redirects; newer defaults only follow
     * redirects within the original origin.
     */
    DEFAULT,

    /**
     * Does not follow redirects.
     */
    NEVER,

    /**
     * Follows at most five redirects, and only when scheme, host, and effective port equal the original request origin.
     * The origin is verified before any request headers or body are reused for the redirect target.
     */
    SAME_ORIGIN,

    /**
     * Uses the JDK's normal redirect policy, which follows HTTP-to-HTTP, HTTP-to-HTTPS, and HTTPS-to-HTTPS redirects,
     * but not HTTPS-to-HTTP redirects.
     */
    ALLOW
}
