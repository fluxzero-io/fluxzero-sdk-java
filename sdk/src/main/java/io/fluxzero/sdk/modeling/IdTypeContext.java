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

import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JavaType;
import io.fluxzero.common.reflection.ReflectionUtils;

import java.lang.reflect.Modifier;
import java.util.List;

/** Property-local wire binding. Structural metadata remains owned by ReflectionUtils. */
final class IdTypeContext {
    private final Class<?> declaredType;
    private final boolean idProperty;
    private final List<Class<?>> parents;
    private final Class<?> declaredModel;
    private final Class<?> declaredEntityType;
    private List<Class<?>> discoveredModels;

    IdTypeContext(JavaType type, BeanProperty property) {
        while (type != null && !Id.class.isAssignableFrom(type.getRawClass()) && type.getContentType() != null) {
            type = type.getContentType();
        }
        idProperty = type != null && Id.class.isAssignableFrom(type.getRawClass());
        declaredType = idProperty ? type.getRawClass() : Id.class;
        JavaType model = type == null ? null : type.findTypeParameters(Id.class).length == 1
                ? type.findTypeParameters(Id.class)[0] : null;
        declaredEntityType = model == null ? Object.class : model.getRawClass();
        declaredModel = isModel(declaredEntityType) ? declaredEntityType : null;
        Parent parent = property == null ? null : property.getAnnotation(Parent.class);
        if (parent != null && parent.value() != void.class && parent.types().length > 0) {
            throw new IllegalArgumentException("@Parent value and types are mutually exclusive");
        }
        parents = parent == null ? List.of() : parent.types().length > 0 ? List.of(parent.types())
                : parent.value() == void.class ? List.of() : List.of(parent.value());
    }

    boolean polymorphic() {
        return idProperty && (declaredType == Id.class || Modifier.isAbstract(declaredType.getModifiers()));
    }

    void validate(Id<?> value) {
        if (!declaredType.isInstance(value)) {
            throw new IllegalArgumentException("Id discriminator does not match the declared Id type");
        }
        if (!parents.isEmpty() && !parents.contains(value.getType())) {
            throw new IllegalArgumentException("Id model type is not allowed by @Parent");
        }
        if (!declaredEntityType.isAssignableFrom(value.getType())) {
            throw new IllegalArgumentException("Id discriminator conflicts with its declared entity type");
        }
    }

    static boolean isModel(Class<?> type) {
        return ReflectionUtils.getTypeMetadata(type).typeAnnotation(Model.class) != null;
    }

    static Class<?> modelIdType(Class<?> model) {
        Class<?> result = EntityMetadata.of(model).entityId().orElseThrow(() -> new IllegalArgumentException(
                "Model must declare a concrete Id @EntityId for polymorphic Id serialization")).type();
        if (!Id.class.isAssignableFrom(result) || result == Id.class || Modifier.isAbstract(result.getModifiers())) {
            throw new IllegalArgumentException("Model must declare a concrete Id @EntityId for polymorphic Id serialization");
        }
        return result;
    }

    Class<?> resolveModel(String name) {
        List<Class<?>> candidates = !parents.isEmpty() ? parents
                : declaredModel != null && ModelNames.name(declaredModel).equals(name)
                  ? List.of(declaredModel) : discoveredModels();
        List<Class<?>> matches = candidates.stream().filter(IdTypeContext::isModel)
                .filter(type -> declaredModel == null || declaredModel.isAssignableFrom(type))
                .filter(type -> ModelNames.name(type).equals(name)).distinct().toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("Unknown or ambiguous Model Id name: " + name);
        }
        return matches.getFirst();
    }

    private synchronized List<Class<?>> discoveredModels() {
        if (discoveredModels == null) {
            discoveredModels = ModelTypes.discover();
        }
        return discoveredModels;
    }

    Class<?> resolveClass(String name, ClassLoader classLoader) throws ClassNotFoundException {
        // Never let @class bypass a declared parent allowlist, nor initialize arbitrary classes from wire data.
        if (!parents.isEmpty()) {
            throw new IllegalArgumentException("@Parent Id requires a Model name, not @class");
        }
        Class<?> result = ReflectionUtils.loadClassWithoutInitialization(
                name, classLoader == null ? Id.class.getClassLoader() : classLoader);
        if (!Id.class.isAssignableFrom(result) || !declaredType.isAssignableFrom(result)
            || Modifier.isAbstract(result.getModifiers())) {
            throw new IllegalArgumentException("@class must identify a concrete compatible Id subtype");
        }
        return result;
    }
}
