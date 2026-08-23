/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FluxzeroModelMigrationTest {

    @AfterEach
    void cleanUp() {
        Fluxzero.instance.remove();
    }

    @Test
    void migratesTheCurrentlyHandledIndexedGlobalEvent() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        when(fluxzero.executePublishedModelEvent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Fluxzero.instance.set(fluxzero);
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serialized = new Message("legacy-event").serialize(serializer);
        serialized.setIndex(42L);
        DeserializingMessage current = serializer.deserializeMessage(
                serialized, MessageType.EVENT);

        current.apply(ignored -> {
            Fluxzero.migratePublishedEventAsync().join();
            return null;
        });

        verify(fluxzero).executePublishedModelEvent(current.toMessage(), 42L);
    }

    @Test
    void rejectsMigrationOutsideAnIndexedEventHandler() {
        Fluxzero.instance.set(mock(Fluxzero.class));

        assertThrows(
                CompletionException.class,
                () -> Fluxzero.migratePublishedEventAsync().join());
    }
}
