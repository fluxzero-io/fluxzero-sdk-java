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
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityParameterResolverTest {

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForHandlerKind() throws Exception {
        Executable command = HandlerKindHandler.class.getDeclaredMethod("handleCommand", Entity.class);
        EntityParameterResolver resolver =
                new EntityParameterResolver(true, (executable, annotationType) -> Optional.empty());

        GeneratedOnlyMetadataMode.run(() ->
                assertTrue(resolver.mayApply(command, HandlerKindHandler.class)));
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForHandlerKind() throws Exception {
        Executable command = HandlerKindHandler.class.getDeclaredMethod("handleCommand", Entity.class);
        Executable event = HandlerKindHandler.class.getDeclaredMethod("handleEvent", Entity.class);
        Executable notification = HandlerKindHandler.class.getDeclaredMethod("handleNotification", Entity.class);
        EntityParameterResolver resolver = new EntityParameterResolver();
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(HandlerKindHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                assertFalse(resolver.mayApply(command, HandlerKindHandler.class));
                assertTrue(resolver.mayApply(event, HandlerKindHandler.class));
                assertTrue(resolver.mayApply(notification, HandlerKindHandler.class));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    static class HandlerKindHandler {
        @HandleCommand
        void handleCommand(Entity<SampleAggregate> entity) {
        }

        @HandleEvent
        void handleEvent(Entity<SampleAggregate> entity) {
        }

        @HandleNotification
        void handleNotification(Entity<SampleAggregate> entity) {
        }
    }

    record SampleAggregate() {
    }
}
