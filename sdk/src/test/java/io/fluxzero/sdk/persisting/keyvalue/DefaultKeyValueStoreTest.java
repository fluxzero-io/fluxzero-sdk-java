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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultKeyValueStoreTest {

    private final Client client = mock(Client.class);
    private final KeyValueClient keyValueClient = mock(KeyValueClient.class);
    private final Serializer serializer = mock(Serializer.class);
    private final Data<byte[]> serializedValue = new Data<>(new byte[0], "type", 0);

    private DefaultKeyValueStore testSubject;

    @BeforeEach
    void setUp() {
        when(client.getKeyValueClient()).thenReturn(keyValueClient);
        testSubject = new DefaultKeyValueStore(client, serializer);
    }

    @Test
    void storeFailureDoesNotRenderValueInException() {
        SensitiveValue value = new SensitiveValue();
        RuntimeException failure = new RuntimeException("write failed");
        when(serializer.serialize(value)).thenReturn(serializedValue);
        when(keyValueClient.putValue("key", serializedValue, Guarantee.STORED))
                .thenReturn(CompletableFuture.failedFuture(failure));

        KeyValueStoreException error = assertThrows(
                KeyValueStoreException.class, () -> testSubject.store("key", value, Guarantee.STORED));

        assertEquals("Could not store a value for key key", error.getMessage());
        assertSame(failure, error.getCause().getCause());
        assertFalse(value.rendered);
    }

    @Test
    void storeIfAbsentFailureDoesNotRenderValueInException() {
        SensitiveValue value = new SensitiveValue();
        RuntimeException failure = new RuntimeException("write failed");
        when(serializer.serialize(value)).thenReturn(serializedValue);
        when(keyValueClient.putValueIfAbsent("key", serializedValue))
                .thenReturn(CompletableFuture.failedFuture(failure));

        KeyValueStoreException error = assertThrows(
                KeyValueStoreException.class, () -> testSubject.storeIfAbsent("key", value));

        assertEquals("Could not store a value for key key", error.getMessage());
        assertSame(failure, error.getCause().getCause());
        assertFalse(value.rendered);
    }

    private static class SensitiveValue {

        private boolean rendered;

        @Override
        public String toString() {
            rendered = true;
            return "sensitive-value";
        }
    }
}
