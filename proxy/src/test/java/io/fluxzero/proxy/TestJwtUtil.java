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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public final class TestJwtUtil {

    // This deliberately public test key exercises real RSA signing/verification without random prime generation.
    private static final KeyPair keyPair = loadTestKey();

    public static Map.Entry<String, String> create(String subject, String kid) {
        return create(subject, kid, null);
    }

    public static Map.Entry<String, String> create(String subject, String kid, Instant expiresAt) {
        try {
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

            // Base64url encode modulus + exponent
            String n = base64UrlEncode(publicKey.getModulus().toByteArray());
            String e = base64UrlEncode(publicKey.getPublicExponent().toByteArray());

            // Create JWKS document
            String jwks = """
            {
              "keys": [
                {
                  "kty": "RSA",
                  "kid": "%s",
                  "use": "sig",
                  "alg": "RS256",
                  "n": "%s",
                  "e": "%s"
                }
              ]
            }
            """.formatted(kid, n, e);

            // JWT header
            String headerJson = """
            {
              "alg": "RS256",
              "kid": "%s",
              "typ": "JWT"
            }
            """.formatted(kid);

            // JWT payload
            String payloadJson = expiresAt == null ? """
            {
              "sub": "%s"
            }
            """.formatted(subject) : """
            {
              "sub": "%s",
              "exp": %d
            }
            """.formatted(subject, expiresAt.getEpochSecond());

            // Base64url encoding
            String h = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String p = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = h + "." + p;

            // Sign with private key
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] signature = sig.sign();

            String s = base64UrlEncode(signature);

            return Map.entry(signingInput + "." + s, jwks);

        } catch (Exception ex) {
            throw new RuntimeException("Failed to create test JWT", ex);
        }
    }

    private static KeyPair loadTestKey() {
        try (var input = TestJwtUtil.class.getResourceAsStream("/jwt/test-only-private-key.pem")) {
            if (input == null) {
                throw new IllegalStateException("Missing test-only RSA key");
            }
            String pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            var publicKey = factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
