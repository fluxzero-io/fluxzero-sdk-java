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
 *
 */

package io.fluxzero.common.application;

import io.fluxzero.common.encryption.DefaultEncryption;
import io.fluxzero.common.encryption.Encryption;
import io.fluxzero.common.encryption.NoOpEncryption;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.common.ObjectUtils.memoize;

/**
 * A {@link PropertySource} decorator that transparently decrypts encrypted property values.
 *
 * <p>This implementation wraps a delegate {@link PropertySource} and attempts to decrypt any retrieved
 * property value using a configured {@link Encryption} strategy.
 *
 * <p>Decryption is only applied to values identified as encrypted via {@link Encryption#isEncrypted(String)}.
 * Values that are not encrypted are returned as-is.
 *
 * <p>The encryption strategy is determined through the default Fluxzero property source using the
 * {@code ENCRYPTION_KEY} or {@code encryption_key} property. This includes environment variables, system properties,
 * configured additional locations, application property files, and Fluxzero defaults according to
 * {@link DefaultPropertySource} precedence. If no key is found, a no-op fallback is used, meaning decryption will be
 * skipped.
 *
 * <p>To enable encrypted property support in a Fluxzero application, ensure the appropriate key is present, e.g.:
 * <pre>{@code
 *   export ENCRYPTION_KEY=ChaCha20|mYbAse64ENcodedKeY==
 * }</pre>
 *
 * @see Encryption
 * @see DefaultEncryption
 */
@Slf4j
public class DecryptingPropertySource implements PropertySource {
    private final PropertySource delegate;

    /**
     * Returns the {@link Encryption} instance used by this property source.
     */
    @Getter
    private final Encryption encryption;

    private final Function<String, String> decryptionCache = memoize(s -> getEncryption().decrypt(s));

    /**
     * Constructs a {@code DecryptingPropertySource} with the specified delegate property source.
     * By default, the encryption key is fetched from the {@link DefaultPropertySource} using the keys
     * {@code ENCRYPTION_KEY} or {@code encryption_key}.
     *
     * @param delegate the property source to wrap and decrypt values from
     */
    public DecryptingPropertySource(PropertySource delegate) {
        this(delegate, DefaultPropertySource.getInstance().get(
                "ENCRYPTION_KEY", DefaultPropertySource.getInstance().get("encryption_key")));
    }

    /**
     * Constructs a {@code DecryptingPropertySource} using the given encryption key.
     * <p>If the provided key is invalid or unrecognized, a no-op encryption fallback is used.
     *
     * @param delegate      the property source to wrap
     * @param encryptionKey the Base64-encoded encryption key in the form {@code algorithm|key}
     */
    public DecryptingPropertySource(PropertySource delegate, String encryptionKey) {
        this(delegate, Optional.ofNullable(encryptionKey)
                .map(encodedKey -> {
                    try {
                        log.info("Initializing DefaultEncryption from key");
                        return DefaultEncryption.fromEncryptionKey(encodedKey);
                    } catch (Exception e) {
                        log.error("Could not construct DefaultEncryption from environment variable "
                                  + "`ENCRYPTION_KEY`");
                        return null;
                    }
                }).orElseGet(() -> new DefaultEncryption(NoOpEncryption.INSTANCE)));
    }

    /**
     * Constructs a {@code DecryptingPropertySource} using an explicit {@link Encryption} strategy.
     *
     * @param delegate   the property source to wrap
     * @param encryption the encryption strategy to apply to values
     */
    public DecryptingPropertySource(PropertySource delegate, Encryption encryption) {
        this.delegate = delegate;
        this.encryption = encryption;
    }

    /**
     * Returns the decrypted value of the given property name.
     *
     * <p>If the underlying property is encrypted (i.e., {@link Encryption#isEncrypted(String)} is {@code true}),
     * it is decrypted using the configured {@link Encryption} implementation.
     * Otherwise, the raw value is returned unchanged.
     *
     * <p>Values are cached after the first decryption attempt for efficiency.
     *
     * @param name the property key
     * @return the decrypted or original property value, or {@code null} if not found
     */
    @Override
    public String get(String name) {
        String value = delegate.get(name);
        return value == null ? null : decryptionCache.apply(value);
    }
}
