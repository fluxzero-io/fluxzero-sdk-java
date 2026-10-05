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

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.application.SimplePropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AssertCurrentDefaultsTest {
    @ParameterizedTest
    @CsvSource({",,false", "2026.10.03,,false", "2026.10.04,,true", "2027.01.01,,true",
                ",true,true", "2026.10.03,true,true", "2026.10.04,false,false", "2027.01.01,false,false"})
    void versionAndPropertyPrecedence(String date,String override,boolean expected) {
        Map<String,String> properties = new HashMap<>();
        if(date!=null) properties.put(ApplicationProperties.DEFAULTS_VERSION_PROPERTY,date);
        if(override!=null) properties.put(ApplicationProperties.ASSERT_CURRENT_PROPERTY,override);
        assertEquals(expected,ApplicationProperties.assertCurrent(new SimplePropertySource(properties)));
    }
    @Test void invalidDateFails() {
        assertThrows(IllegalArgumentException.class,()->ApplicationProperties.assertCurrent(
                new SimplePropertySource(Map.of(ApplicationProperties.DEFAULTS_VERSION_PROPERTY,"invalid"))));
    }
}
