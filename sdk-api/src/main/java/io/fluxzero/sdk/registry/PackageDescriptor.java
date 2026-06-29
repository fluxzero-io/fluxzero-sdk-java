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
public final class PackageDescriptor {
    private final String packageName;
    private final String sourceFileName;
    private final List<AnnotationDescriptor> annotations;
    private final List<RegisteredTypeDescriptor> registeredTypes;
    private final ConsumerDescriptor consumer;
    private final Set<ComponentCapability> capabilities;

    public PackageDescriptor(
            String packageName,
            Path sourceFile,
            List<AnnotationDescriptor> annotations,
            List<RegisteredTypeDescriptor> registeredTypes,
            ConsumerDescriptor consumer,
            Set<ComponentCapability> capabilities) {
        this(packageName, pathName(sourceFile), annotations, registeredTypes, consumer, capabilities);
    }

    public PackageDescriptor(String packageName, Path sourceFile, Set<ComponentCapability> capabilities) {
        this(packageName, sourceFile, List.of(), List.of(), null, capabilities);
    }

    private PackageDescriptor(
            String packageName,
            String sourceFileName,
            List<AnnotationDescriptor> annotations,
            List<RegisteredTypeDescriptor> registeredTypes,
            ConsumerDescriptor consumer,
            Set<ComponentCapability> capabilities) {
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.sourceFileName = sourceFileName;
        this.annotations = RegistryCollections.immutableList(Objects.requireNonNull(annotations, "annotations"));
        this.registeredTypes = RegistryCollections.immutableList(
                Objects.requireNonNull(registeredTypes, "registeredTypes"));
        this.consumer = consumer;
        this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(capabilities, "capabilities")));
    }

    /**
     * Creates a descriptor from a browser-safe source label.
     */
    public static PackageDescriptor fromSourceName(
            String packageName,
            String sourceFileName,
            List<AnnotationDescriptor> annotations,
            List<RegisteredTypeDescriptor> registeredTypes,
            ConsumerDescriptor consumer,
            Set<ComponentCapability> capabilities) {
        return new PackageDescriptor(packageName, sourceFileName, annotations, registeredTypes, consumer, capabilities);
    }

    public String packageName() {
        return packageName;
    }

    public Path sourceFile() {
        return path(sourceFileName);
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public List<AnnotationDescriptor> annotations() {
        return annotations;
    }

    public List<RegisteredTypeDescriptor> registeredTypes() {
        return registeredTypes;
    }

    public ConsumerDescriptor consumer() {
        return consumer;
    }

    public Set<ComponentCapability> capabilities() {
        return capabilities;
    }

    /**
     * Returns source consumer metadata declared by the package.
     */
    public Optional<ConsumerDescriptor> consumerMetadata() {
        return Optional.ofNullable(consumer);
    }

    private static String pathName(Path path) {
        return path == null ? null : path.toString();
    }

    private static Path path(String pathName) {
        return pathName == null ? null : Path.of(pathName);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PackageDescriptor that)) {
            return false;
        }
        return packageName.equals(that.packageName)
               && Objects.equals(sourceFileName, that.sourceFileName)
               && annotations.equals(that.annotations)
               && registeredTypes.equals(that.registeredTypes)
               && Objects.equals(consumer, that.consumer)
               && capabilities.equals(that.capabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, sourceFileName, annotations, registeredTypes, consumer, capabilities);
    }

    @Override
    public String toString() {
        return "PackageDescriptor[packageName=" + packageName
               + ", sourceFile=" + sourceFileName
               + ", annotations=" + annotations
               + ", registeredTypes=" + registeredTypes
               + ", consumer=" + consumer
               + ", capabilities=" + capabilities + "]";
    }
}
