/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

import static org.junit.jupiter.api.Assertions.*;

class PolymorphicIdTest {
    private final JacksonSerializer serializer = new JacksonSerializer();

    @Test
    void parentRoundTripPreservesTypeForIdenticalFunctionalIds() {
        for (Id<?> id : new Id<?>[]{new ProjectId("owner-a"), new FolderId("owner-a")}) {
            var value = new Policy("policy-a", id);
            var data = serializer.serialize(value);
            assertEquals(value, serializer.deserialize(data, Policy.class));
            assertEquals(id.getClass(), serializer.deserialize(data, Policy.class).ownerId().getClass());
        }
        assertNotEquals(JsonUtils.asJson(new Policy("policy-a", new ProjectId("owner-a"))),
                        JsonUtils.asJson(new Policy("policy-a", new FolderId("owner-a"))));
    }

    @Test
    void usesLogicalModelName() {
        assertEquals("{\"policyId\":\"p\",\"ownerId\":{\"name\":\"poly-project\",\"id\":\"a\"}}",
                     JsonUtils.asJson(new Policy("p", new ProjectId("a"))));
    }

    @Test
    void nonModelIdUsesClassName() {
        var value = new Reference(new ExternalId("a"));
        assertEquals(value, serializer.deserialize(serializer.serialize(value), Reference.class));
        assertTrue(JsonUtils.asJson(value).contains("\"@class\":\"" + ExternalId.class.getName() + "\""));
    }

