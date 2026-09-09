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

package io.fluxzero.common.serialization;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.util.Set;
import java.util.function.Supplier;

/**
 * A {@link PolymorphicTypeValidator} that restricts deserialization to types from trusted packages
 * or types registered in the {@link TypeRegistry}.
 * <p>
 * This prevents arbitrary class instantiation via the {@code @class} field in JSON, which could
 * otherwise be exploited to achieve remote code execution through Jackson deserialization gadgets.
 */
public class AllowlistTypeValidator extends PolymorphicTypeValidator.Base {

    static final AllowlistTypeValidator instance = new AllowlistTypeValidator();

    private static final Set<String> ALLOWED_PACKAGE_PREFIXES = Set.of(
            "io.fluxzero.",
            "com.fasterxml.jackson.",
            "java.util.",
            "java.math.",
            "java.time.",
            "[" // array types
    );

    private static final Set<String> ALLOWED_CLASSES = Set.of(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Number",
            "java.lang.Short",
            "java.lang.String"
    );

    private final Supplier<TypeRegistry> typeRegistry;

    AllowlistTypeValidator() {
        this(DefaultTypeRegistry::new);
    }

    AllowlistTypeValidator(Supplier<TypeRegistry> typeRegistry) {
        this.typeRegistry = typeRegistry;
    }

    @Override
    public Validity validateBaseType(MapperConfig<?> config, JavaType baseType) {
        return Validity.INDETERMINATE;
    }

    boolean isAllowed(String className) {
        if (ALLOWED_CLASSES.contains(className)) {
            return true;
        }
        for (String prefix : ALLOWED_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return typeRegistry.get().isRegistered(className);
    }

    @Override
    public Validity validateSubClassName(MapperConfig<?> config, JavaType baseType, String subClassName) {
        return isAllowed(subClassName) ? Validity.ALLOWED : Validity.DENIED;
    }

    @Override
    public Validity validateSubType(MapperConfig<?> config, JavaType baseType, JavaType subType) {
        return isAllowed(subType.getRawClass().getName()) ? Validity.ALLOWED : Validity.DENIED;
    }
}
