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

package io.fluxzero.sdk.common;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.SearchParameters;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientUtilsTest {

    @Test
    void handleOnlyTracking() throws Exception {
        assertTrue(
                ClientUtils.isTrackingHandler(Handler.class, Handler.class.getDeclaredMethod("handle", String.class)));
        assertFalse(ClientUtils.getLocalHandlerAnnotation(Handler.class,
                                                          Handler.class.getDeclaredMethod("handle", String.class))
                            .isPresent());
    }

    @Test
    void handleOnlyLocal() throws Exception {
        assertTrue(ClientUtils.getLocalHandlerAnnotation(Handler.class,
                                                         Handler.class.getDeclaredMethod("handleOnlyLocal", int.class))
                           .isPresent());
        assertFalse(ClientUtils.isTrackingHandler(Handler.class,
                                                  Handler.class.getDeclaredMethod("handleOnlyLocal", int.class)));
    }

    @Test
    void handleLocalAndExternal() throws Exception {
        assertTrue(ClientUtils.getLocalHandlerAnnotation(Handler.class,
                                                         Handler.class.getDeclaredMethod("handleLocalOrExternal",
                                                                                         double.class)).isPresent());
        assertTrue(ClientUtils.isTrackingHandler(Handler.class, Handler.class.getDeclaredMethod("handleLocalOrExternal",
                                                                                                double.class)));
    }

    @Test
    void generatedOnlyModeUsesGeneratedMetadataForLocalHandlerSemantics() throws Exception {
        GeneratedOnlyMetadataMode.run(() -> {
            assertTrue(ClientUtils.getLocalHandlerAnnotation(
                    GeneratedClasspathHandler.class,
                    GeneratedClasspathHandler.class.getDeclaredMethod("handleOnlyLocal", int.class))
                    .isPresent());
            assertFalse(ClientUtils.isTrackingHandler(
                    GeneratedClasspathHandler.class,
                    GeneratedClasspathHandler.class.getDeclaredMethod("handleOnlyLocal", int.class)));
            assertTrue(ClientUtils.isSelfTracking(GeneratedClasspathTrackSelf.class));
        });
    }

    @Test
    void generatedOnlyModeUsesRegisteredMetadataForLocalHandlerSemantics() throws Exception {
        try {
            TestFixture fixture = TestFixture.create();
            fixture.getFluxzero().registerComponentRegistry(JvmComponentMetadataLookup.scan(
                    RegisteredGeneratedOnlyHandler.class, RegisteredGeneratedOnlyTrackSelf.class).registry());
            GeneratedOnlyMetadataMode.run(() -> {
                assertTrue(ClientUtils.getLocalHandlerAnnotation(
                        RegisteredGeneratedOnlyHandler.class,
                        RegisteredGeneratedOnlyHandler.class.getDeclaredMethod("handleOnlyLocal", int.class))
                        .isPresent());
                assertFalse(ClientUtils.isTrackingHandler(
                        RegisteredGeneratedOnlyHandler.class,
                        RegisteredGeneratedOnlyHandler.class.getDeclaredMethod("handleOnlyLocal", int.class)));
                assertTrue(ClientUtils.isSelfTracking(RegisteredGeneratedOnlyTrackSelf.class));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeUsesGeneratedMetadataForHandlerTopics() {
        GeneratedOnlyMetadataMode.run(() -> {
            assertEquals(Set.of("documents"), ClientUtils.getTopics(
                    MessageType.DOCUMENT, List.of(GeneratedClasspathTopicHandler.class)));
            assertEquals(Set.of("custom-topic"), ClientUtils.getTopics(
                    MessageType.CUSTOM, List.of(GeneratedClasspathTopicHandler.class)));
        });
    }

    @Test
    void generatedOnlyModeUsesRegisteredMetadataForHandlerTopics() {
        try {
            TestFixture fixture = TestFixture.create();
            fixture.getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyTopicHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                assertEquals(Set.of("documents"), ClientUtils.getTopics(
                        MessageType.DOCUMENT, List.of(RegisteredGeneratedOnlyTopicHandler.class)));
                assertEquals(Set.of("custom-topic"), ClientUtils.getTopics(
                        MessageType.CUSTOM, List.of(RegisteredGeneratedOnlyTopicHandler.class)));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeUsesGeneratedMetadataForSearchParameters() {
        GeneratedOnlyMetadataMode.run(() -> {
            SearchParameters parameters = ClientUtils.getSearchParameters(GeneratedClasspathSearchable.class);

            assertTrue(parameters.isSearchable());
            assertEquals("generated-search", parameters.getCollection());
            assertEquals("createdAt", parameters.getTimestampPath());
        });
    }

    @Test
    void generatedOnlyModeUsesRegisteredMetadataForSearchParameters() {
        try {
            TestFixture fixture = TestFixture.create();
            fixture.getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlySearchable.class).registry());

            fixture.getFluxzero().apply(fc -> {
                GeneratedOnlyMetadataMode.run(() -> {
                    SearchParameters parameters =
                            ClientUtils.getSearchParameters(RegisteredGeneratedOnlySearchable.class);

                    assertTrue(parameters.isSearchable());
                    assertEquals("registered-search", parameters.getCollection());
                    assertEquals("createdAt", parameters.getTimestampPath());
                    assertEquals("deletedAt", parameters.getEndPath());
                });
                return null;
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Nested
    class MemoizeTests {
        final AtomicInteger counter = new AtomicInteger();
        final ClientUtilsMemoizeHandler handler = new ClientUtilsMemoizeHandler(counter::incrementAndGet);
        final TestFixture testFixture = TestFixture.create(handler);

        @Test
        void memoizeWithLifespan_refreshValueAfterLifespan() {
            testFixture.givenCommands(new ClientUtilsMemoizeCommand()).givenElapsedTime(Duration.ofSeconds(15))
                    .whenCommand(new ClientUtilsMemoizeCommand()).expectResult(2);
        }

        @Test
        void memoizeWithLifespan_dontRefreshBeforeLifespan() {
            testFixture.givenCommands(new ClientUtilsMemoizeCommand()).givenElapsedTime(Duration.ofSeconds(5))
                    .whenCommand(new ClientUtilsMemoizeCommand()).expectResult(1);
        }

        @Test
        void memoizeWithLifespan_cleared() {
            testFixture.givenCommands(new ClientUtilsMemoizeCommand()).givenElapsedTime(Duration.ofSeconds(5))
                    .given(fc -> handler.clear())
                    .whenCommand(new ClientUtilsMemoizeCommand()).expectResult(2);
        }
    }

    private static class Handler {
        @HandleCommand
        String handle(String command) {
            return command;
        }

        @HandleCommand
        @LocalHandler
        int handleOnlyLocal(int command) {
            return command;
        }

        @HandleCommand
        @LocalHandler(allowExternalMessages = true)
        double handleLocalOrExternal(double command) {
            return command;
        }
    }

    private static class RegisteredGeneratedOnlyHandler {
        @HandleCommand
        @LocalHandler
        int handleOnlyLocal(int command) {
            return command;
        }
    }

    private static class GeneratedClasspathHandler {
        @HandleCommand
        @LocalHandler
        int handleOnlyLocal(int command) {
            return command;
        }
    }

    @TrackSelf
    private static class RegisteredGeneratedOnlyTrackSelf {
    }

    @TrackSelf
    private static class GeneratedClasspathTrackSelf {
    }

    private static class RegisteredGeneratedOnlyTopicHandler {
        @HandleDocument("documents")
        void handleDocument(Object document) {
        }

        @HandleCustom("custom-topic")
        void handleCustom(Object payload) {
        }

        @HandleCustom(value = "disabled-topic", disabled = true)
        void handleDisabledCustom(Object payload) {
        }
    }

    private static class GeneratedClasspathTopicHandler {
        @HandleDocument("documents")
        void handleDocument(Object document) {
        }

        @HandleCustom("custom-topic")
        void handleCustom(Object payload) {
        }
    }

    @Aggregate(searchable = true, collection = "registered-search", timestampPath = "createdAt", endPath = "deletedAt")
    private static class RegisteredGeneratedOnlySearchable {
    }

    @Aggregate(searchable = true, collection = "generated-search", timestampPath = "createdAt")
    private static class GeneratedClasspathSearchable {
    }
}
