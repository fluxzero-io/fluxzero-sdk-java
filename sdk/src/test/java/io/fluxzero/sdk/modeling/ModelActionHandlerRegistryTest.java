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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelActionHandlerRegistryTest {

    @Test
    void retriesAutomaticGraphProjectionRegistrationAfterTransientFailure() {
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        when(eventStoreClient.registerModelGraphProjection(
                any())).thenReturn(
                CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "runtime temporarily unavailable")),
                CompletableFuture.completedFuture(
                        new ModelGraphProjectionStatus(
                                0L, "retryRoots",
                                -1L, -1L,
                                0L, 0L, false)));
        JacksonSerializer serializer =
                new JacksonSerializer();
        ModelActionHandlerRegistry subject =
                new ModelActionHandlerRegistry(
                        mock(DefaultModelRepository.class),
                        eventStoreClient,
                        mock(DocumentStore.class),
                        serializer,
                        mock(DocumentSerializer.class),
                        DispatchInterceptor.noOp,
                        "test",
                        List.of(),
                        HandlerDecorator.noOp);

        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);

        verify(eventStoreClient, times(2))
                .registerModelGraphProjection(
                        any());
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "retryRoots"))
    private record RetryRoot(
            @EntityId String id) {
    }
}
