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
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.compiled.web.child.CompiledWebPathHandler;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUtilsTest {

    @Test
    void getsWebPatternsFromCompiledMetadataWithPackagePathStacking() throws Exception {
        var method = CompiledWebPathHandler.class.getDeclaredMethod("stacked");

        var result = WebUtils.getWebPatterns(CompiledWebPathHandler.class, null, method);

        assertEquals(1, result.size());
        assertEquals("/compiled/web-root/child/type/method/items", result.getFirst().getUri());
        assertEquals(GET, result.getFirst().getMethod());
    }

    @Test
    void fallsBackToRuntimePathPropertyForDynamicHandlerPath() throws Exception {
        var handler = new DynamicPathHandler("/tenant");
        var method = DynamicPathHandler.class.getDeclaredMethod("get");

        var result = WebUtils.getWebPatterns(DynamicPathHandler.class, handler, method);

        assertEquals(1, result.size());
        assertEquals("/tenant/items", result.getFirst().getUri());
        assertEquals(GET, result.getFirst().getMethod());
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForDynamicHandlerPath() throws Exception {
        var handler = new DynamicPathHandler("/tenant");
        var method = DynamicPathHandler.class.getDeclaredMethod("get");

        GeneratedOnlyMetadataMode.run(() ->
                assertTrue(WebUtils.getWebPatterns(DynamicPathHandler.class, handler, method).isEmpty()));
    }

    @Test
    void generatedOnlyModeUsesRegistryMetadataForDynamicHandlerPath() throws Exception {
        var handler = new DynamicPathHandler("/tenant");
        var method = DynamicPathHandler.class.getDeclaredMethod("get");
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(DynamicPathHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                var result = WebUtils.getWebPatterns(DynamicPathHandler.class, handler, method);

                assertEquals(1, result.size());
                assertEquals("/tenant/items", result.getFirst().getUri());
                assertEquals(GET, result.getFirst().getMethod());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static class DynamicPathHandler {
        @Path
        private final String root;

        private DynamicPathHandler(String root) {
            this.root = root;
        }

        @HandleGet("items")
        String get() {
            return "ok";
        }
    }
}
