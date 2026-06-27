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

package io.fluxzero.proxy;

import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.web.WebRequest;

import java.time.Clock;
import java.util.Locale;
import java.util.function.Supplier;

import static io.fluxzero.sdk.common.ClientUtils.memoize;

/**
 * The NamespaceSelector class is responsible for determining and decoding a namespace value from an HTTP web request.
 * It utilizes a JWT-based verification mechanism to decode and validate the namespace value.
 * <p>
 * This class includes a memoized {@link JwtVerifier} for managing JWT verification and a memoized namespace decoding
 * function that decodes and validates the provided namespace JWT token.
 */
public class NamespaceSelector {
    public static final String FLUXZERO_NAMESPACE_HEADER = "Fluxzero-Namespace";
    public static final String JWKS_URL_PROPERTY = "FLUXZERO_PROXY_JWKS_URL";
    public static final String NAMESPACE_HEADER_MODE_PROPERTY = "FLUXZERO_PROXY_NAMESPACE_HEADER_MODE";

    private final Supplier<JwtVerifier> jwtVerifier;

    public NamespaceSelector() {
        this(Clock.systemUTC());
    }

    NamespaceSelector(Clock clock) {
        jwtVerifier = memoize(
                () -> ApplicationProperties.mapProperty(JWKS_URL_PROPERTY, url -> new JwtVerifier(url, clock)));
    }

    /**
     * Extracts and decodes the namespace value from the given web request. The namespace value is retrieved from the
     * header identified by {@code FLUXZERO_NAMESPACE_HEADER}. The configured namespace header mode determines whether
     * raw values are accepted, signed values are required, or the header is rejected completely.
     *
     * @param webRequest the incoming HTTP web request containing the namespace header
     * @return the decoded namespace string, or {@code null} if the header is not present
     */
    public String select(WebRequest webRequest) {
        String encoded = webRequest.getHeader(FLUXZERO_NAMESPACE_HEADER);
        if (encoded == null) {
            return null;
        }
        return switch (getMode()) {
            case RAW -> decodeRawOrSigned(encoded);
            case SIGNED -> decodeSigned(encoded);
            case DISABLED -> throw new SecurityException("Namespace header is disabled");
        };
    }

    private String decodeRawOrSigned(String encoded) {
        JwtVerifier verifier = jwtVerifier.get();
        return verifier == null ? encoded : verify(verifier, encoded);
    }

    private String decodeSigned(String encoded) {
        JwtVerifier verifier = jwtVerifier.get();
        if (verifier == null) {
            throw new SecurityException("Namespace headers should be signed");
        }
        return verify(verifier, encoded);
    }

    private static String verify(JwtVerifier verifier, String encoded) {
        var claims = verifier.verify(encoded);
        String sub = claims.getString("sub");
        if (sub == null) {
            throw new SecurityException("JWT misses subject claim");
        }
        return sub;
    }

    private static HeaderMode getMode() {
        String configuredMode = ApplicationProperties.getProperty(NAMESPACE_HEADER_MODE_PROPERTY, HeaderMode.RAW.name());
        try {
            return HeaderMode.valueOf(configuredMode.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(NAMESPACE_HEADER_MODE_PROPERTY
                                               + " must be one of raw, signed or disabled", e);
        }
    }

    private enum HeaderMode {
        RAW,
        SIGNED,
        DISABLED
    }
}
