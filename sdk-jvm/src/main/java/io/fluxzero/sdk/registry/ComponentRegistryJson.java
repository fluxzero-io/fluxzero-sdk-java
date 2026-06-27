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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.serialization.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads and writes the incubating Fluxzero component registry JSON format.
 * <p>
 * The JSON representation is intentionally plain: paths are strings, enums are names, and descriptor collections are
 * regular arrays. This makes the generated build artifact consumable by the SDK runtime, build tools, and external
 * inspection utilities without relying on Jackson type metadata.
 */
public final class ComponentRegistryJson {
    /**
     * Default classpath resource written by the build-time registry processor.
     */
    public static final String DEFAULT_RESOURCE = "META-INF/fluxzero/component-registry.json";

    private ComponentRegistryJson() {
    }

    /**
     * Serializes a component registry to pretty-printed JSON.
     */
    public static String toJson(ComponentRegistry registry) {
        try {
            return JsonUtils.writer.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(registry));
        } catch (JsonProcessingException e) {
            throw new ComponentRegistryException("Failed to serialize Fluxzero component registry", e);
        }
    }

    /**
     * Deserializes a component registry from JSON.
     */
    public static ComponentRegistry fromJson(String json) {
        try {
            return fromDto(JsonUtils.writer.readValue(json, RegistryDto.class));
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to deserialize Fluxzero component registry", e);
        }
    }

    /**
     * Writes a component registry JSON file.
     */
    public static void write(ComponentRegistry registry, Path output) {
        Objects.requireNonNull(output, "output");
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, toJson(registry), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to write Fluxzero component registry: " + output, e);
        }
    }

    /**
     * Writes component registry JSON to a writer.
     */
    public static void write(ComponentRegistry registry, Writer writer) {
        Objects.requireNonNull(writer, "writer");
        try {
            writer.write(toJson(registry));
            writer.flush();
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to write Fluxzero component registry", e);
        }
    }

    /**
     * Reads a component registry JSON file.
     */
    public static ComponentRegistry read(Path input) {
        Objects.requireNonNull(input, "input");
        try {
            return fromJson(Files.readString(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to read Fluxzero component registry: " + input, e);
        }
    }

    /**
     * Reads component registry JSON from a stream.
     */
    public static ComponentRegistry read(InputStream input) {
        Objects.requireNonNull(input, "input");
        try {
            return fromDto(JsonUtils.writer.readValue(input, RegistryDto.class));
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to read Fluxzero component registry", e);
        }
    }

    /**
     * Loads all generated component registry resources visible to the context class loader.
     */
    public static List<ComponentRegistry> load() {
        return load(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Loads all generated component registry resources visible to the supplied class loader.
     */
    public static List<ComponentRegistry> load(ClassLoader classLoader) {
        ClassLoader effectiveClassLoader = classLoader == null
                ? ComponentRegistryJson.class.getClassLoader() : classLoader;
        try {
            Enumeration<URL> resources = effectiveClassLoader.getResources(DEFAULT_RESOURCE);
            List<ComponentRegistry> registries = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                try (InputStream input = resource.openStream()) {
                    registries.add(read(input));
                }
            }
            return List.copyOf(registries);
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to load Fluxzero component registry resources", e);
        }
    }

    private static RegistryDto toDto(ComponentRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return new RegistryDto(
                path(registry.sourceRoot()),
                registry.packages().stream().map(ComponentRegistryJson::toDto).toList(),
                registry.components().stream().map(ComponentRegistryJson::toDto).toList());
    }

    private static PackageDto toDto(PackageDescriptor descriptor) {
        return new PackageDto(
                descriptor.packageName(), path(descriptor.sourceFile()),
                descriptor.annotations().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.registeredTypes().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.consumer() == null ? null : toDto(descriptor.consumer()),
                enumNames(descriptor.capabilities()));
    }

    private static ComponentDto toDto(ComponentDescriptor descriptor) {
        return new ComponentDto(
                path(descriptor.sourceFile()), path(descriptor.packageInfoSource()),
                descriptor.componentKind().name(), descriptor.packageName(), descriptor.className(),
                descriptor.superTypeNames(),
                descriptor.annotations().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.properties().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.executables().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.routes().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.registeredTypes().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.consumer() == null ? null : toDto(descriptor.consumer()),
                enumNames(descriptor.capabilities()));
    }

    private static HandlerRouteDto toDto(HandlerRoute route) {
        return new HandlerRouteDto(
                route.messageType().name(), route.annotation() == null ? null : toDto(route.annotation()),
                route.executable() == null ? null : toDto(route.executable()), route.disabled(), route.passive(),
                route.skipExpiredRequests(), route.local(), route.tracked(), List.copyOf(route.payloadTypeNames()),
                List.copyOf(route.allowedClassNames()),
                route.webRoutes().stream().map(ComponentRegistryJson::toDto).toList());
    }

    private static AnnotationDto toDto(AnnotationDescriptor descriptor) {
        return new AnnotationDto(descriptor.name(), descriptor.qualifiedName(), descriptor.attributes());
    }

    private static ExecutableDto toDto(ExecutableDescriptor descriptor) {
        return new ExecutableDto(
                descriptor.kind().name(), descriptor.name(), descriptor.returnTypeName(),
                descriptor.parameters().stream().map(ComponentRegistryJson::toDto).toList(),
                descriptor.annotations().stream().map(ComponentRegistryJson::toDto).toList());
    }

    private static PropertyDto toDto(PropertyDescriptor descriptor) {
        return new PropertyDto(
                descriptor.name(), descriptor.typeName(), descriptor.genericTypeName(),
                descriptor.annotations().stream().map(ComponentRegistryJson::toDto).toList());
    }

    private static ParameterDto toDto(ParameterDescriptor descriptor) {
        return new ParameterDto(
                descriptor.name(), descriptor.typeName(),
                descriptor.annotations().stream().map(ComponentRegistryJson::toDto).toList());
    }

    private static ConsumerDto toDto(ConsumerDescriptor descriptor) {
        return new ConsumerDto(descriptor.name(), descriptor.attributes(), toDto(descriptor.annotation()));
    }

    private static RegisteredTypeDto toDto(RegisteredTypeDescriptor descriptor) {
        return new RegisteredTypeDto(
                descriptor.root(), descriptor.contains(), descriptor.candidateTypeNames(), toDto(descriptor.annotation()));
    }

    private static WebRouteDto toDto(WebRouteDescriptor descriptor) {
        return new WebRouteDto(descriptor.paths(), descriptor.methods(), descriptor.autoHead(), descriptor.autoOptions());
    }

    private static ComponentRegistry fromDto(RegistryDto dto) {
        if (dto == null) {
            return ComponentRegistry.empty();
        }
        return new ComponentRegistry(
                path(dto.sourceRoot()),
                list(dto.packages()).stream().map(ComponentRegistryJson::fromDto).toList(),
                list(dto.components()).stream().map(ComponentRegistryJson::fromDto).toList());
    }

    private static PackageDescriptor fromDto(PackageDto dto) {
        return new PackageDescriptor(
                dto.packageName(), path(dto.sourceFile()),
                list(dto.annotations()).stream().map(ComponentRegistryJson::fromDto).toList(),
                list(dto.registeredTypes()).stream().map(ComponentRegistryJson::fromDto).toList(),
                dto.consumer() == null ? null : fromDto(dto.consumer()),
                enums(dto.capabilities(), ComponentCapability.class));
    }

    private static ComponentDescriptor fromDto(ComponentDto dto) {
        return new ComponentDescriptor(
                path(dto.sourceFile()), path(dto.packageInfoSource()), ComponentKind.valueOf(dto.componentKind()),
                dto.packageName(), dto.className(), list(dto.superTypeNames()),
                list(dto.annotations()).stream().map(ComponentRegistryJson::fromDto).toList(),
                list(dto.properties()).stream().map(ComponentRegistryJson::fromDto).toList(),
                list(dto.executables()).stream().map(ComponentRegistryJson::fromDto).toList(),
                set(list(dto.handlerRoutes()).stream().map(ComponentRegistryJson::fromDto).toList()),
                list(dto.registeredTypes()).stream().map(ComponentRegistryJson::fromDto).toList(),
                dto.consumer() == null ? null : fromDto(dto.consumer()),
                enums(dto.capabilities(), ComponentCapability.class));
    }

    private static HandlerRoute fromDto(HandlerRouteDto dto) {
        return new HandlerRoute(
                MessageType.valueOf(dto.messageType()),
                dto.annotation() == null ? null : fromDto(dto.annotation()),
                dto.executable() == null ? null : fromDto(dto.executable()), dto.disabled(), dto.passive(),
                dto.skipExpiredRequests(), dto.local(), dto.tracked(),
                set(list(dto.payloadTypeNames())), set(list(dto.allowedClassNames())),
                list(dto.webRoutes()).stream().map(ComponentRegistryJson::fromDto).toList());
    }

    private static AnnotationDescriptor fromDto(AnnotationDto dto) {
        return new AnnotationDescriptor(dto.name(), dto.qualifiedName(), dto.attributes() == null ? Map.of() : dto.attributes());
    }

    private static ExecutableDescriptor fromDto(ExecutableDto dto) {
        return new ExecutableDescriptor(
                ExecutableKind.valueOf(dto.kind()), dto.name(), dto.returnTypeName(),
                list(dto.parameters()).stream().map(ComponentRegistryJson::fromDto).toList(),
                list(dto.annotations()).stream().map(ComponentRegistryJson::fromDto).toList());
    }

    private static PropertyDescriptor fromDto(PropertyDto dto) {
        return new PropertyDescriptor(
                dto.name(), dto.typeName(), dto.genericTypeName() == null ? dto.typeName() : dto.genericTypeName(),
                list(dto.annotations()).stream().map(ComponentRegistryJson::fromDto).toList());
    }

    private static ParameterDescriptor fromDto(ParameterDto dto) {
        return new ParameterDescriptor(
                dto.name(), dto.typeName(), list(dto.annotations()).stream().map(ComponentRegistryJson::fromDto).toList());
    }

    private static ConsumerDescriptor fromDto(ConsumerDto dto) {
        return new ConsumerDescriptor(dto.name(), dto.attributes() == null ? Map.of() : dto.attributes(), fromDto(dto.annotation()));
    }

    private static RegisteredTypeDescriptor fromDto(RegisteredTypeDto dto) {
        return new RegisteredTypeDescriptor(
                dto.root(), list(dto.contains()), list(dto.candidateTypeNames()), fromDto(dto.annotation()));
    }

    private static WebRouteDescriptor fromDto(WebRouteDto dto) {
        return new WebRouteDescriptor(list(dto.paths()), list(dto.methods()), dto.autoHead(), dto.autoOptions());
    }

    private static String path(Path path) {
        return path == null ? null : path.toString();
    }

    private static Path path(String path) {
        return path == null || path.isBlank() ? null : Path.of(path);
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static <T> Set<T> set(List<T> value) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(value == null ? List.of() : value));
    }

    private static <E extends Enum<E>> List<String> enumNames(Set<E> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private static <E extends Enum<E>> Set<E> enums(List<String> values, Class<E> enumType) {
        LinkedHashSet<E> result = new LinkedHashSet<>();
        for (String value : list(values)) {
            result.add(Enum.valueOf(enumType, value));
        }
        return Collections.unmodifiableSet(result);
    }

    private record RegistryDto(String sourceRoot, List<PackageDto> packages, List<ComponentDto> components) {
    }

    private record PackageDto(
            String packageName, String sourceFile, List<AnnotationDto> annotations,
            List<RegisteredTypeDto> registeredTypes, ConsumerDto consumer, List<String> capabilities) {
    }

    private record ComponentDto(
            String sourceFile, String packageInfoSource, String componentKind, String packageName, String className,
            List<String> superTypeNames,
            List<AnnotationDto> annotations, List<PropertyDto> properties,
            List<ExecutableDto> executables, List<HandlerRouteDto> handlerRoutes,
            List<RegisteredTypeDto> registeredTypes, ConsumerDto consumer, List<String> capabilities) {
    }

    private record HandlerRouteDto(
            String messageType, AnnotationDto annotation, ExecutableDto executable, boolean disabled, boolean passive,
            boolean skipExpiredRequests, boolean local, boolean tracked, List<String> payloadTypeNames,
            List<String> allowedClassNames, List<WebRouteDto> webRoutes) {
    }

    private record AnnotationDto(String name, String qualifiedName, Map<String, List<String>> attributes) {
    }

    private record ExecutableDto(
            String kind, String name, String returnTypeName, List<ParameterDto> parameters,
            List<AnnotationDto> annotations) {
    }

    private record PropertyDto(String name, String typeName, String genericTypeName, List<AnnotationDto> annotations) {
    }

    private record ParameterDto(String name, String typeName, List<AnnotationDto> annotations) {
    }

    private record ConsumerDto(String name, Map<String, List<String>> attributes, AnnotationDto annotation) {
    }

    private record RegisteredTypeDto(
            String root, List<String> contains, List<String> candidateTypeNames, AnnotationDto annotation) {
    }

    private record WebRouteDto(List<String> paths, List<String> methods, boolean autoHead, boolean autoOptions) {
    }
}