    @Test
    void abstractIdPropertiesKeepTheirConcreteSubtype() {
        var value = new AbstractReference(new DerivedId("a"));
        assertEquals(value, serializer.deserialize(serializer.serialize(value), AbstractReference.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"a\"", "{}", "{\"id\":\"a\"}", "{\"name\":\"missing\",\"id\":\"a\"}",
            "{\"name\":\"poly-project\",\"id\":5}",
            "{\"name\":\"poly-project\",\"@class\":\"java.lang.String\",\"id\":\"a\"}",
            "{\"name\":\"poly-project\",\"name\":\"poly-folder\",\"id\":\"a\"}",
            "{\"name\":\"poly-project\",\"id\":\"a\",\"extra\":true}",
            "{\"@class\":\"java.lang.String\",\"id\":\"a\"}"})
    void malformedParentFailsClosed(String id) {
        assertThrows(Exception.class, () -> JsonUtils.fromJson("{\"policyId\":\"p\",\"ownerId\":" + id + "}", Policy.class));
    }

    @Test
    void parentDisallowsClassFallbackAndDisallowedValues() {
        assertThrows(Exception.class, () -> JsonUtils.fromJson(
                "{\"ownerId\":{\"@class\":\"" + ProjectId.class.getName() + "\",\"id\":\"a\"}}", Policy.class));
        assertThrows(Exception.class, () -> serializer.serialize(new Policy("p", new ExternalId("a"))));
    }

    @Test
    void concreteWireAndLegacyObjectAreUnchanged() {
        var value = new Concrete(new ProjectId("a"));
        assertEquals("{\"id\":\"a\"}", JsonUtils.asJson(value));
        assertEquals(value, JsonUtils.fromJson("{\"id\":\"a\"}", Concrete.class));
        assertEquals(value, JsonUtils.fromJson("{\"id\":{\"functionalId\":\"a\"}}", Concrete.class));
        assertThrows(Exception.class, () -> JsonUtils.fromJson(
                "{\"id\":{\"name\":\"poly-folder\",\"id\":\"a\"}}", Concrete.class));
    }

    @Test
    void containersPreserveTheirValueTypes() {
        var value = new Containers(List.of(new ExternalId("a")), List.of(List.of(new ExternalId("b"))),
                                   Map.of(new ProjectId("key"), new ExternalId("value")));
        assertEquals(value, serializer.deserialize(serializer.serialize(value), Containers.class));
    }

    @Test
    void explicitTypeContractIsPreservedAndCannotBypassParentAllowlist() {
        var value = new Explicit(new ProjectId("a"));
        assertEquals(value, serializer.deserialize(serializer.serialize(value), Explicit.class));
        assertTrue(JsonUtils.asJson(value).contains("[\"project\",\"a\"]"));
        assertThrows(Exception.class, () -> JsonUtils.fromJson("{\"id\":[\"external\",\"a\"]}", Explicit.class));
        assertThrows(Exception.class, () -> JsonUtils.fromJson(
                "{\"id\":[\"project\",{\"name\":\"poly-folder\",\"id\":\"a\"}]}", Explicit.class));
    }

    @Test
    void typedModelIdNeedsNoParentAnnotation() {
        var value = new TypedReference(new ProjectId("a"));
        assertEquals(value, serializer.deserialize(serializer.serialize(value), TypedReference.class));
    }

    @Test
    void modelNamePrefixIsApplicationLocalEvenWithSharedMapper() {
        var value = new Policy("p", new ProjectId("a"));
        for (String prefix : List.of("first-", "second-")) {
            io.fluxzero.sdk.test.TestFixture.create()
                    .withProperty(io.fluxzero.sdk.configuration.ApplicationProperties.MODEL_NAME_PREFIX_PROPERTY, prefix)
                    .whenExecuting(fc -> {
                        var data = serializer.serialize(value);
                        assertTrue(new String(data.getValue(), java.nio.charset.StandardCharsets.UTF_8)
                                           .contains(prefix + "poly-project"));
                        assertEquals(value, serializer.deserialize(data, Policy.class));
                    }).expectSuccessfulResult().expectNoErrors();
        }
    }

    @Test
    void unboundedModelReferenceUsesModelIndex() {
        var value = new Reference(new ProjectId("a"));
        assertEquals(value, serializer.deserialize(serializer.serialize(value), Reference.class));
    }

    @Test
    void respectsConfiguredPolymorphicTypeValidator() {
        var mapper = JsonUtils.writer.rebuild().polymorphicTypeValidator(
                com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("some.other.package.").build()).build();
        assertThrows(Exception.class, () -> mapper.readValue(JsonUtils.asJson(new Reference(new ExternalId("a"))), Reference.class));
    }

    @Test
    void nullAndRootConcreteIdsRemainCompatible() throws Exception {
        assertEquals(new Reference(null), JsonUtils.fromJson("{}", Reference.class));
        assertEquals("\"a\"", JsonUtils.asJson(new ExternalId("a")));
        assertEquals(new ExternalId("a"), serializer.deserialize(serializer.serialize(new ExternalId("a")), ExternalId.class));
        assertEquals("{\"id\":\"a\"}", JsonUtils.asJson(new ObjectReference(new ExternalId("a"))));
    }

    @Test
    void moduleProvidedSerializerKeepsPrecedence() throws Exception {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(ExternalId.class, new com.fasterxml.jackson.databind.JsonSerializer<ExternalId>() {
            @Override
            public void serialize(ExternalId value, com.fasterxml.jackson.core.JsonGenerator generator,
                                  com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                generator.writeString("custom-" + value.getFunctionalId());
            }
        });
        var mapper = JsonUtils.writer.rebuild().addModule(module).build();
        assertEquals("{\"id\":\"custom-a\"}", mapper.writeValueAsString(new Reference(new ExternalId("a"))));
    }

    @Test
    void contentFilteringRetainsPolymorphicIdAndActuallyFilters() {
        var value = new Filtered(new ExternalId("a"), "secret");
        var filter = new io.fluxzero.sdk.common.serialization.jackson.JacksonContentFilter(JsonUtils.writer);
        assertEquals(new Filtered(new ExternalId("a"), null), filter.filterContent(value, null));
    }

    @Model(name = "poly-project")
    record Project(@EntityId ProjectId id) { }
    @Model(name = "poly-folder")
    record Folder(@EntityId FolderId id) { }
    @Model(name = "poly-policy")
    record Policy(@EntityId String policyId, @Parent(types = {Project.class, Folder.class}) Id<?> ownerId) { }
    record Reference(Id<?> id) { }
    record AbstractReference(AbstractId id) { }
    static abstract class AbstractId extends Id<String> {
        AbstractId(String id) { super(id, String.class); }
    }
    static final class DerivedId extends AbstractId {
        DerivedId(String id) { super(id); }
    }
    record ObjectReference(Object id) { }
    record Filtered(Id<?> id, String secret) {
        @io.fluxzero.sdk.common.serialization.FilterContent
        Filtered filter() { return new Filtered(id, null); }
    }
    record Concrete(ProjectId id) { }
    record TypedReference(Id<Project> id) { }
    record Containers(List<Id<?>> ids, List<List<Id<?>>> nested, Map<ProjectId, Id<?>> values) { }
    record Explicit(
            @Parent(types = {Project.class, Folder.class})
            @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
            @JsonSubTypes({@JsonSubTypes.Type(value = ProjectId.class, name = "project"),
                    @JsonSubTypes.Type(value = ExternalId.class, name = "external")}) Id<?> id) { }
    static final class ProjectId extends Id<Project> {
        ProjectId(String id) { super(id, "project-"); }
    }
    static final class FolderId extends Id<Folder> {
        FolderId(String id) { super(id, "folder-"); }
    }
    static final class ExternalId extends Id<String> {
        ExternalId(String id) { super(id); }
    }
}
