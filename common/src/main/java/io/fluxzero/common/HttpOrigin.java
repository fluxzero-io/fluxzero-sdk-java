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

package io.fluxzero.common;

import java.net.URI;
import java.util.Locale;

/**
 * HTTP origin identity without credentials, path, query or fragment.
 *
 * @param scheme lowercase HTTP scheme
 * @param host lowercase hostname (no DNS resolution)
 * @param port effective port, including the scheme default
 */
public record HttpOrigin(String scheme, String host, int port) {
    /** Returns the normalized origin of an absolute HTTP(S) URI. */
    public static HttpOrigin of(URI uri) {
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                                      || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Expected an absolute HTTP(S) URI");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return new HttpOrigin(scheme, uri.getHost().toLowerCase(Locale.ROOT),
                              uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort());
    }
}
