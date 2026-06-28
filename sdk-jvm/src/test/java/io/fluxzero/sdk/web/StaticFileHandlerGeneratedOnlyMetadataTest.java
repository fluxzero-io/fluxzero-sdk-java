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

import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.web.staticfixture.PackageStaticHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticFileHandlerGeneratedOnlyMetadataTest {
    @Test
    void generatedOnlyModeDoesNotDiscoverServeStaticWithoutRegistryMetadata() {
        @ServeStatic(value = "/static", resourcePath = "classpath:/web/static")
        class LocalUnregisteredStaticHandler {
        }

        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            assertTrue(StaticFileHandler.isHandler(LocalUnregisteredStaticHandler.class));
        }

        GeneratedOnlyMetadataMode.run(() -> {
            assertFalse(StaticFileHandler.isHandler(LocalUnregisteredStaticHandler.class));
            assertTrue(StaticFileHandler.forTargetClass(LocalUnregisteredStaticHandler.class).isEmpty());
        });
    }

    @Test
    void generatedOnlyModeUsesTypeServeStaticMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredStaticHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                assertTrue(StaticFileHandler.isHandler(RegisteredStaticHandler.class));

                StaticFileHandler handler =
                        StaticFileHandler.forTargetClass(RegisteredStaticHandler.class).getFirst();
                assertEquals("static", handler.getWebRoot());
                assertEquals("index.html", handler.getFallbackFile());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeUsesPackageServeStaticMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(PackageStaticHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                assertTrue(StaticFileHandler.isHandler(PackageStaticHandler.class));

                StaticFileHandler handler = StaticFileHandler.forTargetClass(PackageStaticHandler.class).getFirst();
                assertEquals("package-static", handler.getWebRoot());
                assertEquals("index.html", handler.getFallbackFile());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @ServeStatic(value = "/static", resourcePath = "classpath:/web/static")
    static class RegisteredStaticHandler {
    }
}
