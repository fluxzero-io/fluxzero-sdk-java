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
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.publishing.dataprotection.ProtectData;
import io.fluxzero.sdk.registry.compiled.CompiledPackageHandler;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole;
import io.fluxzero.sdk.web.HandleWeb;
import io.fluxzero.sdk.web.HttpRequestMethod;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private static String packageStrippedName(Class<?> type) {
        return type.getName().substring(type.getPackageName().length() + 1);
    }
}
