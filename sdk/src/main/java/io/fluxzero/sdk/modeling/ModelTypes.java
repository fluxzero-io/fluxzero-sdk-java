/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.reflection.ReflectionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

/** Compile-time Model discovery, independent of optional serialized type-name aliases. */
public final class ModelTypes {
    /** Resource contributed by each module compiling {@link Model} types. */
    public static final String INDEX = io.fluxzero.common.modeling.ModelTypeProcessor.TYPES_FILE;

    private ModelTypes() {
    }

    /**
     * Reads the Model indexes visible to the SDK. This discovers types, not message handlers.
     * Application catalogs retain the result for their own lifecycle; structural reflection uses ReflectionUtils.
     * Missing indexed classes fail explicitly instead of silently producing an incomplete catalog.
     * Abstract/interface contracts with an identity are included; identity-less inheritance templates are not
     * standalone Models and are omitted from the runtime catalog.
     */
    public static List<Class<?>> discover() {
        TreeSet<String> names = new TreeSet<>();
        ClassLoader classLoader = Model.class.getClassLoader();
        try {
            var resources = classLoader.getResources(INDEX);
            while (resources.hasMoreElements()) {
                var resource = resources.nextElement();
                try (var reader = new BufferedReader(new InputStreamReader(
                        resource.openStream(), StandardCharsets.UTF_8))) {
                    reader.lines().filter(name -> !name.isBlank()).forEach(names::add);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the application's Model type indexes", e);
        }
        return names.stream().<Class<?>>map(name -> {
            Class<?> type;
            try {
                type = ReflectionUtils.loadClassWithoutInitialization(name, classLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Indexed Model class '%s' is unavailable; check the contract JAR and %s"
                                                        .formatted(name, INDEX), e);
            }
            if (ReflectionUtils.getTypeMetadata(type).typeAnnotation(Model.class) == null) {
                throw new IllegalStateException("Indexed class '%s' is not a Model; rebuild its contract JAR"
                                                        .formatted(name));
            }
            return type;
        }).filter(type -> !(type.isInterface() || Modifier.isAbstract(type.getModifiers()))
                          || !ReflectionUtils.getTypeMetadata(type).annotatedProperties(EntityId.class).isEmpty())
                .<Class<?>>map(type -> {
                    EntityMetadata.of(type); // Validate actual contracts, including abstract/interface identities.
                    return type;
                }).toList();
    }
}
