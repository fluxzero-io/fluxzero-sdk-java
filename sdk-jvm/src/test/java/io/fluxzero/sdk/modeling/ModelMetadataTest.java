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

import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.GeneratedPropertyAccesses;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelMetadataTest {

    @Test
    void readsModelingAnnotationAttributesAsMetadataConfigs() throws Exception {
        var member = ModelMetadata.member(MetadataAggregate.class.getDeclaredField("children")).orElseThrow();
        var alias = ModelMetadata.alias(MetadataChild.class.getDeclaredField("alias")).orElseThrow();
        var apply = ModelMetadata.apply(MetadataUpdate.class.getDeclaredMethod("apply")).orElseThrow();

        assertEquals("customId", member.idProperty());
        assertEquals("withChildren", member.wither());
        assertEquals("pre-", alias.prefix());
        assertEquals("-post", alias.postfix());
        assertTrue(apply.disableCompatibilityCheck());
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForApplyMetadata() throws Exception {
        class LocalUnregisteredGeneratedOnlyUpdate {
            @Apply(disableCompatibilityCheck = true)
            void apply() {
            }
        }

        var method = LocalUnregisteredGeneratedOnlyUpdate.class.getDeclaredMethod("apply");

        GeneratedOnlyMetadataMode.run(() -> assertTrue(ModelMetadata.apply(method).isEmpty()));
    }

    @Test
    void generatedOnlyModeUsesRegisteredApplyMetadata() throws Exception {
        var method = RegisteredGeneratedOnlyUpdate.class.getDeclaredMethod("apply");
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyUpdate.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                var apply = ModelMetadata.apply(method).orElseThrow();

                assertTrue(apply.disableCompatibilityCheck());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeDoesNotUseReflectionFallbackForPropertyMetadata() throws Exception {
        class LocalUnregisteredGeneratedOnlyChild {
            @Alias(prefix = "pre-", postfix = "-post")
            private String alias;
        }

        class LocalUnregisteredGeneratedOnlyAggregate {
            @Member(idProperty = "customId", wither = "withChildren")
            private LocalUnregisteredGeneratedOnlyChild children;
        }

        var member = LocalUnregisteredGeneratedOnlyAggregate.class.getDeclaredField("children");
        var alias = LocalUnregisteredGeneratedOnlyChild.class.getDeclaredField("alias");

        GeneratedOnlyMetadataMode.run(() -> {
            assertTrue(ModelMetadata.member(member).isEmpty());
            assertTrue(ModelMetadata.alias(alias).isEmpty());
            assertTrue(ModelMetadata.annotatedPropertyLocations(
                    LocalUnregisteredGeneratedOnlyAggregate.class, Member.class).isEmpty());
            assertTrue(ModelMetadata.annotatedPropertyName(
                    LocalUnregisteredGeneratedOnlyAggregate.class, Member.class).isEmpty());
            assertFalse(ModelMetadata.hasAnnotatedProperty(
                    LocalUnregisteredGeneratedOnlyAggregate.class, Member.class));
        });
    }

    @Test
    void generatedOnlyModeUsesRegisteredPropertyMetadata() throws Exception {
        var memberField = RegisteredGeneratedOnlyAggregate.class.getDeclaredField("children");
        var aliasField = RegisteredGeneratedOnlyChild.class.getDeclaredField("alias");
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(
                            RegisteredGeneratedOnlyAggregate.class, RegisteredGeneratedOnlyChild.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                var member = ModelMetadata.member(memberField).orElseThrow();
                var alias = ModelMetadata.alias(aliasField).orElseThrow();

                assertEquals("customId", member.idProperty());
                assertEquals("withChildren", member.wither());
                assertEquals("pre-", alias.prefix());
                assertEquals("-post", alias.postfix());
                assertEquals(List.of(memberField), ModelMetadata.annotatedPropertyLocations(
                        RegisteredGeneratedOnlyAggregate.class, Member.class));
                assertEquals("children", ModelMetadata.annotatedPropertyName(
                        RegisteredGeneratedOnlyAggregate.class, Member.class).orElseThrow());
                assertTrue(ModelMetadata.hasAnnotatedProperty(
                        RegisteredGeneratedOnlyAggregate.class, Member.class));
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void generatedOnlyModeReadsAnnotatedPropertyValuesThroughGeneratedAccessors() {
        RegisteredGeneratedOnlyAssertLegalAggregate aggregate = new RegisteredGeneratedOnlyAssertLegalAggregate();
        RegisteredGeneratedOnlyAssertLegalChild generatedChild = new RegisteredGeneratedOnlyAssertLegalChild();

        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(
                            RegisteredGeneratedOnlyAssertLegalAggregate.class,
                            RegisteredGeneratedOnlyAssertLegalChild.class).registry());
            ComponentMetadataLookups.ensureGeneratedExecutions(RegisteredGeneratedOnlyAssertLegalAggregate.class);

            try (var ignored = GeneratedPropertyAccesses.registerReader(
                    RegisteredGeneratedOnlyAssertLegalAggregate.class, "child", ignoredTarget -> generatedChild)) {
                GeneratedOnlyMetadataMode.run(() -> assertEquals(
                        List.of(generatedChild),
                        ModelMetadata.annotatedPropertyValues(aggregate, AssertLegal.class)));
            }
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static class MetadataAggregate {
        @Member(idProperty = "customId", wither = "withChildren")
        private MetadataChild children;
    }

    private static class MetadataChild {
        @Alias(prefix = "pre-", postfix = "-post")
        private String alias;
    }

    private static class MetadataUpdate {
        @Apply(disableCompatibilityCheck = true)
        void apply() {
        }
    }

    private static class RegisteredGeneratedOnlyUpdate {
        @Apply(disableCompatibilityCheck = true)
        void apply() {
        }
    }

    private static class RegisteredGeneratedOnlyAggregate {
        @Member(idProperty = "customId", wither = "withChildren")
        private RegisteredGeneratedOnlyChild children;
    }

    private static class RegisteredGeneratedOnlyChild {
        @Alias(prefix = "pre-", postfix = "-post")
        private String alias;
    }

    private static class RegisteredGeneratedOnlyAssertLegalAggregate {
        @AssertLegal
        private RegisteredGeneratedOnlyAssertLegalChild child;
    }

    private static class RegisteredGeneratedOnlyAssertLegalChild {
    }
}
