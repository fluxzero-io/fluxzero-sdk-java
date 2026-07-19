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

package io.fluxzero.sdk.persisting.keyvalue;

import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class KeyValueStoreNamespaceTest {

    @Test
    void storesAndLoadsValuesWithinSelectedNamespace() {
        var fluxzero = TestFixture.create().getFluxzero();
        KeyValueStore defaultStore = fluxzero.keyValueStore();
        KeyValueStore firstStore = defaultStore.forNamespace("first");
        KeyValueStore secondStore = defaultStore.forNamespace("second");

        firstStore.store("shared", "first value");
        secondStore.store("shared", "second value");

        assertSame(firstStore, firstStore.forNamespace("first"));
        assertSame(defaultStore, firstStore.forNamespace(null));
        assertSame(defaultStore, firstStore.forNamespace(fluxzero.client().namespace()));
        assertEquals("first value", firstStore.get("shared"));
        assertEquals("second value", secondStore.get("shared"));
        assertNull(defaultStore.get("shared"));
    }
}
