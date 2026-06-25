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

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.application.DecryptingPropertySource;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.encryption.DefaultEncryption;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationPropertiesTest {
    private final DefaultEncryption encryption = new DefaultEncryption();
    private final TestFixture testFixture = TestFixture.create();

    @Test
    void ableToReadApplicationProperties() {
        testFixture.whenApplying(fc -> ApplicationProperties.getProperty("foo2"))
                .expectResult("bar2")
                .andThen()
                .whenApplying(fc -> ApplicationProperties.getProperty("foo"))
                .expectResult("barOverride");
    }

    @Test
    void ableToDecrypt() {
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .replacePropertySource(existing -> new DecryptingPropertySource(
                        new SimplePropertySource(Map.of("encrypted", encryption.encrypt("bar"))).andThen(existing),
                        encryption))
                .build(LocalClient.newInstance())) {
            assertEquals("bar", fluxzero.apply(fc -> ApplicationProperties.getProperty("encrypted")));
        }
    }

    @Test
    void readsDefaultsVersion() {
        var propertySource = defaultsVersion("2026.06.09");

        assertEquals(LocalDate.of(2026, 6, 9), ApplicationProperties.getDefaultsVersion(propertySource));
        assertTrue(ApplicationProperties.defaultsVersionAtLeast(propertySource, LocalDate.of(2026, 6, 9)));
        assertTrue(ApplicationProperties.defaultsVersionAtLeast(propertySource, LocalDate.of(2026, 6, 8)));
        assertFalse(ApplicationProperties.defaultsVersionAtLeast(propertySource, LocalDate.of(2026, 6, 10)));
    }

    @Test
    void missingDefaultsVersionMeansCompatibilityDefaults() {
        assertNull(ApplicationProperties.getDefaultsVersion(defaultsVersion(null)));
        assertNull(ApplicationProperties.getDefaultsVersion(defaultsVersion(" ")));
        assertFalse(ApplicationProperties.defaultsVersionAtLeast(defaultsVersion(null), LocalDate.of(2026, 6, 9)));
    }

    @Test
    void rejectsInvalidDefaultsVersion() {
        assertThrows(IllegalArgumentException.class,
                     () -> ApplicationProperties.getDefaultsVersion(defaultsVersion("2026-06-09")));
    }

    private static PropertySource defaultsVersion(String value) {
        return name -> ApplicationProperties.DEFAULTS_VERSION_PROPERTY.equals(name) ? value : null;
    }
}
