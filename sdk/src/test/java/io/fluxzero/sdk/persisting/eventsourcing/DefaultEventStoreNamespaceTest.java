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

package io.fluxzero.sdk.persisting.eventsourcing;

import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.EventPublicationStrategy;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultEventStoreNamespaceTest {

    @Test
    void customNamespacePublishesEventsToLocalHandlersInThatNamespace() {
        Client applicationClient = mock(Client.class);
        Client customClient = mock(Client.class);
        EventStoreClient storeClient = mock(EventStoreClient.class);
        HandlerRegistry localHandlers = mock(HandlerRegistry.class);
        when(customClient.namespace()).thenReturn("tenant");
        when(customClient.forNamespace(null)).thenReturn(applicationClient);
        when(customClient.getEventStoreClient()).thenReturn(storeClient);
        when(storeClient.storeEvents(anyString(), anyList(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        DefaultEventStore eventStore = new DefaultEventStore(
                customClient, new JacksonSerializer(), DispatchInterceptor.noOp, localHandlers);

        eventStore.storeEvents("aggregate", List.of("event"), EventPublicationStrategy.STORE_AND_PUBLISH).join();

        var message = ArgumentCaptor.forClass(DeserializingMessage.class);
        verify(localHandlers).handle(message.capture());
        assertEquals("tenant", ClientUtils.getConsumerNamespace(message.getValue()));
    }
}
