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

package io.fluxzero.common.application;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaPropertiesSourceTest {

    @Test
    void customSourcesRetainExactLookupBehavior() {
        Properties properties = new Properties();
        properties.setProperty("CUSTOM_PROPERTY_NAME", "value");
        JavaPropertiesSource source = new JavaPropertiesSource(properties) {
        };

        assertNull(source.get("custom.property-name"));
    }
    @Test
    void repeatedAliasLookupsObserveCurrentValuesAndKeepSourcesIsolated() {
        Properties first = new Properties();
        Properties second = new Properties();
        JavaPropertiesSource source = new JavaPropertiesSource(first, true) {};
        JavaPropertiesSource other = new JavaPropertiesSource(second, true) {};
        String key = "mutable.camelCase";
        assertNull(source.get(key));
        first.setProperty("MUTABLE_CAMELCASE", "compact");
        second.setProperty("MUTABLE_CAMEL_CASE", "other");
        assertEquals("compact", source.get(key));
        first.setProperty("MUTABLE_CAMEL_CASE", "conventional");
        assertEquals("conventional", source.get(key));
        first.setProperty(key, "exact");
        assertEquals("exact", source.get(key));
        first.remove(key);
        first.setProperty("MUTABLE_CAMEL_CASE", "changed");
        assertEquals("changed", source.get(key));
        assertEquals("other", other.get(key));
        first.clear();
        assertNull(source.get(key));
        assertEquals("other", other.get(key));
    }

}
