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

import lombok.NonNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Package-level metadata discovered from source or compiled package annotations.
 *
 * @param packageName Java package name
 * @param sourceFile source file containing the package metadata, or {@code null} for classpath-scanned packages
 * @param annotations source annotations on the package declaration
 * @param registeredTypes source type registration metadata declared by the package
 * @param consumer source consumer metadata declared by the package
 * @param capabilities capabilities contributed by the package metadata
 */
public record PackageDescriptor(
        @NonNull String packageName,
        Path sourceFile,
        @NonNull List<AnnotationDescriptor> annotations,
        @NonNull List<RegisteredTypeDescriptor> registeredTypes,
        ConsumerDescriptor consumer,
        @NonNull Set<ComponentCapability> capabilities) {

    public PackageDescriptor {
        Objects.requireNonNull(packageName, "packageName");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        registeredTypes = List.copyOf(Objects.requireNonNull(registeredTypes, "registeredTypes"));
        capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(capabilities, "capabilities")));
    }

    public PackageDescriptor(String packageName, Path sourceFile, Set<ComponentCapability> capabilities) {
        this(packageName, sourceFile, List.of(), List.of(), null, capabilities);
    }

    /**
     * Returns source consumer metadata declared by the package.
     */
    public Optional<ConsumerDescriptor> consumerMetadata() {
        return Optional.ofNullable(consumer);
    }
}
