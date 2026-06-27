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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Stateful;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DefaultHandlerRepositoryGeneratedOnlyMetadataTest {

    @Test
    void generatedOnlyModeDoesNotReadStatefulAnnotationWithoutRegistryMetadata() {
        GeneratedOnlyMetadataMode.run(() -> {
            HandlerRepository repository = DefaultHandlerRepository.handlerRepositorySupplier(
                    () -> null, null).apply(GeneratedOnlyBatchingHandler.class);

            assertEquals(DefaultHandlerRepository.class, repository.getClass());
        });
    }

    @Test
    void generatedOnlyModeCreatesBatchingRepositoryFromRegistryMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(GeneratedOnlyBatchingHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                HandlerRepository repository = DefaultHandlerRepository.handlerRepositorySupplier(
                        () -> null, null).apply(GeneratedOnlyBatchingHandler.class);

                assertInstanceOf(BatchingHandlerRepository.class, repository);
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Stateful(commitInBatch = true)
    private static class GeneratedOnlyBatchingHandler {
    }
}
