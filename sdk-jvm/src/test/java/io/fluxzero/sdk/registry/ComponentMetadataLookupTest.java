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

package io.fluxzero.sdk.registry;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.SearchParameters;
import io.fluxzero.sdk.publishing.Timeout;
import io.fluxzero.sdk.publishing.dataprotection.ProtectData;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.registry.compiled.CompiledPackageHandler;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.unguarded.UnclassifiedJvmIntrospection;
import io.fluxzero.sdk.web.HandleWeb;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.SocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentMetadataLookupTest {

    @Test
    void jvmAndRegistryBackendsAnswerTheSameMetadataQuestions() {
        JvmComponentMetadataLookup jvm = JvmComponentMetadataLookup.scan(
                LookupHandler.class, LookupCommand.class, LookupQuery.class, LookupIdentityProvider.class);
        ComponentMetadataLookup registry = RegistryComponentMetadataLookup.of(jvm.registry());

        assertEquals("lookup", jvm.consumer(LookupHandler.class).orElseThrow().name());
        assertEquals("lookup", registry.consumer(LookupHandler.class.getName()).orElseThrow().name());

        assertTrue(jvm.capabilities(LookupHandler.class).contains(ComponentCapability.HANDLER));
        assertTrue(registry.capabilities(LookupHandler.class.getName()).contains(ComponentCapability.HANDLER));
        assertTrue(jvm.capabilities(LookupIdentityProvider.class).contains(ComponentCapability.IDENTITY_PROVIDER));
        assertTrue(registry.capabilities(LookupIdentityProvider.class.getName())
                           .contains(ComponentCapability.IDENTITY_PROVIDER));

        HandlerRoute jvmCommand = jvm.routes(LookupHandler.class, MessageType.COMMAND).getFirst();
        HandlerRoute registryCommand = registry.routes(LookupHandler.class.getName(), MessageType.COMMAND).getFirst();
        assertTrue(jvmCommand.allowedClassNames().contains(LookupCommand.class.getCanonicalName()));
        assertEquals(jvmCommand.payloadTypeNames(), registryCommand.payloadTypeNames());
        assertEquals(jvmCommand.local(), registryCommand.local());
        assertEquals(jvmCommand.tracked(), registryCommand.tracked());
        assertEquals("handle", registryCommand.executableMetadata().orElseThrow().name());

        assertEquals(Set.of(LookupQuery.class.getCanonicalName()),
                     registry.routes(MessageType.QUERY).getFirst().payloadTypeNames());
        assertFalse(registry.routes(LookupHandler.class.getName(), MessageType.QUERY).getFirst().tracked());

        PropertyDescriptor id = registry.property(LookupCommand.class.getName(), "id").orElseThrow();
        assertEquals(String.class.getName(), id.typeName());
        assertTrue(id.annotations().stream()
                           .anyMatch(annotation -> annotation.qualifiedName().equals(EntityId.class.getName())));
        assertTrue(registry.property(LookupCommand.class.getName(), "secret").orElseThrow().annotations().stream()
                .anyMatch(annotation -> annotation.qualifiedName().equals(ProtectData.class.getName())));

        assertEquals(packageStrippedName(LookupCommand.class),
                     registry.component(LookupCommand.class.getName()).orElseThrow().className());
        assertEquals("id", jvm.annotatedPropertyName(LookupCommand.class, EntityId.class).orElseThrow());
        assertTrue(jvm.hasTypeAnnotation(LookupHandler.class, LocalHandler.class));
        ComponentMetadataLookup packageLookup = JvmComponentMetadataLookup.scan(CompiledPackageHandler.class);
        assertEquals("compiled-package", packageLookup.packageMetadataChain(CompiledPackageHandler.class.getPackageName())
                .getFirst().consumerMetadata().orElseThrow().name());
    }

    @Test
    void resolvesDeepNestedCanonicalNamesToJvmClasses() {
        assertSame(Deeply.Nested.Component.class, JvmComponentMetadataLookup.classForMetadataName(
                Deeply.Nested.Component.class.getCanonicalName()).orElseThrow());
    }

    @Test
    void resolverPrefersRegistryBackedMetadataWhenRegistryContainsTypes() {
        ComponentRegistry registry = JvmComponentMetadataLookup.scan(LookupCommand.class).registry();

        ComponentMetadataLookup lookup = ComponentMetadataLookups.lookup(registry, LookupCommand.class).orElseThrow();

        assertInstanceOf(RegistryComponentMetadataLookup.class, lookup);
        assertEquals("id", lookup.properties(LookupCommand.class.getName()).stream()
                .filter(property -> property.annotations().stream()
                        .anyMatch(annotation -> annotation.isOrHas("EntityId", EntityId.class.getName())))
                .findFirst().orElseThrow().name());
    }

    @Test
    void resolverLoadsGeneratedRegistryResources(@TempDir Path tempDir) throws Exception {
        ComponentRegistry registry = JvmComponentMetadataLookup.scan(LookupCommand.class).registry();
        ComponentRegistryJson.write(registry, tempDir.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        try (URLClassLoader classLoader =
                     new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()},
                                        ComponentMetadataLookupTest.class.getClassLoader())) {
            ComponentMetadataLookup lookup =
                    ComponentMetadataLookups.lookupGenerated(classLoader, LookupCommand.class).orElseThrow();

            assertInstanceOf(RegistryComponentMetadataLookup.class, lookup);
            assertEquals("id", lookup.property(LookupCommand.class.getName(), "id").orElseThrow().name());
        }
    }

    @Test
    void resolverLoadsGeneratedRegistryResourcesFromContextClassLoader(@TempDir Path tempDir) throws Exception {
        @Timeout(15)
        class ContextOnlyCommand {
        }

        ComponentRegistry registry = JvmComponentMetadataLookup.scan(ContextOnlyCommand.class).registry();
        ComponentRegistryJson.write(registry, tempDir.resolve(ComponentRegistryJson.DEFAULT_RESOURCE));

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader primaryLoader = new URLClassLoader(new java.net.URL[0], null);
             URLClassLoader contextLoader =
                     new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()},
                                        ComponentMetadataLookupTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(contextLoader);

            ComponentMetadataLookup lookup =
                    ComponentMetadataLookups.lookupGenerated(primaryLoader, ContextOnlyCommand.class).orElseThrow();

            assertInstanceOf(RegistryComponentMetadataLookup.class, lookup);
            assertTrue(lookup.typeAnnotations(ContextOnlyCommand.class.getName()).stream()
                               .anyMatch(annotation -> annotation.qualifiedName().equals(Timeout.class.getName())));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void metadataModePropertySupportsExplicitJvmCompatibilityNames() throws Exception {
        withMetadataModeProperty(ComponentMetadataLookups.JVM_COMPATIBILITY_MODE, () -> {
            assertFalse(ComponentMetadataLookups.generatedOnlyMode());
            assertFalse(ComponentMetadataLookups.strictGeneratedOnlyMode());
            assertTrue(ComponentMetadataLookups.jvmCompatibilityMode());
            assertTrue(ComponentMetadataLookups.configuredJvmCompatibilityMode());
        });
        withMetadataModeProperty(ComponentMetadataLookups.COMPATIBILITY_MODE,
                                 () -> assertTrue(ComponentMetadataLookups.configuredJvmCompatibilityMode()));
        withMetadataModeProperty(ComponentMetadataLookups.HYBRID_MODE,
                                 () -> assertTrue(ComponentMetadataLookups.configuredJvmCompatibilityMode()));
    }

    @Test
    void scopedJvmCompatibilityModeAllowsIntentionalBackendAccessUnderGeneratedOnlyProperty() throws Exception {
        withMetadataModeProperty(ComponentMetadataLookups.GENERATED_ONLY_MODE, () -> {
            assertTrue(ComponentMetadataLookups.generatedOnlyMode());
            assertThrows(ComponentRegistryException.class, JvmCompatibilityBackend::introspector);

            JvmCompatibilityMetadataMode.run(() -> {
                assertFalse(ComponentMetadataLookups.generatedOnlyMode());
                assertFalse(ComponentMetadataLookups.strictGeneratedOnlyMode());
                assertTrue(ComponentMetadataLookups.jvmCompatibilityMode());
                assertSame(JvmComponentIntrospector.getInstance(), JvmCompatibilityBackend.introspector());
            });

            assertTrue(ComponentMetadataLookups.generatedOnlyMode());
        });
    }

    @Test
    void resolverUsesGeneratedRegistryForCompiledClasspathComponentsInGeneratedOnlyMode() {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            assertInstanceOf(RegistryComponentMetadataLookup.class,
                             ComponentMetadataLookups.lookup(RegisteredTimeoutRequest.class).orElseThrow());
        }

        GeneratedOnlyMetadataMode.run(() -> assertInstanceOf(
                RegistryComponentMetadataLookup.class,
                ComponentMetadataLookups.lookup(RegisteredTimeoutRequest.class).orElseThrow()));
    }

    @Test
    void generatedOnlyModeRejectsUnclassifiedJvmIntrospection() {
        assertThrows(ComponentRegistryException.class, () ->
                GeneratedOnlyMetadataMode.run(() -> UnclassifiedJvmIntrospection.inspect(this)));
    }

    @Test
    void registryProducerUsesGeneratedRegistryForCompiledClasspathComponentsInGeneratedOnlyMode() {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            assertFalse(ComponentMetadataLookups.registryFor(Set.of(RegisteredTimeoutRequest.class)).isEmpty());
        }

        GeneratedOnlyMetadataMode.run(() ->
                assertFalse(ComponentMetadataLookups.registryFor(Set.of(RegisteredTimeoutRequest.class))
                                   .isEmpty()));
    }

    @Test
    void resolverStillUsesRegistryMetadataInGeneratedOnlyMode() {
        ComponentRegistry registry = JvmComponentMetadataLookup.scan(LookupCommand.class).registry();

        GeneratedOnlyMetadataMode.run(() -> {
            ComponentMetadataLookup lookup =
                    ComponentMetadataLookups.lookup(registry, LookupCommand.class).orElseThrow();

            assertInstanceOf(RegistryComponentMetadataLookup.class, lookup);
            assertEquals("id", lookup.property(LookupCommand.class.getName(), "id").orElseThrow().name());
        });
    }

    @Test
    void typeAnnotationUsesGeneratedRegistryForCompiledClasspathComponentInGeneratedOnlyMode() {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            assertTrue(ComponentMetadataLookups.typeAnnotation(
                    RegisteredTimeoutRequest.class, Timeout.class).isPresent());
        }

        GeneratedOnlyMetadataMode.run(() ->
                assertTrue(ComponentMetadataLookups.typeAnnotation(
                        RegisteredTimeoutRequest.class, Timeout.class).isPresent()));
    }

    @Test
    void typeAnnotationUsesRegisteredMetadataInGeneratedOnlyMode() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredTimeoutRequest.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Timeout timeout = ComponentMetadataLookups.typeAnnotation(
                        RegisteredTimeoutRequest.class, Timeout.class).orElseThrow();

                assertEquals(15, timeout.value());
                assertEquals(TimeUnit.SECONDS, timeout.timeUnit());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void typeAnnotationProjectsMetaAnnotationToRequestedPolicyTypeInGeneratedOnlyMode() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredMetaUserRequest.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                RequiresUser requiresUser = ComponentMetadataLookups.typeAnnotation(
                        RegisteredMetaUserRequest.class, RequiresUser.class).orElseThrow();

                assertTrue(requiresUser.throwIfUnauthorized());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void packageAnnotationUsesRegisteredMetadataInGeneratedOnlyMode() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(CompiledPackageHandler.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                Consumer consumer = ComponentMetadataLookups.packageAnnotation(
                        CompiledPackageHandler.class, Consumer.class).orElseThrow();

                assertEquals("compiled-package", consumer.name());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void typeAnnotationProjectsNestedAnnotationAttributesFromMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredSocketEndpoint.class).registry());

            GeneratedOnlyMetadataMode.run(() -> {
                SocketEndpoint socketEndpoint = ComponentMetadataLookups.typeAnnotation(
                        RegisteredSocketEndpoint.class, SocketEndpoint.class).orElseThrow();

                assertFalse(socketEndpoint.aliveCheck().value());
                assertEquals(7, socketEndpoint.aliveCheck().pingDelay());
                assertEquals(3, socketEndpoint.aliveCheck().pingTimeout());
                assertEquals(TimeUnit.MILLISECONDS, socketEndpoint.aliveCheck().timeUnit());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    @Test
    void typeAnnotationAsProjectsMetaAnnotationAttributesFromMetadata() {
        ComponentMetadataLookup lookup = RegistryComponentMetadataLookup.of(
                JvmComponentMetadataLookup.scan(RegisteredSearchableAggregate.class).registry());

        SearchParameters parameters = ComponentMetadataLookups.typeAnnotationAs(
                lookup, RegisteredSearchableAggregate.class, Searchable.class, SearchParameters.class).orElseThrow();

        assertTrue(parameters.isSearchable());
        assertEquals("registered-search", parameters.getCollection());
        assertEquals("createdAt", parameters.getTimestampPath());
        assertEquals("deletedAt", parameters.getEndPath());
    }

    @Test
    void metadataLookupMatchesMetaAnnotationsWithoutJvmFallback() throws Exception {
        JvmComponentMetadataLookup lookup = JvmComponentMetadataLookup.scan(MetaLookupHandler.class, LookupCommand.class);
        Method command = MetaLookupHandler.class.getDeclaredMethod("command", LookupCommand.class);
        Method web = MetaLookupHandler.class.getDeclaredMethod("web");

        assertTrue(lookup.hasExecutableAnnotation(command, RequiresAnyRole.class));
        assertTrue(lookup.executableAnnotations(command).stream()
                           .anyMatch(annotation -> annotation.isOrHas(
                                   "RequiresAnyRole", RequiresAnyRole.class.getName())));
        assertTrue(lookup.hasExecutableAnnotation(web, HandleWeb.class));
        assertEquals("HandleWeb", lookup.routes(MetaLookupHandler.class, MessageType.WEBREQUEST)
                .getFirst().annotation().name());
    }

    private static void withMetadataModeProperty(String mode, ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty(ComponentMetadataLookups.METADATA_MODE_PROPERTY);
        System.setProperty(ComponentMetadataLookups.METADATA_MODE_PROPERTY, mode);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty(ComponentMetadataLookups.METADATA_MODE_PROPERTY);
            } else {
                System.setProperty(ComponentMetadataLookups.METADATA_MODE_PROPERTY, previous);
            }
        }
    }

    @Consumer(name = "lookup")
    @LocalHandler
    static class LookupHandler {
        @HandleCommand(allowedClasses = LookupCommand.class)
        void handle(LookupCommand command) {
        }

        @HandleQuery
        String handle(LookupQuery query) {
            return query.value();
        }
    }

    record LookupCommand(@EntityId String id, @ProtectData String secret) {
    }

    record LookupQuery(String value) {
    }

    static class LookupIdentityProvider implements IdentityProvider {
        @Override
        public String nextFunctionalId() {
            return "lookup";
        }

        @Override
        public String idForName(String name) {
            return name;
        }
    }

    static class MetaLookupHandler {
        @AdminOnly
        @HandleCommand
        void command(LookupCommand command) {
        }

        @MetaWeb
        String web() {
            return "ok";
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @RequiresAnyRole("admin")
    @interface AdminOnly {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @HandleWeb(value = "meta", method = HttpRequestMethod.GET)
    @interface MetaWeb {
    }

    static class Deeply {
        static class Nested {
            static class Component {
            }
        }
    }

    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    static class RegisteredTimeoutRequest {
    }

    @MetaRequiresUser
    static class RegisteredMetaUserRequest {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @RequiresUser
    @interface MetaRequiresUser {
    }

    @SocketEndpoint(aliveCheck = @SocketEndpoint.AliveCheck(
            value = false, timeUnit = TimeUnit.MILLISECONDS, pingDelay = 7, pingTimeout = 3))
    static class RegisteredSocketEndpoint {
    }

    @Aggregate(searchable = true, collection = "registered-search", timestampPath = "createdAt", endPath = "deletedAt")
    static class RegisteredSearchableAggregate {
    }

    private static String packageStrippedName(Class<?> type) {
        return type.getName().substring(type.getPackageName().length() + 1);
    }
}
