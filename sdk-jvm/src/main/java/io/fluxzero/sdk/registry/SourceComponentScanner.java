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

import io.fluxzero.common.MessageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;

/**
 * Incubating scanner for local Java source files.
 * <p>
 * The scanner builds a Fluxzero component registry from source text only. It does not invoke javac, load classes, or
 * run annotation processors.
 */
public class SourceComponentScanner {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("\\bimport\\s+(?:static\\s+)?([\\w.]+)(\\.\\*)?\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\b(class|record|interface|enum)\\s+(\\w+)\\b");
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float", "Integer", "Long",
            "Object", "Short", "String", "Throwable", "Void");
    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void");
    private static final Set<String> MODIFIERS = Set.of(
            "public", "protected", "private", "abstract", "static", "final", "strictfp", "synchronized",
            "native", "default", "transient", "volatile", "sealed", "non-sealed");

    private static final Map<String, String> KNOWN_ANNOTATIONS = Map.ofEntries(
            entry("RegisterType", "io.fluxzero.common.serialization.RegisterType"),
            entry("Consumer", "io.fluxzero.sdk.tracking.Consumer"),
            entry("TrackSelf", "io.fluxzero.sdk.tracking.TrackSelf"),
            entry("LocalHandler", "io.fluxzero.sdk.tracking.handling.LocalHandler"),
            entry("HandleCommand", "io.fluxzero.sdk.tracking.handling.HandleCommand"),
            entry("HandleQuery", "io.fluxzero.sdk.tracking.handling.HandleQuery"),
            entry("HandleEvent", "io.fluxzero.sdk.tracking.handling.HandleEvent"),
            entry("HandleNotification", "io.fluxzero.sdk.tracking.handling.HandleNotification"),
            entry("HandleError", "io.fluxzero.sdk.tracking.handling.HandleError"),
            entry("HandleMetrics", "io.fluxzero.sdk.tracking.handling.HandleMetrics"),
            entry("HandleSchedule", "io.fluxzero.sdk.tracking.handling.HandleSchedule"),
            entry("HandleWeb", "io.fluxzero.sdk.web.HandleWeb"),
            entry("HandleGet", "io.fluxzero.sdk.web.HandleGet"),
            entry("HandlePost", "io.fluxzero.sdk.web.HandlePost"),
            entry("HandlePut", "io.fluxzero.sdk.web.HandlePut"),
            entry("HandlePatch", "io.fluxzero.sdk.web.HandlePatch"),
            entry("HandleDelete", "io.fluxzero.sdk.web.HandleDelete"),
            entry("HandleHead", "io.fluxzero.sdk.web.HandleHead"),
            entry("HandleOptions", "io.fluxzero.sdk.web.HandleOptions"),
            entry("HandleTrace", "io.fluxzero.sdk.web.HandleTrace"),
            entry("HandleSocketHandshake", "io.fluxzero.sdk.web.HandleSocketHandshake"),
            entry("HandleSocketOpen", "io.fluxzero.sdk.web.HandleSocketOpen"),
            entry("HandleSocketMessage", "io.fluxzero.sdk.web.HandleSocketMessage"),
            entry("HandleSocketPong", "io.fluxzero.sdk.web.HandleSocketPong"),
            entry("HandleSocketClose", "io.fluxzero.sdk.web.HandleSocketClose"),
            entry("Path", "io.fluxzero.sdk.web.Path"),
            entry("PathParam", "io.fluxzero.sdk.web.PathParam"),
            entry("QueryParam", "io.fluxzero.sdk.web.QueryParam"),
            entry("HeaderParam", "io.fluxzero.sdk.web.HeaderParam"),
            entry("CookieParam", "io.fluxzero.sdk.web.CookieParam"),
            entry("FormParam", "io.fluxzero.sdk.web.FormParam"),
            entry("BodyParam", "io.fluxzero.sdk.web.BodyParam"));

    private static final Map<String, String> KNOWN_TYPES = Map.ofEntries(
            entry("Cache", "io.fluxzero.common.caching.Cache"),
            entry("PropertySource", "io.fluxzero.common.application.PropertySource"),
            entry("TaskScheduler", "io.fluxzero.common.TaskScheduler"),
            entry("DispatchInterceptor", "io.fluxzero.sdk.publishing.DispatchInterceptor"),
            entry("HandlerDecorator", "io.fluxzero.sdk.tracking.handling.HandlerDecorator"),
            entry("HandlerInterceptor", "io.fluxzero.sdk.tracking.handling.HandlerInterceptor"),
            entry("BatchInterceptor", "io.fluxzero.sdk.tracking.BatchInterceptor"),
            entry("ResponseMapper", "io.fluxzero.sdk.tracking.handling.ResponseMapper"),
            entry("WebResponseMapper", "io.fluxzero.sdk.web.WebResponseMapper"),
            entry("Validator", "io.fluxzero.sdk.tracking.handling.validation.Validator"),
            entry("ParameterResolver", "io.fluxzero.common.handling.ParameterResolver"),
            entry("Serializer", "io.fluxzero.sdk.common.serialization.Serializer"),
            entry("JacksonSerializer", "io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer"),
            entry("DocumentSerializer", "io.fluxzero.sdk.persisting.search.DocumentSerializer"),
            entry("CorrelationDataProvider", "io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider"),
            entry("IdentityProvider", "io.fluxzero.sdk.common.IdentityProvider"),
            entry("UserProvider", "io.fluxzero.sdk.tracking.handling.authentication.UserProvider"),
            entry("AbstractUserProvider", "io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider"),
            entry("DefaultResponseMapper", "io.fluxzero.sdk.tracking.handling.DefaultResponseMapper"),
            entry("DefaultWebResponseMapper", "io.fluxzero.sdk.web.DefaultWebResponseMapper"),
            entry("SoftReferenceCache", "io.fluxzero.sdk.persisting.caching.SoftReferenceCache"),
            entry("InMemoryTaskScheduler", "io.fluxzero.common.InMemoryTaskScheduler"));

    private static final Map<String, ComponentCapability> INFRASTRUCTURE_CAPABILITIES = Map.ofEntries(
            entry("io.fluxzero.sdk.publishing.DispatchInterceptor", ComponentCapability.DISPATCH_INTERCEPTOR),
            entry("io.fluxzero.sdk.tracking.handling.HandlerDecorator", ComponentCapability.HANDLER_DECORATOR),
            entry("io.fluxzero.sdk.tracking.handling.HandlerInterceptor", ComponentCapability.HANDLER_INTERCEPTOR),
            entry("io.fluxzero.sdk.tracking.BatchInterceptor", ComponentCapability.BATCH_INTERCEPTOR),
            entry("io.fluxzero.sdk.tracking.handling.ResponseMapper", ComponentCapability.RESPONSE_MAPPER),
            entry("io.fluxzero.sdk.web.WebResponseMapper", ComponentCapability.WEB_RESPONSE_MAPPER),
            entry("io.fluxzero.sdk.tracking.handling.validation.Validator", ComponentCapability.VALIDATOR),
            entry("io.fluxzero.common.handling.ParameterResolver", ComponentCapability.PARAMETER_RESOLVER),
            entry("io.fluxzero.sdk.common.serialization.Serializer", ComponentCapability.SERIALIZER),
            entry("io.fluxzero.sdk.persisting.search.DocumentSerializer", ComponentCapability.DOCUMENT_SERIALIZER),
            entry("io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider",
                  ComponentCapability.CORRELATION_DATA_PROVIDER),
            entry("io.fluxzero.sdk.common.IdentityProvider", ComponentCapability.IDENTITY_PROVIDER),
            entry("io.fluxzero.sdk.tracking.handling.authentication.UserProvider", ComponentCapability.USER_PROVIDER),
            entry("io.fluxzero.common.caching.Cache", ComponentCapability.CACHE),
            entry("io.fluxzero.common.TaskScheduler", ComponentCapability.TASK_SCHEDULER),
            entry("io.fluxzero.common.application.PropertySource", ComponentCapability.PROPERTY_SOURCE));

    private static final Map<String, HandlerSpec> HANDLERS = Map.ofEntries(
            entry("HandleCommand", new HandlerSpec(MessageType.COMMAND, false, false, false, List.of(), false, false)),
            entry("HandleQuery", new HandlerSpec(MessageType.QUERY, false, true, false, List.of(), false, false)),
            entry("HandleEvent", new HandlerSpec(MessageType.EVENT, false, false, false, List.of(), false, false)),
            entry("HandleNotification", new HandlerSpec(MessageType.NOTIFICATION, false, false, false, List.of(), false, false)),
            entry("HandleError", new HandlerSpec(MessageType.ERROR, false, false, false, List.of(), false, false)),
            entry("HandleMetrics", new HandlerSpec(MessageType.METRICS, false, false, false, List.of(), false, false)),
            entry("HandleSchedule", new HandlerSpec(MessageType.SCHEDULE, false, false, false, List.of(), false, false)),
            entry("HandleWeb", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("ANY"), true, true)),
            entry("HandleGet", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("GET"), true, true)),
            entry("HandlePost", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("POST"), false, true)),
            entry("HandlePut", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PUT"), false, true)),
            entry("HandlePatch", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PATCH"), false, true)),
            entry("HandleDelete", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("DELETE"), false, true)),
            entry("HandleHead", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("HEAD"), false, true)),
            entry("HandleOptions", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("OPTIONS"), false, false)),
            entry("HandleTrace", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("TRACE"), false, true)),
            entry("HandleSocketHandshake", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_HANDSHAKE"), false, false)),
            entry("HandleSocketOpen", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_OPEN"), false, false)),
            entry("HandleSocketMessage", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_MESSAGE"), false, false)),
            entry("HandleSocketPong", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_PONG"), false, false)),
            entry("HandleSocketClose", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_CLOSE"), false, false)));

    /**
     * Scans the source root and returns indexed component metadata.
     */
    public ComponentRegistry scan(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new ComponentRegistryException(
                    "Component source root does not exist or is not a directory: " + sourceRoot);
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<ParsedSource> sources = files.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::parse)
                    .toList();
            List<String> allTypeNames = sources.stream()
                    .flatMap(source -> source.type().stream())
                    .map(TypeInfo::fullClassName)
                    .sorted()
                    .toList();
            Map<String, PackageInfo> packages = packageInfos(sources);
            List<PackageDescriptor> packageDescriptors = packages.values().stream()
                    .map(packageInfo -> packageDescriptor(packageInfo, allTypeNames))
                    .toList();
            List<ComponentDescriptor> components = sources.stream()
                    .flatMap(source -> source.type().stream().map(type -> componentDescriptor(source, type, packages, allTypeNames)))
                    .toList();
            return new ComponentRegistry(sourceRoot, packageDescriptors, components);
        } catch (Exception e) {
            if (e instanceof ComponentRegistryException componentRegistryException) {
                throw componentRegistryException;
            }
            throw new ComponentRegistryException("Failed to index component source root: " + sourceRoot, e);
        }
    }

    /**
     * Scans the source root, writes the registry JSON artifact, and returns the indexed registry.
     */
    public ComponentRegistry writeJson(Path sourceRoot, Path output) {
        ComponentRegistry registry = scan(sourceRoot);
        ComponentRegistryJson.write(registry, output);
        return registry;
    }

    private ParsedSource parse(Path sourceFile) {
        try {
            return new SourceParser(sourceFile, Files.readString(sourceFile)).parse();
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to read component source: " + sourceFile, e);
        }
    }

    private Map<String, PackageInfo> packageInfos(List<ParsedSource> sources) {
        Map<String, PackageInfo> result = new LinkedHashMap<>();
        for (ParsedSource source : sources) {
            if ("package-info.java".equals(source.sourceFile().getFileName().toString())) {
                LocalHandlerConfig localHandler = localHandlerConfig(source.packageAnnotations()).orElse(null);
                ConsumerDescriptor consumer = consumerDescriptor(source.packageAnnotations()).orElse(null);
                String path = pathValue(source.packageAnnotations()).orElse("");
                result.put(source.packageName(), new PackageInfo(
                        source.packageName(), source.sourceFile(), source.packageAnnotations(),
                        localHandler, consumer, path));
            }
        }
        return result;
    }

    private PackageDescriptor packageDescriptor(PackageInfo packageInfo, List<String> allTypeNames) {
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(
                packageInfo.annotations(), packageInfo.packageName(), allTypeNames);
        Set<ComponentCapability> capabilities = EnumSet.noneOf(ComponentCapability.class);
        if (packageInfo.localHandler() != null) {
            capabilities.add(ComponentCapability.PACKAGE_LOCAL_HANDLER);
            if (packageInfo.localHandler().enabled()) {
                capabilities.add(ComponentCapability.LOCAL_HANDLER);
            }
            if (!packageInfo.localHandler().enabled() || packageInfo.localHandler().allowExternalMessages()) {
                capabilities.add(ComponentCapability.TRACKING_HANDLER);
            }
        }
        if (!registeredTypes.isEmpty()) {
            capabilities.add(ComponentCapability.REGISTERED_TYPE);
        }
        if (packageInfo.consumer() != null) {
            capabilities.add(ComponentCapability.CONSUMER);
        }
        return new PackageDescriptor(
                packageInfo.packageName(), packageInfo.sourceFile(), packageInfo.annotations(),
                registeredTypes, packageInfo.consumer(), Set.copyOf(capabilities));
    }

    private ComponentDescriptor componentDescriptor(ParsedSource source, TypeInfo type,
                                                   Map<String, PackageInfo> packageInfos, List<String> allTypeNames) {
        PackageInfo packageInfo = packageInfo(source.packageName(), packageInfos);
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(type.annotations(), type.fullClassName(), allTypeNames);
        ConsumerDescriptor consumer = consumerDescriptor(type.annotations())
                .orElse(packageInfo == null ? null : packageInfo.consumer());
        LocalHandlerConfig typeLocalHandler = localHandlerConfig(type.annotations()).orElse(null);
        Set<HandlerRoute> routes = new LinkedHashSet<>();
        for (ExecutableDescriptor executable : type.executables()) {
            for (AnnotationDescriptor annotation : executable.annotations()) {
                HandlerSpec spec = HANDLERS.get(annotation.name());
                if (spec == null) {
                    continue;
                }
                LocalHandlerConfig localHandler = localHandlerConfig(executable.annotations())
                        .orElse(typeLocalHandler != null ? typeLocalHandler
                                : packageInfo == null ? null : packageInfo.localHandler());
                boolean selfHandler = isSelfHandler(spec, type, executable);
                boolean selfTracking = hasTrackSelf(executable.annotations()) || hasTrackSelf(type.annotations())
                                       || packageInfo != null && hasTrackSelf(packageInfo.annotations());
                boolean local = localHandler != null ? localHandler.enabled() : selfHandler && !selfTracking;
                boolean tracked = localHandler != null ? !local || localHandler.allowExternalMessages() : !local;
                boolean disabled = annotation.booleanValue("disabled", false);
                boolean passive = annotation.booleanValue("passive", spec.defaultPassive());
                boolean skipExpiredRequests =
                        annotation.booleanValue("skipExpiredRequests", spec.defaultSkipExpiredRequests());
                Set<String> allowedClasses = new LinkedHashSet<>(annotation.values("allowedClasses"));
                Set<String> payloadTypes = !allowedClasses.isEmpty() ? allowedClasses
                        : spec.web() ? Set.of()
                        : firstPayloadType(executable).map(Set::of)
                                .orElseGet(() -> selfHandler ? Set.of(type.fullClassName()) : Set.of());
                routes.add(new HandlerRoute(
                        spec.messageType(), annotation, executable, disabled, passive, skipExpiredRequests,
                        local, tracked, payloadTypes, allowedClasses,
                        spec.web() ? webRoutes(annotation, spec, packageInfo, type, executable) : List.of()));
            }
        }
        Set<ComponentCapability> capabilities = componentCapabilities(routes, registeredTypes, consumer, type.superTypeNames());
        return new ComponentDescriptor(
                source.sourceFile(), packageInfo == null ? null : packageInfo.sourceFile(), type.kind(),
                source.packageName(), type.className(), type.superTypeNames(), type.annotations(), type.executables(),
                Set.copyOf(routes), registeredTypes, consumer, capabilities);
    }

    private static Optional<String> firstPayloadType(ExecutableDescriptor executable) {
        return executable.parameters().stream().map(ParameterDescriptor::typeName).findFirst();
    }

    private static boolean isSelfHandler(HandlerSpec spec, TypeInfo type, ExecutableDescriptor executable) {
        return (spec.messageType().isRequest() || spec.messageType() == MessageType.SCHEDULE)
               && !spec.web()
               && executable.kind() == ExecutableKind.METHOD
               && executable.parameters().isEmpty()
               && requiresPayloadInstance(type);
    }

    private static boolean requiresPayloadInstance(TypeInfo type) {
        if (type.kind() == ComponentKind.RECORD) {
            return true;
        }
        if (type.kind() != ComponentKind.CLASS) {
            return false;
        }
        List<ExecutableDescriptor> constructors = type.executables().stream()
                .filter(executable -> executable.kind() == ExecutableKind.CONSTRUCTOR)
                .toList();
        return !constructors.isEmpty()
               && constructors.stream().noneMatch(constructor -> constructor.parameters().isEmpty());
    }

    private static boolean hasTrackSelf(List<AnnotationDescriptor> annotations) {
        return annotations.stream().anyMatch(annotation -> annotation.name().equals("TrackSelf"));
    }

    private List<WebRouteDescriptor> webRoutes(AnnotationDescriptor annotation, HandlerSpec spec,
                                               PackageInfo packageInfo, TypeInfo type,
                                               ExecutableDescriptor executable) {
        String packagePath = packageInfo == null ? "" : packageInfo.path();
        String typePath = pathValue(type.annotations()).orElse("");
        String methodPath = pathValue(executable.annotations()).orElse("");
        List<String> handlerPaths = annotation.values("value");
        if (handlerPaths.isEmpty()) {
            handlerPaths = methodPath.isBlank() ? List.of("") : List.of(methodPath);
        }
        List<String> methods = annotation.name().equals("HandleWeb") && !annotation.values("method").isEmpty()
                ? annotation.values("method") : spec.webMethods();
        boolean autoHead = annotation.booleanValue("autoHead", spec.defaultAutoHead());
        boolean autoOptions = annotation.booleanValue("autoOptions", spec.defaultAutoOptions());
        String basePath = combinePath(packagePath, typePath);
        List<String> paths = handlerPaths.stream().map(path -> combinePath(basePath, path)).distinct().toList();
        return List.of(new WebRouteDescriptor(paths, methods, autoHead, autoOptions));
    }

    private static Set<ComponentCapability> componentCapabilities(Set<HandlerRoute> routes,
                                                                  List<RegisteredTypeDescriptor> registeredTypes,
                                                                  ConsumerDescriptor consumer,
                                                                  List<String> superTypeNames) {
        Set<ComponentCapability> result = EnumSet.noneOf(ComponentCapability.class);
        result.add(ComponentCapability.SOURCE_COMPONENT);
        if (!routes.isEmpty()) {
            result.add(ComponentCapability.HANDLER);
        }
        if (routes.stream().anyMatch(HandlerRoute::local)) {
            result.add(ComponentCapability.LOCAL_HANDLER);
        }
        if (routes.stream().anyMatch(HandlerRoute::tracked)) {
            result.add(ComponentCapability.TRACKING_HANDLER);
        }
        if (routes.stream().anyMatch(route -> route.messageType() == MessageType.WEBREQUEST)) {
            result.add(ComponentCapability.WEB_REQUEST_HANDLER);
        }
        if (!registeredTypes.isEmpty()) {
            result.add(ComponentCapability.REGISTERED_TYPE);
        }
        if (consumer != null) {
            result.add(ComponentCapability.CONSUMER);
        }
        superTypeNames.stream()
                .flatMap(typeName -> infrastructureCapabilities(typeName).stream())
                .forEach(result::add);
        if (result.contains(ComponentCapability.HANDLER_INTERCEPTOR)) {
            result.add(ComponentCapability.HANDLER_DECORATOR);
        }
        if (result.contains(ComponentCapability.WEB_RESPONSE_MAPPER)) {
            result.add(ComponentCapability.RESPONSE_MAPPER);
        }
        return Set.copyOf(result);
    }

    private static Set<ComponentCapability> infrastructureCapabilities(String typeName) {
        if ("io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer".equals(typeName)) {
            return Set.of(ComponentCapability.SERIALIZER, ComponentCapability.DOCUMENT_SERIALIZER);
        }
        if ("io.fluxzero.sdk.tracking.handling.DefaultResponseMapper".equals(typeName)) {
            return Set.of(ComponentCapability.RESPONSE_MAPPER);
        }
        if ("io.fluxzero.sdk.web.DefaultWebResponseMapper".equals(typeName)) {
            return Set.of(ComponentCapability.WEB_RESPONSE_MAPPER, ComponentCapability.RESPONSE_MAPPER);
        }
        if ("io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider".equals(typeName)) {
            return Set.of(ComponentCapability.USER_PROVIDER);
        }
        if ("io.fluxzero.sdk.persisting.caching.SoftReferenceCache".equals(typeName)) {
            return Set.of(ComponentCapability.CACHE);
        }
        if ("io.fluxzero.common.InMemoryTaskScheduler".equals(typeName)) {
            return Set.of(ComponentCapability.TASK_SCHEDULER);
        }
        return Optional.ofNullable(INFRASTRUCTURE_CAPABILITIES.get(typeName)).map(Set::of).orElseGet(Set::of);
    }

    private static List<RegisteredTypeDescriptor> registeredTypes(
            List<AnnotationDescriptor> annotations, String defaultRoot, List<String> allTypeNames) {
        return annotations.stream()
                .filter(annotation -> annotation.name().equals("RegisterType"))
                .map(annotation -> {
                    String root = annotation.firstValue("root").filter(value -> !value.isBlank()).orElse(defaultRoot);
                    List<String> contains = annotation.values("contains");
                    List<String> candidates = allTypeNames.stream()
                            .filter(typeName -> typeName.replace("$", ".").startsWith(root))
                            .filter(typeName -> contains.isEmpty() || contains.stream()
                                    .map(Pattern::compile)
                                    .anyMatch(pattern -> pattern.matcher(typeName).find()))
                            .toList();
                    return new RegisteredTypeDescriptor(root, contains, candidates, annotation);
                })
                .toList();
    }

    private static Optional<ConsumerDescriptor> consumerDescriptor(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.name().equals("Consumer"))
                .findFirst()
                .map(annotation -> new ConsumerDescriptor(
                        annotation.firstValue("name").or(() -> annotation.firstValue("value")).orElse(""),
                        annotation.attributes(), annotation));
    }

    private static Optional<LocalHandlerConfig> localHandlerConfig(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.name().equals("LocalHandler"))
                .reduce((first, second) -> second)
                .map(annotation -> {
                    boolean enabled = annotation.booleanValue("value", true);
                    boolean allowExternalMessages = enabled && annotation.booleanValue("allowExternalMessages", false);
                    return new LocalHandlerConfig(enabled, allowExternalMessages);
                });
    }

    private static Optional<String> pathValue(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.name().equals("Path"))
                .reduce((first, second) -> second)
                .flatMap(annotation -> annotation.firstValue("value"));
    }

    private static PackageInfo packageInfo(String packageName, Map<String, PackageInfo> packageInfos) {
        for (String current = packageName; current != null; current = parentPackage(current)) {
            PackageInfo packageInfo = packageInfos.get(current);
            if (packageInfo != null) {
                return packageInfo;
            }
        }
        return null;
    }

    private static String parentPackage(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? null : packageName.substring(0, lastDot);
    }

    private static String combinePath(String prefix, String path) {
        if (path == null || path.isBlank()) {
            return prefix == null ? "" : prefix;
        }
        if (path.startsWith("/")) {
            return path;
        }
        if (prefix == null || prefix.isBlank()) {
            return path;
        }
        return prefix.endsWith("/") ? prefix + path : prefix + "/" + path;
    }

    private record HandlerSpec(MessageType messageType, boolean defaultPassive, boolean defaultSkipExpiredRequests,
                               boolean web, List<String> webMethods, boolean defaultAutoHead,
                               boolean defaultAutoOptions) {
    }

    private record LocalHandlerConfig(boolean enabled, boolean allowExternalMessages) {
    }

    private record PackageInfo(String packageName, Path sourceFile, List<AnnotationDescriptor> annotations,
                               LocalHandlerConfig localHandler, ConsumerDescriptor consumer, String path) {
    }

    private record ParsedSource(Path sourceFile, String packageName, Map<String, String> imports,
                                List<String> wildcardImports, List<AnnotationDescriptor> packageAnnotations,
                                Optional<TypeInfo> type) {
    }

    private record TypeInfo(ComponentKind kind, String packageName, String className, List<String> superTypeNames,
                            List<AnnotationDescriptor> annotations, List<ExecutableDescriptor> executables) {
        String fullClassName() {
            return packageName.isBlank() ? className : packageName + "." + className;
        }
    }

    private static class SourceParser {
        private final Path sourceFile;
        private final String source;
        private final String packageName;
        private final Map<String, String> imports;
        private final List<String> wildcardImports;

        SourceParser(Path sourceFile, String source) {
            this.sourceFile = sourceFile;
            this.source = stripComments(source);
            this.packageName = first(PACKAGE_PATTERN, this.source).orElse("");
            this.imports = imports(this.source);
            this.wildcardImports = wildcardImports(this.source);
        }

        ParsedSource parse() {
            List<AnnotationDescriptor> packageAnnotations = parsePackageAnnotations();
            return new ParsedSource(sourceFile, packageName, imports, wildcardImports,
                                    packageAnnotations, parseType());
        }

        private List<AnnotationDescriptor> parsePackageAnnotations() {
            Matcher matcher = PACKAGE_PATTERN.matcher(source);
            if (!matcher.find()) {
                return List.of();
            }
            return parseAnnotations(source.substring(0, matcher.start()));
        }

        private Optional<TypeInfo> parseType() {
            Matcher matcher = TYPE_PATTERN.matcher(source);
            while (matcher.find()) {
                if (insideAnnotation(matcher.start())) {
                    continue;
                }
                ComponentKind kind = ComponentKind.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
                String className = matcher.group(2);
                int bodyStart = source.indexOf('{', matcher.end());
                if (bodyStart < 0) {
                    return Optional.empty();
                }
                int bodyEnd = matching(source, bodyStart, '{', '}');
                if (bodyEnd < 0) {
                    bodyEnd = source.length() - 1;
                }
                List<AnnotationDescriptor> annotations = parseAnnotations(annotationsBefore(matcher.start()));
                List<String> superTypeNames = parseSuperTypeNames(source.substring(matcher.end(), bodyStart));
                List<ExecutableDescriptor> executables = parseExecutables(className, bodyStart, bodyEnd);
                return Optional.of(new TypeInfo(kind, packageName, className, superTypeNames, annotations, executables));
            }
            return Optional.empty();
        }

        private List<String> parseSuperTypeNames(String declarationTail) {
            String tail = declarationTail.trim();
            if (tail.startsWith("(")) {
                int end = matching(tail, 0, '(', ')');
                tail = end < 0 || end + 1 >= tail.length() ? "" : tail.substring(end + 1).trim();
            }
            List<String> result = new ArrayList<>();
            collectSuperTypes(tail, "extends", result);
            collectSuperTypes(tail, "implements", result);
            return result.stream().distinct().toList();
        }

        private void collectSuperTypes(String declarationTail, String keyword, List<String> sink) {
            int index = wordIndexOf(declarationTail, keyword);
            if (index < 0) {
                return;
            }
            int start = index + keyword.length();
            int end = nextKeywordIndex(declarationTail, start);
            String segment = declarationTail.substring(start, end < 0 ? declarationTail.length() : end);
            for (String type : splitTopLevel(segment, ',')) {
                String erased = eraseGeneric(type.trim());
                if (!erased.isBlank()) {
                    sink.add(resolveType(erased));
                }
            }
        }

        private boolean insideAnnotation(int offset) {
            int at = source.lastIndexOf('@', offset);
            int semicolon = source.lastIndexOf(';', offset);
            int brace = Math.max(source.lastIndexOf('{', offset), source.lastIndexOf('}', offset));
            return at > semicolon && at > brace && source.substring(at, offset).contains("@interface");
        }

        private String annotationsBefore(int offset) {
            int boundary = Math.max(Math.max(source.lastIndexOf(';', offset), source.lastIndexOf('{', offset)),
                                    source.lastIndexOf('}', offset));
            return source.substring(Math.max(boundary + 1, 0), offset);
        }

        private List<ExecutableDescriptor> parseExecutables(String className, int bodyStart, int bodyEnd) {
            List<ExecutableDescriptor> result = new ArrayList<>();
            int memberStart = bodyStart + 1;
            int i = memberStart;
            int paren = 0;
            while (i < bodyEnd) {
                char c = source.charAt(i);
                if (isStringStart(i)) {
                    i = skipString(i);
                    continue;
                }
                if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (c == '{' && paren == 0) {
                    String header = source.substring(memberStart, i).trim();
                    parseExecutable(header, className).ifPresent(result::add);
                    int blockEnd = matching(source, i, '{', '}');
                    if (blockEnd < 0 || blockEnd >= bodyEnd) {
                        break;
                    }
                    i = blockEnd + 1;
                    memberStart = i;
                    paren = 0;
                    continue;
                }
                if (c == ';' && paren == 0) {
                    parseExecutable(source.substring(memberStart, i).trim(), className).ifPresent(result::add);
                    memberStart = i + 1;
                }
                i++;
            }
            return result;
        }

        private Optional<ExecutableDescriptor> parseExecutable(String header, String className) {
            if (header.isBlank() || !header.contains("(") || topLevelIndexOf(removeAnnotations(header), '=') >= 0) {
                return Optional.empty();
            }
            int paren = lastTopLevel(header, '(');
            if (paren < 0) {
                return Optional.empty();
            }
            int paramEnd = matching(header, paren, '(', ')');
            if (paramEnd < 0) {
                return Optional.empty();
            }
            String name = previousIdentifier(header, paren);
            if (name == null || name.isBlank() || controlKeyword(name)) {
                return Optional.empty();
            }
            int nameStart = previousIdentifierStart(header, paren);
            List<AnnotationDescriptor> annotations = parseAnnotations(header.substring(0, nameStart));
            String signaturePrefix = removeAnnotations(header.substring(0, nameStart)).trim();
            List<String> prefixTokens = words(signaturePrefix);
            prefixTokens.removeIf(MODIFIERS::contains);
            prefixTokens.removeIf(token -> token.startsWith("<") && token.endsWith(">"));
            ExecutableKind kind = name.equals(className) ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
            String returnType = kind == ExecutableKind.CONSTRUCTOR || prefixTokens.isEmpty()
                    ? "void" : resolveType(eraseGeneric(prefixTokens.get(prefixTokens.size() - 1)));
            List<ParameterDescriptor> parameters = parseParameters(header.substring(paren + 1, paramEnd));
            return Optional.of(new ExecutableDescriptor(kind, name, returnType, parameters, annotations));
        }

        private List<ParameterDescriptor> parseParameters(String parameters) {
            List<ParameterDescriptor> result = new ArrayList<>();
            for (String parameter : splitTopLevel(parameters, ',')) {
                String trimmed = parameter.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                List<AnnotationDescriptor> annotations = parseAnnotations(trimmed);
                String cleaned = removeAnnotations(trimmed).replace("final ", "").trim();
                String name = lastIdentifier(cleaned);
                if (name == null) {
                    continue;
                }
                int nameStart = cleaned.lastIndexOf(name);
                String type = cleaned.substring(0, nameStart).trim().replace("...", "[]");
                if (type.isBlank()) {
                    continue;
                }
                result.add(new ParameterDescriptor(name, resolveType(eraseGeneric(type.replace("[]", ""))), annotations));
            }
            return result;
        }

        private List<AnnotationDescriptor> parseAnnotations(String text) {
            List<AnnotationDescriptor> result = new ArrayList<>();
            int i = 0;
            while (i < text.length()) {
                int at = text.indexOf('@', i);
                if (at < 0) {
                    break;
                }
                int nameStart = at + 1;
                int nameEnd = nameStart;
                while (nameEnd < text.length()
                       && (Character.isJavaIdentifierPart(text.charAt(nameEnd)) || text.charAt(nameEnd) == '.')) {
                    nameEnd++;
                }
                if (nameEnd == nameStart) {
                    i = at + 1;
                    continue;
                }
                String rawName = text.substring(nameStart, nameEnd);
                String simpleName = simpleName(rawName);
                String qualifiedName = resolveAnnotationName(rawName);
                Map<String, List<String>> attributes = Map.of();
                int next = skipWhitespace(text, nameEnd);
                if (next < text.length() && text.charAt(next) == '(') {
                    int end = matching(text, next, '(', ')');
                    if (end > next) {
                        attributes = parseAttributes(text.substring(next + 1, end));
                        i = end + 1;
                    } else {
                        i = next + 1;
                    }
                } else {
                    i = nameEnd;
                }
                result.add(new AnnotationDescriptor(simpleName, qualifiedName, attributes));
            }
            return result;
        }

        private Map<String, List<String>> parseAttributes(String attributes) {
            if (attributes == null || attributes.isBlank()) {
                return Map.of();
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            List<String> parts = splitTopLevel(attributes, ',');
            boolean singleValue = parts.size() == 1 && topLevelIndexOf(parts.getFirst(), '=') < 0;
            if (singleValue) {
                result.put("value", parseValues(parts.getFirst()));
                return result;
            }
            for (String part : parts) {
                int equals = topLevelIndexOf(part, '=');
                if (equals < 0) {
                    result.put("value", parseValues(part));
                } else {
                    result.put(part.substring(0, equals).trim(), parseValues(part.substring(equals + 1)));
                }
            }
            return result;
        }

        private List<String> parseValues(String value) {
            value = value.trim();
            if (value.startsWith("{") && value.endsWith("}")) {
                return splitTopLevel(value.substring(1, value.length() - 1), ',').stream()
                        .map(this::normalizeValue)
                        .filter(v -> !v.isBlank())
                        .toList();
            }
            return List.of(normalizeValue(value));
        }

        private String normalizeValue(String value) {
            value = value.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                return value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            if (value.endsWith(".class")) {
                return resolveType(value.substring(0, value.length() - ".class".length()));
            }
            return value;
        }

        private String resolveAnnotationName(String name) {
            if (name.contains(".")) {
                return name;
            }
            return imports.getOrDefault(name, KNOWN_ANNOTATIONS.getOrDefault(name,
                    packageName.isBlank() ? name : packageName + "." + name));
        }

        private String resolveType(String type) {
            type = type.trim();
            if (type.contains(".")) {
                return type;
            }
            if (PRIMITIVE_TYPES.contains(type)) {
                return type;
            }
            if (imports.containsKey(type)) {
                return imports.get(type);
            }
            if (KNOWN_TYPES.containsKey(type)) {
                return KNOWN_TYPES.get(type);
            }
            if (JAVA_LANG_TYPES.contains(type)) {
                return "java.lang." + type;
            }
            if (!wildcardImports.isEmpty()) {
                return type;
            }
            return packageName.isBlank() ? type : packageName + "." + type;
        }

        private static int nextKeywordIndex(String text, int start) {
            int extendsIndex = wordIndexOf(text, "extends", start);
            int implementsIndex = wordIndexOf(text, "implements", start);
            if (extendsIndex < 0) {
                return implementsIndex;
            }
            if (implementsIndex < 0) {
                return extendsIndex;
            }
            return Math.min(extendsIndex, implementsIndex);
        }

        private static int wordIndexOf(String text, String word) {
            return wordIndexOf(text, word, 0);
        }

        private static int wordIndexOf(String text, String word, int start) {
            for (int index = text.indexOf(word, start); index >= 0; index = text.indexOf(word, index + 1)) {
                boolean before = index == 0 || !Character.isJavaIdentifierPart(text.charAt(index - 1));
                int afterIndex = index + word.length();
                boolean after = afterIndex >= text.length()
                                || !Character.isJavaIdentifierPart(text.charAt(afterIndex));
                if (before && after) {
                    return index;
                }
            }
            return -1;
        }

        private boolean isStringStart(int offset) {
            char c = source.charAt(offset);
            return c == '"' || c == '\'';
        }

        private int skipString(int offset) {
            char quote = source.charAt(offset);
            int i = offset + 1;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    return i + 1;
                }
                i++;
            }
            return i;
        }

        private static Optional<String> first(Pattern pattern, String source) {
            Matcher matcher = pattern.matcher(source);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }

        private static Map<String, String> imports(String source) {
            Map<String, String> result = new HashMap<>();
            Matcher matcher = IMPORT_PATTERN.matcher(source);
            while (matcher.find()) {
                if (matcher.group(2) == null) {
                    String name = matcher.group(1);
                    result.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
            }
            return result;
        }

        private static List<String> wildcardImports(String source) {
            List<String> result = new ArrayList<>();
            Matcher matcher = IMPORT_PATTERN.matcher(source);
            while (matcher.find()) {
                if (matcher.group(2) != null) {
                    result.add(matcher.group(1));
                }
            }
            return result;
        }
    }

    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean string = false;
        boolean character = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (!string && !character && c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                if (i < source.length()) {
                    result.append('\n');
                }
                continue;
            }
            if (!string && !character && c == '/' && next == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    result.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                i++;
                continue;
            }
            result.append(c);
            if (c == '\\') {
                if (i + 1 < source.length()) {
                    result.append(source.charAt(++i));
                }
                continue;
            }
            if (!character && c == '"') {
                string = !string;
            } else if (!string && c == '\'') {
                character = !character;
            }
        }
        return result.toString();
    }

    private static int matching(String source, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipQuoted(String source, int start) {
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i;
            }
            i++;
        }
        return source.length() - 1;
    }

    private static int lastTopLevel(String source, char target) {
        int result = -1;
        int generic = 0;
        int paren = 0;
        int brace = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
            } else if (c == '<') {
                generic++;
            } else if (c == '>') {
                generic = Math.max(0, generic - 1);
            } else if (c == '(') {
                if (generic == 0 && paren == 0 && brace == 0 && c == target) {
                    result = i;
                }
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace = Math.max(0, brace - 1);
            }
        }
        return result;
    }

    private static int topLevelIndexOf(String source, char target) {
        int generic = 0;
        int paren = 0;
        int brace = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
            } else if (c == '<') {
                generic++;
            } else if (c == '>') {
                generic = Math.max(0, generic - 1);
            } else if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace = Math.max(0, brace - 1);
            } else if (c == target && generic == 0 && paren == 0 && brace == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String source, char separator) {
        List<String> result = new ArrayList<>();
        int generic = 0;
        int paren = 0;
        int brace = 0;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
            } else if (c == '<') {
                generic++;
            } else if (c == '>') {
                generic = Math.max(0, generic - 1);
            } else if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace = Math.max(0, brace - 1);
            } else if (c == separator && generic == 0 && paren == 0 && brace == 0) {
                result.add(source.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = source.substring(start).trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private static String removeAnnotations(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != '@') {
                result.append(c);
                i++;
                continue;
            }
            i++;
            while (i < source.length() && (Character.isJavaIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) {
                i++;
            }
            i = skipWhitespace(source, i);
            if (i < source.length() && source.charAt(i) == '(') {
                int end = matching(source, i, '(', ')');
                i = end < 0 ? source.length() : end + 1;
            }
            result.append(' ');
        }
        return result.toString();
    }

    private static List<String> words(String source) {
        List<String> result = new ArrayList<>();
        for (String word : source.replace('\n', ' ').replace('\r', ' ').split("\\s+")) {
            if (!word.isBlank()) {
                result.add(word);
            }
        }
        return result;
    }

    private static String eraseGeneric(String type) {
        int genericStart = type.indexOf('<');
        return genericStart < 0 ? type : type.substring(0, genericStart);
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String previousIdentifier(String source, int offset) {
        int start = previousIdentifierStart(source, offset);
        return start < 0 ? null : source.substring(start, previousIdentifierEnd(source, offset));
    }

    private static int previousIdentifierStart(String source, int offset) {
        int end = previousIdentifierEnd(source, offset);
        if (end < 0) {
            return -1;
        }
        int start = end - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(source.charAt(start))) {
            start--;
        }
        return start + 1;
    }

    private static int previousIdentifierEnd(String source, int offset) {
        int end = offset - 1;
        while (end >= 0 && Character.isWhitespace(source.charAt(end))) {
            end--;
        }
        return end < 0 ? -1 : end + 1;
    }

    private static String lastIdentifier(String source) {
        Matcher matcher = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$").matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int skipWhitespace(String source, int offset) {
        while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private static boolean controlKeyword(String name) {
        return Set.of("if", "for", "while", "switch", "catch", "try", "new", "return").contains(name);
    }
}
