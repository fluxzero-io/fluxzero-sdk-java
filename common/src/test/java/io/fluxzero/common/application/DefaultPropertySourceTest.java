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

package io.fluxzero.common.application;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static io.fluxzero.common.TestUtils.runWithSystemProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultPropertySourceTest {
    @TempDir
    Path tempDir;


    @Test
    void fromSystemProperty() {
        runWithSystemProperties(() -> assertEquals("bar", new DefaultPropertySource().get("foo")),
                                "foo", "bar");
    }

    @Test
    void fromApplicationPropertiesFile() {
        runWithSystemProperties(() -> {
            DefaultPropertySource source = new DefaultPropertySource();
            assertEquals("bar", source.get("propertiesFile.foo"));
            assertEquals("someOtherValue", source.get("foo"));
        }, "foo", "someOtherValue");
    }

    @Test
    void fromApplicationEnvironmentPropertiesFile() {
        runWithSystemProperties(() -> {
            var source = new DefaultPropertySource();
            assertEquals("envbar", source.get("propertiesFile.foo"));
            assertEquals("bar", source.get("envFile.foo"));
        }, "environment", "test");
    }

    @Test
    void fromFluxzeroAdditionalLocation() throws Exception {
        Path secrets = tempDir.resolve("application-secrets.properties");
        Files.writeString(secrets, """
                foo=fromAdditionalLocation
                additional.secret=bar
                propertiesFile.foo=fromAdditionalLocation
                """);

        runWithSystemProperties(() -> {
            var source = new DefaultPropertySource();

            assertEquals("fromAdditionalLocation", source.get("foo"));
            assertEquals("fromAdditionalLocation", source.get("propertiesFile.foo"));
            assertEquals("bar", source.get("additional.secret"));
        }, FluxzeroAdditionalPropertiesSource.CONFIG_LOCATIONS_PROPERTY, secrets.toUri().toString());
    }

    @Test
    void systemPropertiesOverrideFluxzeroAdditionalLocation() throws Exception {
        Path secrets = tempDir.resolve("application-secrets.properties");
        Files.writeString(secrets, "additional.secret=fromAdditionalLocation");

        runWithSystemProperties(() -> {
            var source = new DefaultPropertySource();

            assertEquals("fromSystemProperty", source.get("additional.secret"));
        }, FluxzeroAdditionalPropertiesSource.CONFIG_LOCATIONS_PROPERTY, secrets.toUri().toString(),
                                "additional.secret", "fromSystemProperty");
    }

    @Test
    void fromMultipleFluxzeroAdditionalLocations() throws Exception {
        Path first = tempDir.resolve("first.properties");
        Path second = tempDir.resolve("second.properties");
        Files.writeString(first, """
                additional.first=first
                additional.shared=first
                """);
        Files.writeString(second, """
                additional.second=second
                additional.shared=second
                """);

        runWithSystemProperties(() -> {
            var source = new DefaultPropertySource();

            assertEquals("first", source.get("additional.first"));
            assertEquals("second", source.get("additional.second"));
            assertEquals("second", source.get("additional.shared"));
        }, FluxzeroAdditionalPropertiesSource.CONFIG_LOCATIONS_PROPERTY,
                                first.toUri() + "," + second.toUri());
    }

    @Test
    void optionalFluxzeroAdditionalLocationMayBeMissing() {
        Path secrets = tempDir.resolve("missing.properties");

        runWithSystemProperties(() -> {
            var source = new DefaultPropertySource();

            assertEquals("bar", source.get("foo"));
        }, FluxzeroAdditionalPropertiesSource.CONFIG_LOCATIONS_PROPERTY,
                                "optional:" + secrets.toUri());
    }

    @Test
    void fromFluxzeroAdditionalClasspathLocation() {
        runWithSystemProperties(() -> {
            var source = new DefaultPropertySource();

            assertEquals("fromClasspathAdditional", source.get("additional.classpath"));
        }, FluxzeroAdditionalPropertiesSource.CONFIG_LOCATIONS_PROPERTY,
                                "classpath:additional-fluxzero.properties");
    }

    @Test
    void fromFluxzeroPropertiesFiles() {
        var source = new DefaultPropertySource();

        assertEquals("bar", source.get("fluxzero.properties.foo"));
        assertEquals("bar", source.get("fluxzero.json.foo"));
        assertEquals("true", source.get("fluxzero.json.enabled"));
        assertEquals("42", source.get("fluxzero.number"));
    }

    @Test
    void applicationPropertiesOverrideFluxzeroPropertiesFiles() {
        var source = new DefaultPropertySource();

        assertEquals("bar", source.get("foo"));
    }

    @Test
    void fluxzeroPropertiesOverrideFluxzeroJson() {
        var source = new DefaultPropertySource();

        assertEquals("properties", source.get("fluxzero.shared"));
    }

    @Test
    void getInteger() {
        runWithSystemProperties(() -> {
            var source = new DefaultPropertySource();

            assertEquals(42, source.getInteger("integer.value"));
            assertEquals(7, source.getInteger("missing.value", 7));
        }, "integer.value", "42");
    }

    @Nested
    class PropertySubstitutionsString {
        @Test
        void noQuotes() {
            runWithSystemProperties(() -> assertEquals("Template with foo", new DefaultPropertySource()
                    .substituteProperties("Template with ${aba}")), "aba", "foo", "abb", "bar");
        }

        @Test
        void propertyMissing() {
            assertThrows(IllegalStateException.class, () -> runWithSystemProperties(
                    () -> new DefaultPropertySource().substituteProperties("Template with ${aba}"), "abb", "bar"));
        }

        @Test
        void nested() {
            runWithSystemProperties(() -> assertEquals("Template with foo", new DefaultPropertySource()
                    .substituteProperties("Template with ${aba${abb}}")), "abb", "bar", "ababar", "foo");
        }

        @Test
        void referToOtherProperty() {
            runWithSystemProperties(() -> assertEquals("Template with bar", new DefaultPropertySource()
                    .substituteProperties("Template with ${aba}")), "aba", "${abb}", "abb", "bar");
        }

        @Test
        void defaultValue_missing() {
            runWithSystemProperties(() -> assertEquals("Template with foo:bar", new DefaultPropertySource()
                    .substituteProperties("Template with ${aba:foo:bar}")));
        }

        @Test
        void defaultValue_notMissing() {
            runWithSystemProperties(() -> assertEquals("Template with bar", new DefaultPropertySource()
                    .substituteProperties("Template with ${aba:foo}")), "aba", "bar");
        }

        @Test
        void defaultValue_otherProperty() {
            runWithSystemProperties(() -> assertEquals("Template with bar", new DefaultPropertySource()
                    .substituteProperties("Template with ${aba:${abb}}")), "abb", "bar");
        }
    }

    @Nested
    class PropertySubstitutionsProperties {
        @Test
        void noSub() {
            runWithSystemProperties(() -> assertEquals(toProperties("key", "foo"), new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "foo"))), "aba", "foo", "abb", "bar");
        }

        @Test
        void noQuotes() {
            runWithSystemProperties(() -> assertEquals(toProperties("key", "foo"), new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "${aba}"))), "aba", "foo", "abb", "bar");
        }

        @Test
        void propertyMissing() {
            assertThrows(IllegalStateException.class, () -> runWithSystemProperties(
                    () -> new DefaultPropertySource().substituteProperties(toProperties("key", "${aba}")), "abb", "bar"));
        }

        @Test
        void nested() {
            runWithSystemProperties(() -> assertEquals(toProperties("key", "Template with foo"), new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "Template with ${aba${abb}}"))), "abb", "bar", "ababar", "foo");
        }

        @Test
        void referToOtherProperty() {
            runWithSystemProperties(() -> assertEquals(toProperties("key", "Template with bar"), new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "Template with ${aba}"))), "aba", "${abb}", "abb", "bar");
        }

        @Test
        void defaultValue_missing() {
            runWithSystemProperties(() -> assertEquals(toProperties("key", "Template with foo:bar"), new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "Template with ${aba:foo:bar}"))));
        }

        @Test
        void defaultValue_notMissing() {
            runWithSystemProperties(() -> assertEquals(toProperties("key", "Template with bar"), new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "Template with ${aba:foo}"))), "aba", "bar");
        }

        @Test
        void defaultValue_otherProperty() {
            runWithSystemProperties(() -> assertEquals(toProperties("key", "Template with bar"), new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "Template with ${aba:${abb}}"))), "abb", "bar");
        }

        @Test
        void defaultValue_noLoop() {
            assertThrows(IllegalStateException.class, () -> runWithSystemProperties(() -> new DefaultPropertySource()
                    .substituteProperties(toProperties("key", "Template with ${aba}")), "abb", "${aba}", "aba", "${abb}"));
        }

        static Properties toProperties(String... pairs) {
            Properties properties = new Properties();
            for (int i = 0; i < pairs.length; i += 2) {
                properties.setProperty(pairs[i], pairs[i + 1]);
            }
            return properties;
        }
    }
}
