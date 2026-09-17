/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Embedded handlers authorize a change to their owning Model, never to a separate member stream. */
record EmbeddedModelPlan(Class<?> rootType, List<EntityMetadata.HandlerMethod> methods, boolean writes) {
    static boolean hasMembers(Class<?> root) { return !structure(root).types().isEmpty(); }

    boolean requiresStorageBoundary() {
        return structure(rootType).types().stream().anyMatch(type -> !EntityMetadata.of(type).assertionFields().isEmpty())
                || methods.stream().anyMatch(method -> method.kind() == EntityMetadata.HandlerKind.INTERCEPT_APPLY
                || method.modelParameters().stream().anyMatch(EntityMetadata.ModelParameter::graphWrapped)
                || method.kind() == EntityMetadata.HandlerKind.ASSERT_LEGAL
                   && method.executable() instanceof java.lang.reflect.Method executable && executable.getReturnType() != void.class);
    }
    static final class MemberMessage extends DeserializingMessage implements HasEntity {
        private final Entity<?> entity;
        MemberMessage(DeserializingMessage original, Entity<?> entity) {
            super(original);
            this.entity = entity;
        }
        @Override public Entity<?> getEntity() { return entity; }
    }
    static List<EmbeddedModelPlan> compile(Class<?> payloadType, Collection<Class<?>> roots) {
        List<EmbeddedModelPlan> result = new ArrayList<>();
        for (Class<?> root : new LinkedHashSet<>(roots)) {
            Structure structure = structure(root);
            if (structure.types().isEmpty()) { continue; }
            List<EntityMetadata.HandlerMethod> methods = new ArrayList<>(structure.types().stream()
                    .flatMap(type -> EntityMetadata.of(type).handlerMethods().stream())
                    .filter(method -> EntityMetadata.acceptsPayload(method, payloadType)).distinct().toList());
            EntityMetadata.of(payloadType).applyMethods().stream().filter(method ->
                    method.executable() instanceof java.lang.reflect.Method executable
                    && structure.types().stream().anyMatch(type -> type.isAssignableFrom(executable.getReturnType())))
                    .forEach(methods::add);
            boolean writes = methods.stream().anyMatch(m -> m.kind() != EntityMetadata.HandlerKind.ASSERT_LEGAL);
            boolean memberAssertion = EntityMetadata.of(payloadType).handlerMethods().stream()
                    .filter(method -> method.kind() == EntityMetadata.HandlerKind.ASSERT_LEGAL)
                    .anyMatch(method -> Arrays.stream(method.executable().getParameters())
                            .map(parameter -> Entity.class.isAssignableFrom(parameter.getType())
                                    ? ReflectionUtils.getCollectionElementType(parameter.getParameterizedType())
                                            .orElse(Object.class)
                                    : parameter.getType())
                            .anyMatch(parameter -> structure.types().stream().anyMatch(parameter::isAssignableFrom)));
            if (writes || memberAssertion || !methods.isEmpty() || structure.types().stream()
                    .anyMatch(type -> !EntityMetadata.of(type).assertionFields().isEmpty())) {
                result.add(new EmbeddedModelPlan(root, List.copyOf(methods), writes));
            }
        }
        return List.copyOf(result);
    }

    static List<EntityMetadata.HandlerMethod> methods(Class<?> root) {
        return structure(root).types().stream().flatMap(type -> EntityMetadata.of(type).handlerMethods().stream())
                .distinct().toList();
    }

    private static Structure structure(Class<?> root) {
        return ReflectionUtils.getTypeMetadata(root).specializedMetadata(Structure.class, Structure::new);
    }

    private record Structure(List<Class<?>> types) {
        Structure(Class<?> root) { this(find(root)); }

        private static List<Class<?>> find(Class<?> root) {
            Set<Class<?>> visited = new LinkedHashSet<>();
            visited.add(root);
            Set<Class<?>> result = new LinkedHashSet<>();
            collect(root, visited, result);
            return List.copyOf(result);
        }

        private static void collect(Class<?> type, Set<Class<?>> visited, Set<Class<?>> result) {
            for (var property : ReflectionUtils.getTypeMetadata(type).annotatedProperties(Member.class)) {
                Class<?> propertyType = ReflectionUtils.getPropertyType(property);
                Class<?> member = Collection.class.isAssignableFrom(propertyType) || Map.class.isAssignableFrom(propertyType)
                        ? ReflectionUtils.getCollectionElementType(property).orElse(propertyType) : propertyType;
                result.add(member);
                collectMember(member, visited, result);
            }
        }

        private static void collectMember(Class<?> member, Set<Class<?>> visited, Set<Class<?>> result) {
            if (!visited.add(member)) { return; }
            collect(member, visited, result);
            if (member.isSealed()) {
                for (Class<?> subtype : member.getPermittedSubclasses()) {
                    result.add(subtype);
                    collectMember(subtype, visited, result);
                }
            }
        }
    }
}
