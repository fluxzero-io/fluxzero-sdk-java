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

package io.fluxzero.sdk.web;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebParamParameterResolverTest {

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForWebParams() throws Exception {
        class LocalUnregisteredWebParamHandler {
            @HandleGet("/items/{id}")
            String get(@PathParam("id") String id, @QueryParam("view") String view) {
                return id + ":" + view;
            }
        }

        var resolver = new WebParamParameterResolver();
        var method = LocalUnregisteredWebParamHandler.class.getDeclaredMethod("get", String.class, String.class);
        var parameter = method.getParameters()[0];

        GeneratedOnlyMetadataMode.run(() -> {
            assertFalse(resolver.mayApply(method, LocalUnregisteredWebParamHandler.class));
            assertFalse(resolver.matches(parameter, null, message()));
        });
    }

    @Test
    void generatedOnlyModeResolvesWebParamsFromRegistryMetadata() throws Exception {
        var resolver = new WebParamParameterResolver();
        var method = WebParamHandler.class.getDeclaredMethod("get", String.class, String.class);
        var pathParameter = method.getParameters()[0];
        var queryParameter = method.getParameters()[1];
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(WebParamHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                var message = message();

                assertTrue(resolver.mayApply(method, WebParamHandler.class));
                assertTrue(resolver.matches(pathParameter, null, message));
                assertEquals("42", resolver.resolve(pathParameter, null).apply(message));
                assertEquals("compact", resolver.resolve(queryParameter, null).apply(message));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static DeserializingMessage message() {
        var message = new DeserializingMessage(
                WebRequest.get("/items/42?view=compact").build(), MessageType.WEBREQUEST, null);
        DefaultWebRequestContext.getWebRequestContext(message).setPathMap(Map.of("id", "42"));
        return message;
    }

    private static class WebParamHandler {
        @HandleGet("/items/{id}")
        String get(@PathParam("id") String id, @QueryParam("view") String view) {
            return id + ":" + view;
        }
    }
}
