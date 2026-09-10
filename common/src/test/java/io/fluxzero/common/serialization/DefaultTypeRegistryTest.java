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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTypeRegistryTest {

    @Test
    void resolvesUniqueSimpleAndPartialNames() {
        var testSubject = new DefaultTypeRegistry(List.of(
                "com.example.billing.CreateOrder", "com.example.shipping.CancelOrder"));

        assertEquals("com.example.billing.CreateOrder", testSubject.getTypeName("CreateOrder").orElseThrow());
        assertEquals("com.example.shipping.CancelOrder", testSubject.getTypeName("shipping.CancelOrder").orElseThrow());
        assertTrue(testSubject.getTypeName("UnknownOrder").isEmpty());
    }

    @Test
    void doesNotResolveAmbiguousSimpleOrPartialNames() {
        var testSubject = new DefaultTypeRegistry(List.of(
                "com.example.billing.commands.CreateOrder",
                "com.example.shipping.commands.CreateOrder"));

        assertTrue(testSubject.getTypeName("CreateOrder").isEmpty());
        assertTrue(testSubject.getTypeName("commands.CreateOrder").isEmpty());
        assertEquals("com.example.billing.commands.CreateOrder",
                     testSubject.getTypeName("billing.commands.CreateOrder").orElseThrow());
        assertEquals("com.example.shipping.commands.CreateOrder",
                     testSubject.getTypeName("shipping.commands.CreateOrder").orElseThrow());
    }

    @Test
    void duplicateMetadataForSameTypeIsNotAmbiguous() {
        var testSubject = new DefaultTypeRegistry(List.of(
                "com.example.CreateOrder", "com.example.CreateOrder"));

        assertEquals("com.example.CreateOrder", testSubject.getTypeName("CreateOrder").orElseThrow());
    }

    @Test
    void ignoresEmptyMetadataEntries() {
        var testSubject = new DefaultTypeRegistry(Arrays.asList(
                null, "", "  ", "com.example.CreateOrder"));

        assertEquals("com.example.CreateOrder", testSubject.getTypeName("CreateOrder").orElseThrow());
    }

}
