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

import java.util.function.Function;
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

    private final Supplier<JwtVerifier> jwtVerifier = memoize(
            () -> ApplicationProperties.mapProperty(JWKS_URL_PROPERTY, JwtVerifier::new));

    private final Function<String, String> namespaceDecoder = memoize(encoded -> {
        if (encoded == null) {
            return null;
        }
        var verifier = jwtVerifier.get();
        if (verifier == null) {
            return encoded;
        }
        var claims = verifier.verify(encoded);
        String sub = claims.getString("sub");
        if (sub == null) {
            throw new SecurityException("JWT misses subject claim");
        }
        return sub;
    });

    /**
     * Extracts and decodes the namespace value from the given web request. The namespace value is retrieved from the
     * header identified by {@code FLUXZERO_NAMESPACE_HEADER} and is processed using the {@code namespaceDecoder}
     * function to decode it.
     *
     * @param webRequest the incoming HTTP web request containing the namespace header
     * @return the decoded namespace string, or {@code null} if the header is not present or the decoding fails
     */
    public String select(WebRequest webRequest) {
        return namespaceDecoder.apply(webRequest.getHeader(FLUXZERO_NAMESPACE_HEADER));
    }

}
