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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.serialization.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and writes the incubating Fluxzero component registry JSON format.
 * <p>
 * The JSON representation is intentionally plain: paths are strings, enums are names, repeated annotations are stored
 * in a registry-local pool, and handler routes reference component executables by stable executable id. This makes the
 * generated build artifact consumable by the SDK runtime, build tools, and external inspection utilities without
 * relying on Jackson type metadata.
 */
public final class ComponentRegistryJson {
    /**
     * Default classpath resource written by the build-time registry processor.
     */
    public static final String DEFAULT_RESOURCE = "META-INF/fluxzero/component-registry.json";
    private static final int LOAD_CACHE_SIZE = 64;
    private static final ObjectWriter REGISTRY_WRITER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .defaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .findAndAddModules()
            .build()
            .writer();
    private static final Map<IdentityClassLoaderKey, List<ComponentRegistry>> loadCache =
            Collections.synchronizedMap(new LinkedHashMap<>(LOAD_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<IdentityClassLoaderKey, List<ComponentRegistry>> eldest) {
                    return size() > LOAD_CACHE_SIZE;
                }
            });

    private ComponentRegistryJson() {
    }

    /**
     * Serializes a component registry to compact JSON.
     */
    public static String toJson(ComponentRegistry registry) {
        try {
            return REGISTRY_WRITER.writeValueAsString(toDto(registry));
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
        synchronized (loadCache) {
            return loadCache.computeIfAbsent(
                    new IdentityClassLoaderKey(effectiveClassLoader),
                    ignored -> loadUncached(effectiveClassLoader));
        }
    }

    static List<ComponentRegistry> load(Collection<ClassLoader> classLoaders) {
        Objects.requireNonNull(classLoaders, "classLoaders");
        List<ClassLoader> effectiveClassLoaders = classLoaders.stream()
                .map(classLoader -> classLoader == null ? ComponentRegistryJson.class.getClassLoader() : classLoader)
                .distinct()
                .toList();
        if (effectiveClassLoaders.isEmpty()) {
            return List.of();
        }
        return effectiveClassLoaders.size() == 1
                ? load(effectiveClassLoaders.getFirst())
                : loadUncached(effectiveClassLoaders);
    }

    private static List<ComponentRegistry> loadUncached(ClassLoader effectiveClassLoader) {
        return loadUncached(List.of(effectiveClassLoader));
    }

    private static List<ComponentRegistry> loadUncached(List<ClassLoader> effectiveClassLoaders) {
        try {
            Map<String, RegistryResource> resources = new LinkedHashMap<>();
            for (int i = 0; i < effectiveClassLoaders.size(); i++) {
                ClassLoader effectiveClassLoader = effectiveClassLoaders.get(i);
                Enumeration<URL> loaderResources = effectiveClassLoader.getResources(DEFAULT_RESOURCE);
                while (loaderResources.hasMoreElements()) {
                    URL resource = loaderResources.nextElement();
                    resources.putIfAbsent(
                            resource.toExternalForm(), new RegistryResource(resource, effectiveClassLoader, i));
                }
            }
            List<RegistryResource> orderedResources = new ArrayList<>(resources.values());
            orderedResources.sort(Comparator
                    .comparingInt(RegistryResource::loaderIndex)
                    .thenComparingInt(resource -> resourcePriority(resource.url(), resource.classLoader()))
                    .thenComparing(resource -> resource.url().toExternalForm()));
            List<ComponentRegistry> registries = new ArrayList<>();
            for (RegistryResource resource : orderedResources) {
                try (InputStream input = resource.url().openStream()) {
                    registries.add(read(input));
                }
            }
            return List.copyOf(registries);
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to load Fluxzero component registry resources", e);
        }
    }

    private record RegistryResource(URL url, ClassLoader classLoader, int loaderIndex) {
    }

    private static int resourcePriority(URL resource, ClassLoader classLoader) {
        if (!(classLoader instanceof URLClassLoader urlClassLoader)) {
            return 1;
        }
        String resourceUrl = resource.toExternalForm();
        for (URL root : urlClassLoader.getURLs()) {
            String rootUrl = root.toExternalForm();
            String directoryPrefix = rootUrl.endsWith("/") ? rootUrl : rootUrl + "/";
            if (resourceUrl.startsWith(directoryPrefix)
                || resourceUrl.startsWith("jar:" + rootUrl + "!/")) {
                return 0;
            }
        }
        return 1;
    }

    private static final class IdentityClassLoaderKey {
        private final ClassLoader classLoader;
        private final int hashCode;

        private IdentityClassLoaderKey(ClassLoader classLoader) {
            this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
            this.hashCode = System.identityHashCode(classLoader);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityClassLoaderKey key && classLoader == key.classLoader;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static RegistryDto toDto(ComponentRegistry registry) {
        return new SerializationContext().registry(Objects.requireNonNull(registry, "registry"));
    }

    private static ComponentRegistry fromDto(RegistryDto dto) {
        if (dto == null) {
            return ComponentRegistry.empty();
        }
        DeserializationContext context = new DeserializationContext(dto.annotations());
        return new ComponentRegistry(
                path(dto.sourceRoot()),
                list(dto.packages()).stream().map(context::packageDescriptor).toList(),
                list(dto.components()).stream().map(context::componentDescriptor).toList());
    }

    private static final class SerializationContext {
        private final Map<AnnotationDescriptor, Integer> annotationRefs = new LinkedHashMap<>();
        private final List<AnnotationDto> annotations = new ArrayList<>();

        private RegistryDto registry(ComponentRegistry registry) {
            List<PackageDto> packages = registry.packages().stream().map(this::packageDescriptor).toList();
            List<ComponentDto> components = registry.components().stream().map(this::componentDescriptor).toList();
            return new RegistryDto(
                    path(registry.sourceRoot()), listOrNull(annotations),
                    listOrNull(packages), listOrNull(components));
        }

        private PackageDto packageDescriptor(PackageDescriptor descriptor) {
            return new PackageDto(
                    descriptor.packageName(), path(descriptor.sourceFile()),
                    annotations(descriptor.annotations()),
                    listOrNull(descriptor.registeredTypes().stream().map(this::registeredType).toList()),
                    descriptor.consumer() == null ? null : consumer(descriptor.consumer()),
                    enumNamesOrNull(descriptor.capabilities()));
        }

        private ComponentDto componentDescriptor(ComponentDescriptor descriptor) {
            Set<String> executableIds = descriptor.executables().stream()
                    .map(ComponentRegistryJson::executableId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new ComponentDto(
                    path(descriptor.sourceFile()), path(descriptor.packageInfoSource()),
                    descriptor.componentKind() == ComponentKind.CLASS ? null : descriptor.componentKind().name(),
                    descriptor.packageName(), descriptor.className(),
                    listOrNull(descriptor.superTypeNames()),
                    annotations(descriptor.annotations()),
                    listOrNull(descriptor.properties().stream().map(this::property).toList()),
                    listOrNull(descriptor.executables().stream().map(this::executable).toList()),
                    listOrNull(descriptor.routes().stream().map(route -> handlerRoute(route, executableIds)).toList()),
                    listOrNull(descriptor.registeredTypes().stream().map(this::registeredType).toList()),
                    descriptor.consumer() == null ? null : consumer(descriptor.consumer()),
                    enumNamesOrNull(descriptor.capabilities()));
        }

        private HandlerRouteDto handlerRoute(HandlerRoute route, Set<String> componentExecutableIds) {
            String executableId = null;
            ExecutableDto executable = null;
            if (route.executable() != null) {
                String candidate = executableId(route.executable());
                if (componentExecutableIds.contains(candidate)) {
                    executableId = candidate;
                } else {
                    executable = executable(route.executable());
                }
            }

            String annotationRef = null;
            AnnotationDto annotation = null;
            if (route.annotation() != null) {
                if (route.executable() != null
                    && findAnnotation(route.executable().annotations(), route.annotation().qualifiedName()).isPresent()) {
                    annotationRef = route.annotation().qualifiedName();
                } else {
                    annotation = annotation(route.annotation());
                }
            }
            return new HandlerRouteDto(
                    route.messageType().name(), annotationRef, annotation, executableId, executable,
                    bool(route.disabled()), bool(route.passive()), bool(route.skipExpiredRequests()),
                    bool(route.local()), bool(route.tracked()),
                    setOrNull(route.payloadTypeNames()), setOrNull(route.allowedClassNames()),
                    listOrNull(route.webRoutes().stream().map(this::webRoute).toList()));
        }

        private AnnotationDto annotation(AnnotationDescriptor descriptor) {
            if (descriptor == null) {
                return null;
            }
            Integer ref = annotationRefs.get(descriptor);
            if (ref == null) {
                ref = annotations.size();
                annotationRefs.put(descriptor, ref);
                annotations.add(null);
                annotations.set(ref, new AnnotationDto(
                        null, descriptor.name(), descriptor.qualifiedName(),
                        mapOrNull(descriptor.attributes()),
                        nestedAnnotations(descriptor.nestedAnnotations()),
                        annotations(descriptor.metaAnnotations())));
            }
            return AnnotationDto.reference(ref);
        }

        private List<AnnotationDto> annotations(List<AnnotationDescriptor> descriptors) {
            return listOrNull(descriptors.stream().map(this::annotation).toList());
        }

        private Map<String, List<AnnotationDto>> nestedAnnotations(
                Map<String, List<AnnotationDescriptor>> nestedAnnotations) {
            if (nestedAnnotations.isEmpty()) {
                return null;
            }
            Map<String, List<AnnotationDto>> result = new LinkedHashMap<>();
            nestedAnnotations.forEach((name, annotations) -> result.put(name, annotations(annotations)));
            return mapOrNull(result);
        }

        private ExecutableDto executable(ExecutableDescriptor descriptor) {
            return new ExecutableDto(
                    descriptor.kind() == ExecutableKind.METHOD ? null : descriptor.kind().name(),
                    descriptor.name(), descriptor.returnTypeName(), typeUse(descriptor.returnTypeUse()),
                    listOrNull(descriptor.parameters().stream().map(this::parameter).toList()),
                    annotations(descriptor.annotations()),
                    bool(descriptor.isStatic()));
        }

        private PropertyDto property(PropertyDescriptor descriptor) {
            return new PropertyDto(
                    descriptor.name(), descriptor.typeName(),
                    Objects.equals(descriptor.genericTypeName(), descriptor.typeName()) ? null
                            : descriptor.genericTypeName(),
                    annotations(descriptor.annotations()), typeUse(descriptor.typeUse()));
        }

        private ParameterDto parameter(ParameterDescriptor descriptor) {
            return new ParameterDto(
                    descriptor.name(), descriptor.typeName(), annotations(descriptor.annotations()),
                    typeUse(descriptor.typeUse()));
        }

        private TypeUseDto typeUse(TypeUseDescriptor descriptor) {
            if (descriptor == null || descriptor.equals(TypeUseDescriptor.EMPTY)) {
                return null;
            }
            return new TypeUseDto(
                    descriptor.typeName(), annotations(descriptor.annotations()),
                    listOrNull(descriptor.typeArguments().stream().map(this::typeUse).toList()),
                    typeUse(descriptor.componentType()));
        }

        private ConsumerDto consumer(ConsumerDescriptor descriptor) {
            return new ConsumerDto(
                    descriptor.name(), mapOrNull(descriptor.attributes()), annotation(descriptor.annotation()));
        }

        private RegisteredTypeDto registeredType(RegisteredTypeDescriptor descriptor) {
            return new RegisteredTypeDto(
                    descriptor.root(), listOrNull(descriptor.contains()),
                    listOrNull(descriptor.candidateTypeNames()), annotation(descriptor.annotation()));
        }

        private WebRouteDto webRoute(WebRouteDescriptor descriptor) {
            return new WebRouteDto(
                    listOrNull(descriptor.paths()), listOrNull(descriptor.methods()),
                    bool(descriptor.autoHead()), bool(descriptor.autoOptions()));
        }
    }

    private static final class DeserializationContext {
        private final List<AnnotationDto> annotationPool;
        private final Map<Integer, AnnotationDescriptor> annotationCache = new LinkedHashMap<>();

        private DeserializationContext(List<AnnotationDto> annotationPool) {
            this.annotationPool = list(annotationPool);
        }

        private PackageDescriptor packageDescriptor(PackageDto dto) {
            return new PackageDescriptor(
                    dto.packageName(), path(dto.sourceFile()),
                    list(dto.annotations()).stream().map(this::annotation).toList(),
                    list(dto.registeredTypes()).stream().map(this::registeredType).toList(),
                    dto.consumer() == null ? null : consumer(dto.consumer()),
                    enums(dto.capabilities(), ComponentCapability.class));
        }

        private ComponentDescriptor componentDescriptor(ComponentDto dto) {
            List<ExecutableDescriptor> executables = list(dto.executables()).stream().map(this::executable).toList();
            List<HandlerRouteDto> handlerRoutes = list(dto.handlerRoutes());
            Map<String, ExecutableDescriptor> executablesById = handlerRoutes.isEmpty()
                    ? Map.of() : executablesById(executables);
            return new ComponentDescriptor(
                    path(dto.sourceFile()), path(dto.packageInfoSource()), componentKind(dto.componentKind()),
                    dto.packageName(), dto.className(), list(dto.superTypeNames()),
                    list(dto.annotations()).stream().map(this::annotation).toList(),
                    list(dto.properties()).stream().map(this::property).toList(),
                    executables,
                    set(handlerRoutes.stream().map(route -> handlerRoute(route, executablesById)).toList()),
                    list(dto.registeredTypes()).stream().map(this::registeredType).toList(),
                    dto.consumer() == null ? null : consumer(dto.consumer()),
                    enums(dto.capabilities(), ComponentCapability.class));
        }

        private HandlerRoute handlerRoute(HandlerRouteDto dto, Map<String, ExecutableDescriptor> executablesById) {
            ExecutableDescriptor executable = dto.executable() == null ? executable(dto.executableId(), executablesById)
                    : executable(dto.executable());
            AnnotationDescriptor annotation = dto.annotation() == null
                    ? annotation(dto.annotationRef(), executable) : annotation(dto.annotation());
            return new HandlerRoute(
                    MessageType.valueOf(dto.messageType()), annotation, executable,
                    Boolean.TRUE.equals(dto.disabled()), Boolean.TRUE.equals(dto.passive()),
                    Boolean.TRUE.equals(dto.skipExpiredRequests()), Boolean.TRUE.equals(dto.local()),
                    Boolean.TRUE.equals(dto.tracked()),
                    set(list(dto.payloadTypeNames())), set(list(dto.allowedClassNames())),
                    list(dto.webRoutes()).stream().map(this::webRoute).toList());
        }

        private ExecutableDescriptor executable(String executableId, Map<String, ExecutableDescriptor> executablesById) {
            if (executableId == null) {
                return null;
            }
            ExecutableDescriptor executable = executablesById.get(executableId);
            if (executable == null) {
                throw new ComponentRegistryException(
                        "Generated component registry route references missing executable: " + executableId);
            }
            return executable;
        }

        private Map<String, ExecutableDescriptor> executablesById(List<ExecutableDescriptor> executables) {
            if (executables.isEmpty()) {
                return Map.of();
            }
            Map<String, ExecutableDescriptor> result = new LinkedHashMap<>();
            for (ExecutableDescriptor executable : executables) {
                result.putIfAbsent(executableId(executable), executable);
            }
            return result;
        }

        private AnnotationDescriptor annotation(String annotationRef, ExecutableDescriptor executable) {
            if (annotationRef == null) {
                return null;
            }
            if (executable == null) {
                throw new ComponentRegistryException(
                        "Generated component registry route references annotation without executable: " + annotationRef);
            }
            return findAnnotation(executable.annotations(), annotationRef)
                    .orElseThrow(() -> new ComponentRegistryException(
                            "Generated component registry route references missing annotation: " + annotationRef));
        }

        private AnnotationDescriptor annotation(AnnotationDto dto) {
            if (dto == null) {
                return null;
            }
            if (dto.ref() != null) {
                return annotation(dto.ref());
            }
            return new AnnotationDescriptor(
                    dto.name(), dto.qualifiedName(), stringAttributes(dto.attributes()),
                    nestedAnnotations(dto.nestedAnnotations()),
                    list(dto.metaAnnotations()).stream().map(this::annotation).toList());
        }

        private AnnotationDescriptor annotation(int ref) {
            AnnotationDescriptor cached = annotationCache.get(ref);
            if (cached != null) {
                return cached;
            }
            if (ref < 0 || ref >= annotationPool.size()) {
                throw new ComponentRegistryException("Generated component registry annotation ref out of range: " + ref);
            }
            AnnotationDescriptor descriptor = annotation(annotationPool.get(ref));
            annotationCache.put(ref, descriptor);
            return descriptor;
        }

        private ExecutableDescriptor executable(ExecutableDto dto) {
            return new ExecutableDescriptor(
                    executableKind(dto.kind()), dto.name(), dto.returnTypeName(), typeUse(dto.returnTypeUse()),
                    list(dto.parameters()).stream().map(this::parameter).toList(),
                    list(dto.annotations()).stream().map(this::annotation).toList(),
                    Boolean.TRUE.equals(dto.isStatic()));
        }

        private PropertyDescriptor property(PropertyDto dto) {
            return new PropertyDescriptor(
                    dto.name(), dto.typeName(), dto.genericTypeName() == null ? dto.typeName() : dto.genericTypeName(),
                    list(dto.annotations()).stream().map(this::annotation).toList(), typeUse(dto.typeUse()));
        }

        private ParameterDescriptor parameter(ParameterDto dto) {
            return new ParameterDescriptor(
                    dto.name(), dto.typeName(), list(dto.annotations()).stream().map(this::annotation).toList(),
                    typeUse(dto.typeUse()));
        }

        private TypeUseDescriptor typeUse(TypeUseDto dto) {
            if (dto == null) {
                return TypeUseDescriptor.EMPTY;
            }
            return new TypeUseDescriptor(
                    dto.typeName(), list(dto.annotations()).stream().map(this::annotation).toList(),
                    list(dto.typeArguments()).stream().map(this::typeUse).toList(),
                    dto.componentType() == null ? null : typeUse(dto.componentType()));
        }

        private ConsumerDescriptor consumer(ConsumerDto dto) {
            return new ConsumerDescriptor(dto.name(), stringAttributes(dto.attributes()), annotation(dto.annotation()));
        }

        private RegisteredTypeDescriptor registeredType(RegisteredTypeDto dto) {
            return new RegisteredTypeDescriptor(
                    dto.root(), list(dto.contains()), list(dto.candidateTypeNames()), annotation(dto.annotation()));
        }

        private WebRouteDescriptor webRoute(WebRouteDto dto) {
            return new WebRouteDescriptor(
                    strings(dto.paths()), strings(dto.methods()),
                    Boolean.TRUE.equals(dto.autoHead()), Boolean.TRUE.equals(dto.autoOptions()));
        }

        private Map<String, List<AnnotationDescriptor>> nestedAnnotations(
                Map<String, List<AnnotationDto>> nestedAnnotations) {
            if (nestedAnnotations == null || nestedAnnotations.isEmpty()) {
                return Map.of();
            }
            return nestedAnnotations.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> list(entry.getValue()).stream().map(this::annotation).toList()));
        }
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

    private static <T> List<T> listOrNull(List<T> value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<String> strings(List<String> value) {
        return list(value).stream().map(item -> item == null ? "" : item).toList();
    }

    private static Map<String, List<String>> stringAttributes(Map<String, List<String>> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return value.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> strings(entry.getValue())));
    }

    private static <K, V> Map<K, V> mapOrNull(Map<K, V> value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static <T> Set<T> set(List<T> value) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(value == null ? List.of() : value));
    }

    private static <T> List<T> setOrNull(Set<T> value) {
        return value == null || value.isEmpty() ? null : List.copyOf(value);
    }

    private static <E extends Enum<E>> List<String> enumNames(Set<E> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private static <E extends Enum<E>> List<String> enumNamesOrNull(Set<E> values) {
        return listOrNull(enumNames(values));
    }

    private static <E extends Enum<E>> Set<E> enums(List<String> values, Class<E> enumType) {
        LinkedHashSet<E> result = new LinkedHashSet<>();
        for (String value : list(values)) {
            result.add(Enum.valueOf(enumType, value));
        }
        return Collections.unmodifiableSet(result);
    }

    private static Boolean bool(boolean value) {
        return value ? Boolean.TRUE : null;
    }

    private static ComponentKind componentKind(String value) {
        return value == null ? ComponentKind.CLASS : ComponentKind.valueOf(value);
    }

    private static ExecutableKind executableKind(String value) {
        return value == null ? ExecutableKind.METHOD : ExecutableKind.valueOf(value);
    }

    private static String executableId(ExecutableDescriptor executable) {
        return InvocationPlanDescriptor.executableId(
                executable.kind(), executable.name(),
                executable.parameters().stream().map(ParameterDescriptor::typeName).toList());
    }

    private static Optional<AnnotationDescriptor> findAnnotation(
            List<AnnotationDescriptor> annotations, String qualifiedName) {
        for (AnnotationDescriptor annotation : annotations) {
            if (Objects.equals(annotation.qualifiedName(), qualifiedName)) {
                return Optional.of(annotation);
            }
            Optional<AnnotationDescriptor> metaAnnotation = findAnnotation(annotation.metaAnnotations(), qualifiedName);
            if (metaAnnotation.isPresent()) {
                return metaAnnotation;
            }
            for (List<AnnotationDescriptor> nestedAnnotations : annotation.nestedAnnotations().values()) {
                Optional<AnnotationDescriptor> nestedAnnotation = findAnnotation(nestedAnnotations, qualifiedName);
                if (nestedAnnotation.isPresent()) {
                    return nestedAnnotation;
                }
            }
        }
        return Optional.empty();
    }

    private record RegistryDto(
            String sourceRoot, List<AnnotationDto> annotations, List<PackageDto> packages,
            List<ComponentDto> components) {
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
            String messageType, String annotationRef, AnnotationDto annotation, String executableId,
            ExecutableDto executable, Boolean disabled, Boolean passive, Boolean skipExpiredRequests, Boolean local,
            Boolean tracked, List<String> payloadTypeNames, List<String> allowedClassNames,
            List<WebRouteDto> webRoutes) {
    }

    private record AnnotationDto(
            Integer ref, String name, String qualifiedName, Map<String, List<String>> attributes,
            Map<String, List<AnnotationDto>> nestedAnnotations,
            List<AnnotationDto> metaAnnotations) {
        private static AnnotationDto reference(int ref) {
            return new AnnotationDto(ref, null, null, null, null, null);
        }
    }

    private record ExecutableDto(
            String kind, String name, String returnTypeName, TypeUseDto returnTypeUse, List<ParameterDto> parameters,
            List<AnnotationDto> annotations, Boolean isStatic) {
    }

    private record PropertyDto(
            String name, String typeName, String genericTypeName, List<AnnotationDto> annotations,
            TypeUseDto typeUse) {
    }

    private record ParameterDto(String name, String typeName, List<AnnotationDto> annotations, TypeUseDto typeUse) {
    }

    private record TypeUseDto(
            String typeName, List<AnnotationDto> annotations, List<TypeUseDto> typeArguments,
            TypeUseDto componentType) {
    }

    private record ConsumerDto(String name, Map<String, List<String>> attributes, AnnotationDto annotation) {
    }

    private record RegisteredTypeDto(
            String root, List<String> contains, List<String> candidateTypeNames, AnnotationDto annotation) {
    }

    private record WebRouteDto(List<String> paths, List<String> methods, Boolean autoHead, Boolean autoOptions) {
    }
}
