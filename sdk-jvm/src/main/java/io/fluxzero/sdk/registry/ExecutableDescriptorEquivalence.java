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

import java.util.Optional;

/**
 * Compares executable descriptors that may come from mixed source and classpath registry metadata.
 */
public final class ExecutableDescriptorEquivalence {
    private ExecutableDescriptorEquivalence() {
    }

    public static boolean equivalent(
            Class<?> leftMetadataType, ExecutableDescriptor left,
            Class<?> rightMetadataType, ExecutableDescriptor right) {
        if (left.kind() != right.kind()
            || !left.name().equals(right.name())
            || left.parameters().size() != right.parameters().size()) {
            return false;
        }
        for (int i = 0; i < left.parameters().size(); i++) {
            if (!equivalentParameterType(
                    leftMetadataType, left.parameters().get(i).typeName(),
                    rightMetadataType, right.parameters().get(i).typeName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean equivalentParameterType(
            Class<?> leftMetadataType, String leftTypeName, Class<?> rightMetadataType, String rightTypeName) {
        if (leftTypeName.equals(rightTypeName)) {
            return true;
        }
        Optional<Class<?>> leftType = JvmComponentMetadataLookup.classForMetadataName(
                leftTypeName, leftMetadataType.getClassLoader());
        Optional<Class<?>> rightType = JvmComponentMetadataLookup.classForMetadataName(
                rightTypeName, rightMetadataType.getClassLoader());
        if (leftType.isPresent() && leftType.equals(rightType)) {
            return true;
        }
        return simpleTypeName(leftTypeName).equals(simpleTypeName(rightTypeName))
               && (leftType.isPresent() || rightType.isPresent());
    }

    private static String simpleTypeName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }
}
