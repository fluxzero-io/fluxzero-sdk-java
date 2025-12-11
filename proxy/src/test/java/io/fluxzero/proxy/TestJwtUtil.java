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
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

public final class TestJwtUtil {

    private static final SecureRandom secureRandom = new SecureRandom();

    public static Map.Entry<String, String> create(String subject, String kid) {
        try {
            // Generate RSA keypair
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, secureRandom);
            KeyPair keyPair = kpg.generateKeyPair();

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
            String payloadJson = """
            {
              "sub": "%s"
            }
            """.formatted(subject);

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

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}