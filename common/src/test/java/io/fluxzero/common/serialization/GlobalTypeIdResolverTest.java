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

package io.fluxzero.common.serialization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.*;

class GlobalTypeIdResolverTest {

    @Test
    void rejectsArbitraryClassDeserialization() {
        String maliciousJson = """
                {"@class": "javax.script.ScriptEngineManager"}
                """;
        Exception e = assertThrows(Exception.class, () -> JsonUtils.fromJson(maliciousJson));
        assertTrue(e.getMessage().contains("not allowed"), e.getMessage());
    }

    @Test
    void rejectsJavaLangProcessBuilder() {
        String maliciousJson = """
                {"@class": "java.lang.ProcessBuilder", "command": ["echo", "pwned"]}
                """;
        Exception e = assertThrows(Exception.class, () -> JsonUtils.fromJson(maliciousJson));
        assertTrue(e.getMessage().contains("not allowed"), e.getMessage());
    }

    @Test
    void rejectsJavaNetURL() {
        String maliciousJson = """
                {"@class": "java.net.URL", "protocol": "http", "host": "evil.com", "file": "/"}
                """;
        Exception e = assertThrows(Exception.class, () -> JsonUtils.fromJson(maliciousJson));
        assertTrue(e.getMessage().contains("not allowed"), e.getMessage());
    }

    @Test
    void allowsFluxzeroTypes() {
        String json = """
                {"@class": "io.fluxzero.common.api.Metadata", "entries": {}}
                """;
        assertDoesNotThrow(() -> JsonUtils.fromJson(json));
    }

    @Test
    void allowsStandardCollectionTypes() {
        String json = """
                {"@class": "java.util.LinkedHashMap"}
                """;
        assertDoesNotThrow(() -> JsonUtils.fromJson(json));
    }

    @Test
    void allowlistPermitsRegisteredTypes() {
        var registry = new DefaultTypeRegistry(List.of("com.example.MyEvent"));
        var validator = new AllowlistTypeValidator(() -> registry);
        assertTrue(validator.isAllowed("com.example.MyEvent"));
    }

    @Test
    void allowlistRejectsUnregisteredExternalTypes() {
        var registry = new DefaultTypeRegistry(emptyList());
        var validator = new AllowlistTypeValidator(() -> registry);
        assertFalse(validator.isAllowed("com.evil.Exploit"));
    }
}
