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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.Value;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JwtVerifier provides functionality to verify the cryptographic signature of a JSON Web Token (JWT) and validate its
 * claims.
 * <p>
 * This class relies on a JWKS (JSON Web Key Set) endpoint to resolve public keys used to verify the JWT signature. It
 * supports the RS256 algorithm for signature verification.
 * <p>
 * The public keys are cached after being fetched from the JWKS endpoint to reduce network calls.
 */
@RequiredArgsConstructor
public final class JwtVerifier {
    private final String jwksUrl;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();

    /**
     * Verifies the provided JWT (JSON Web Token) for its signature, expiration, and not-before validity.
     * <p>
     * This method checks the JWT's structure and signature to ensure it has not been tampered with. It also validates
     * the optional "exp" (expiration time) and "nbf" (not-before time) claims to confirm the token is within its valid
     * usage time frame.
     *
     * @param jwt the JSON Web Token to be verified
     * @return a {@link JwtClaims} object containing the claims from the valid JWT
     */
    public JwtClaims verify(String jwt) {
        JsonNode payload = verifySignature(jwt);

        long now = Instant.now().getEpochSecond();
        if (payload.has("exp") && payload.get("exp").asLong() < now) {
            throw new SecurityException("JWT expired");
        }
        if (payload.has("nbf") && payload.get("nbf").asLong() > now) {
            throw new SecurityException("JWT not yet valid");
        }

        return new JwtClaims(payload);
    }

    @SneakyThrows
    private JsonNode verifySignature(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new SecurityException("Invalid JWT format");
        }

        String hB64 = parts[0];
        String pB64 = parts[1];
        String sB64 = parts[2];

        // Decode header + payload
        JsonNode header = mapper.readTree(base64UrlDecode(hB64));
        JsonNode payload = mapper.readTree(base64UrlDecode(pB64));

        String alg = header.get("alg").asText();
        if (!"RS256".equals(alg)) {
            throw new SecurityException("Unsupported alg: " + alg);
        }
        String kid = header.get("kid").asText();

        PublicKey key = resolveKey(kid);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update((hB64 + "." + pB64).getBytes(StandardCharsets.UTF_8));

        if (!sig.verify(base64UrlDecode(sB64))) {
            throw new SecurityException("JWT signature invalid");
        }
        return payload;
    }

    private PublicKey resolveKey(String kid) throws Exception {
        PublicKey cached = keyCache.get(kid);
        if (cached != null) {
            return cached;
        }
        // Unknown key -> fetch JWKS once
        synchronized (keyCache) {
            JsonNode jwks = fetchJwks();

            PublicKey result = null;

            for (JsonNode keyNode : jwks.get("keys")) {
                String keyKid = keyNode.get("kid").asText();
                PublicKey key = buildRsaKey(keyNode);
                keyCache.put(keyKid, key);
                if (keyKid.equals(kid)) {
                    result = key;
                }
            }
            if (result != null) {
                return result;
            }

            throw new IllegalStateException("JWKS did not contain key with kid=" + kid);
        }
    }

    private JsonNode fetchJwks() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUrl)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    private PublicKey buildRsaKey(JsonNode keyNode) throws Exception {
        BigInteger n = new BigInteger(1, base64UrlDecode(keyNode.get("n").asText()));
        BigInteger e = new BigInteger(1, base64UrlDecode(keyNode.get("e").asText()));

        var spec = new RSAPublicKeySpec(n, e);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private static byte[] base64UrlDecode(String s) {
        String padded = s.replace('-', '+').replace('_', '/');
        int mod = padded.length() % 4;
        if (mod == 2) {
            padded += "==";
        } else if (mod == 3) {
            padded += "=";
        }
        return Base64.getDecoder().decode(padded);
    }

    /**
     * Represents the claims contained in a decoded JWT (JSON Web Token).
     * <p>
     * This class encapsulates the JSON structure of the claims within the JWT and provides helper methods to retrieve
     * specific claim values.
     * <p>
     * Thread-safe and immutable, as enforced by the `@Value` annotation.
     */
    @Value
    public static class JwtClaims {
        JsonNode json;

        /**
         * Retrieves the string value associated with the specified claim name from the JSON structure.
         *
         * @param name the name of the claim whose value needs to be retrieved
         * @return the string value of the specified claim, or null if the claim does not exist or is not a string
         */
        public String getString(String name) {
            return json.path(name).textValue();
        }
    }
}