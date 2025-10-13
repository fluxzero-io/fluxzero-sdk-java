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

import java.util.Properties;

import static io.fluxzero.common.TestUtils.runWithSystemProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultPropertySourceTest {

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