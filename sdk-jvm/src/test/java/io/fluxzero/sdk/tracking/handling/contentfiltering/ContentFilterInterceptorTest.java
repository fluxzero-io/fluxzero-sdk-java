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

package io.fluxzero.sdk.tracking.handling.contentfiltering;

import io.fluxzero.common.TestUtils;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Method;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentFilterInterceptorTest {

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void generatedOnlyModeDoesNotUseReflectionFallback() throws Exception {
        Serializer serializer = mock(Serializer.class);
        ContentFilterInterceptor interceptor = new ContentFilterInterceptor(serializer);
        HandlerInvoker invoker = HandlerInvoker.noOp(
                UnregisteredGeneratedOnlyFilteredQuery.class,
                handlerMethod(UnregisteredGeneratedOnlyFilteredQuery.class));

        TestUtils.runWithSystemProperties(() -> {
            Function<DeserializingMessage, Object> function = interceptor.interceptHandling(message -> "raw", invoker);

            assertEquals("raw", function.apply(null));
            verifyNoInteractions(serializer);
        }, ComponentMetadataLookups.METADATA_MODE_PROPERTY, ComponentMetadataLookups.GENERATED_ONLY_MODE);
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void generatedOnlyModeUsesRegisteredMetadata() throws Exception {
        Serializer serializer = mock(Serializer.class);
        when(serializer.filterContent(same("raw"), isNull())).thenReturn("filtered");
        ContentFilterInterceptor interceptor = new ContentFilterInterceptor(serializer);
        HandlerInvoker invoker = HandlerInvoker.noOp(
                RegisteredGeneratedOnlyFilteredQuery.class,
                handlerMethod(RegisteredGeneratedOnlyFilteredQuery.class));

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyFilteredQuery.class).registry());
            TestUtils.runWithSystemProperties(() -> {
                Function<DeserializingMessage, Object> function = interceptor.interceptHandling(message -> "raw",
                                                                                               invoker);

                assertEquals("filtered", function.apply(null));
                verify(serializer).filterContent(same("raw"), isNull());
            }, ComponentMetadataLookups.METADATA_MODE_PROPERTY, ComponentMetadataLookups.GENERATED_ONLY_MODE);
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static Method handlerMethod(Class<?> type) throws NoSuchMethodException {
        return type.getDeclaredMethod("handle");
    }

    private static class UnregisteredGeneratedOnlyFilteredQuery {
        @HandleQuery
        @FilterContent
        String handle() {
            return "raw";
        }
    }

    private static class RegisteredGeneratedOnlyFilteredQuery {
        @HandleQuery
        @FilterContent
        String handle() {
            return "raw";
        }
    }
}
