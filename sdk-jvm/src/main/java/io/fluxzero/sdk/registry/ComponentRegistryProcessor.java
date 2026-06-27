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

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Build-time producer for the incubating Fluxzero component registry.
 * <p>
 * The processor observes javac's source model and writes {@value ComponentRegistryJson#DEFAULT_RESOURCE}. It does not
 * replace existing annotation processors and it does not alter runtime {@code TypeRegistry} behavior; {@code
 * @RegisterType} is captured as component metadata only.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({
        ComponentRegistryProcessor.ENABLED_OPTION,
        ComponentRegistryProcessor.OUTPUT_OPTION,
        "org.gradle.annotation.processing.aggregating"
})
public class ComponentRegistryProcessor extends AbstractProcessor {
    /**
     * Compiler option used to disable registry generation.
     */
    public static final String ENABLED_OPTION = "fluxzero.registry.enabled";

    /**
     * Compiler option for the class-output resource path.
     */
    public static final String OUTPUT_OPTION = "fluxzero.registry.output";

    private static final String REGISTER_TYPE = "io.fluxzero.common.serialization.RegisterType";
    private static final String CONSUMER = "io.fluxzero.sdk.tracking.Consumer";
    private static final String TRACK_SELF = "io.fluxzero.sdk.tracking.TrackSelf";
    private static final String LOCAL_HANDLER = "io.fluxzero.sdk.tracking.handling.LocalHandler";
    private static final String PATH = "io.fluxzero.sdk.web.Path";
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
            entry("io.fluxzero.sdk.tracking.handling.HandleCommand",
                  new HandlerSpec(MessageType.COMMAND, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleQuery",
                  new HandlerSpec(MessageType.QUERY, false, true, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleEvent",
                  new HandlerSpec(MessageType.EVENT, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleNotification",
                  new HandlerSpec(MessageType.NOTIFICATION, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleError",
                  new HandlerSpec(MessageType.ERROR, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleMetrics",
                  new HandlerSpec(MessageType.METRICS, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleResult",
                  new HandlerSpec(MessageType.RESULT, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleCustom",
                  new HandlerSpec(MessageType.CUSTOM, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleDocument",
                  new HandlerSpec(MessageType.DOCUMENT, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleSchedule",
                  new HandlerSpec(MessageType.SCHEDULE, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.web.HandleWebResponse",
                  new HandlerSpec(MessageType.WEBRESPONSE, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.web.HandleWeb",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("ANY"), true, true)),
            entry("io.fluxzero.sdk.web.HandleGet",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("GET"), true, true)),
            entry("io.fluxzero.sdk.web.HandlePost",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("POST"), false, true)),
            entry("io.fluxzero.sdk.web.HandlePut",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PUT"), false, true)),
            entry("io.fluxzero.sdk.web.HandlePatch",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PATCH"), false, true)),
            entry("io.fluxzero.sdk.web.HandleDelete",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("DELETE"), false, true)),
            entry("io.fluxzero.sdk.web.HandleHead",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("HEAD"), false, true)),
            entry("io.fluxzero.sdk.web.HandleOptions",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("OPTIONS"), false, false)),
            entry("io.fluxzero.sdk.web.HandleTrace",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("TRACE"), false, true)),
            entry("io.fluxzero.sdk.web.HandleSocketHandshake",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_HANDSHAKE"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketOpen",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_OPEN"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketMessage",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_MESSAGE"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketPong",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_PONG"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketClose",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_CLOSE"), false, false)));

    private final Map<String, TypeElement> types = new LinkedHashMap<>();
    private final Map<String, PackageElement> packages = new LinkedHashMap<>();
    private boolean written;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!enabled()) {
            return false;
        }
        roundEnv.getRootElements().forEach(this::collect);
        if (roundEnv.processingOver() && !written) {
            written = true;
            writeRegistry();
        }
        return false;
    }

    private boolean enabled() {
        String configured = processingEnv.getOptions().get(ENABLED_OPTION);
        return configured == null || !"false".equalsIgnoreCase(configured);
    }

    private void collect(Element element) {
        if (element instanceof TypeElement type && componentKind(type).isPresent()) {
            types.putIfAbsent(binaryName(type), type);
            collectPackage(type);
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested && componentKind(nested).isPresent()) {
                types.putIfAbsent(binaryName(nested), nested);
                collectPackage(nested);
            }
        }
    }

    private void collectPackage(TypeElement type) {
        PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(type);
        for (String name = packageElement.getQualifiedName().toString(); name != null; name = parentPackage(name)) {
            PackageElement current = processingEnv.getElementUtils().getPackageElement(name);
            if (current != null) {
                packages.putIfAbsent(name, current);
            }
        }
    }

    private void writeRegistry() {
        ComponentRegistry registry = registry();
        if (registry.packages().isEmpty() && registry.components().isEmpty()) {
            return;
        }
        String output = processingEnv.getOptions().getOrDefault(
                OUTPUT_OPTION, ComponentRegistryJson.DEFAULT_RESOURCE);
        try {
            FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", output);
            try (Writer writer = resource.openWriter()) {
                ComponentRegistryJson.write(registry.normalized(), writer);
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR, "Failed to write Fluxzero component registry: " + e.getMessage());
        }
    }

    private ComponentRegistry registry() {
        List<String> allTypeNames = types.values().stream()
                .map(this::typeName)
                .sorted()
                .toList();
        List<PackageDescriptor> packageDescriptors = packages.values().stream()
                .sorted(Comparator.comparing(p -> p.getQualifiedName().toString()))
                .map(packageElement -> packageDescriptor(packageElement, allTypeNames))
                .filter(descriptor -> !descriptor.annotations().isEmpty()
                                      || !descriptor.registeredTypes().isEmpty()
                                      || descriptor.consumer() != null
                                      || !descriptor.capabilities().isEmpty())
                .toList();
        List<ComponentDescriptor> components = types.values().stream()
                .sorted(Comparator.comparing(this::binaryName))
                .map(type -> componentDescriptor(type, allTypeNames))
                .toList();
        return new ComponentRegistry(null, packageDescriptors, components);
    }

    private PackageDescriptor packageDescriptor(PackageElement packageElement, List<String> allTypeNames) {
        String packageName = packageElement.getQualifiedName().toString();
        List<AnnotationDescriptor> annotations = annotationDescriptors(packageElement.getAnnotationMirrors());
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(annotations, packageName, allTypeNames);
        ConsumerDescriptor consumer = consumerDescriptor(annotations).orElse(null);
        Set<ComponentCapability> capabilities = EnumSet.noneOf(ComponentCapability.class);
        localHandlerConfig(annotations).ifPresent(localHandler -> {
            capabilities.add(ComponentCapability.PACKAGE_LOCAL_HANDLER);
            if (localHandler.enabled()) {
                capabilities.add(ComponentCapability.LOCAL_HANDLER);
            }
            if (!localHandler.enabled() || localHandler.allowExternalMessages()) {
                capabilities.add(ComponentCapability.TRACKING_HANDLER);
            }
        });
        if (!registeredTypes.isEmpty()) {
            capabilities.add(ComponentCapability.REGISTERED_TYPE);
        }
        if (consumer != null) {
            capabilities.add(ComponentCapability.CONSUMER);
        }
        return new PackageDescriptor(packageName, null, annotations, registeredTypes, consumer, Set.copyOf(capabilities));
    }

    private ComponentDescriptor componentDescriptor(TypeElement type, List<String> allTypeNames) {
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        List<AnnotationDescriptor> annotations = annotationDescriptors(type.getAnnotationMirrors());
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(annotations, typeName(type), allTypeNames);
        PackageMetadata packageMetadata = packageMetadata(packageName);
        ConsumerDescriptor consumer = consumerDescriptor(annotations).orElse(packageMetadata.consumer());
        LocalHandlerConfig typeLocalHandler = localHandlerConfig(annotations).orElse(null);
        List<PropertyDescriptor> properties = propertyDescriptors(type);
        List<ExecutableElement> elements = executableElements(type);
        List<ExecutableDescriptor> executables = elements.stream().map(this::executableDescriptor).toList();
        Set<HandlerRoute> routes = new LinkedHashSet<>();
        for (int i = 0; i < elements.size(); i++) {
            ExecutableElement executableElement = elements.get(i);
            ExecutableDescriptor executable = executables.get(i);
            for (AnnotationDescriptor annotation : executable.annotations()) {
                HandlerSpec spec = HANDLERS.get(annotation.qualifiedName());
                if (spec == null) {
                    continue;
                }
                LocalHandlerConfig localHandler = localHandlerConfig(executable.annotations())
                        .orElse(typeLocalHandler != null ? typeLocalHandler : packageMetadata.localHandler());
                boolean selfHandler = isSelfHandler(spec, type, executableElement);
                boolean selfTracking = hasTrackSelf(executable.annotations()) || hasTrackSelf(annotations)
                                       || packageMetadata.trackSelf();
                boolean local = localHandler != null ? localHandler.enabled() : selfHandler && !selfTracking;
                boolean tracked = localHandler != null ? !local || localHandler.allowExternalMessages() : !local;
                boolean disabled = annotation.booleanValue("disabled", false);
                boolean passive = annotation.booleanValue("passive", spec.defaultPassive());
                boolean skipExpiredRequests =
                        annotation.booleanValue("skipExpiredRequests", spec.defaultSkipExpiredRequests());
                Set<String> allowedClasses = new LinkedHashSet<>(annotation.values("allowedClasses"));
                Set<String> payloadTypes = !allowedClasses.isEmpty() ? allowedClasses
                        : spec.web() ? Set.of() : executable.parameters().stream()
                                .map(ParameterDescriptor::typeName).findFirst()
                                .map(Set::of).orElseGet(() -> selfHandler ? Set.of(typeName(type)) : Set.of());
                routes.add(new HandlerRoute(
                        spec.messageType(), annotation, executable, disabled, passive, skipExpiredRequests,
                        local, tracked, payloadTypes, allowedClasses,
                        spec.web() ? webRoutes(annotation, spec, packageMetadata, annotations, executable) : List.of()));
            }
        }
        List<String> superTypeNames = superTypeNames(type);
        return new ComponentDescriptor(
                null, null, componentKind(type).orElse(ComponentKind.CLASS), packageName, className(type),
                superTypeNames, annotations, properties, executables, Set.copyOf(routes), registeredTypes, consumer,
                componentCapabilities(type, routes, registeredTypes, consumer, superTypeNames));
    }

    private List<PropertyDescriptor> propertyDescriptors(TypeElement type) {
        Map<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.FIELD && !element.getSimpleName().toString().startsWith("$"))
                .map(VariableElement.class::cast)
                .sorted(Comparator.comparing(field -> field.getSimpleName().toString()))
                .forEach(field -> properties.putIfAbsent(field.getSimpleName().toString(), new PropertyDescriptor(
                        field.getSimpleName().toString(), typeName(field.asType()), field.asType().toString(),
                        annotationDescriptors(field.getAnnotationMirrors()))));
        type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.RECORD_COMPONENT)
                .map(RecordComponentElement.class::cast)
                .sorted(Comparator.comparing(component -> component.getSimpleName().toString()))
                .forEach(component -> {
                    String name = component.getSimpleName().toString();
                    List<AnnotationDescriptor> annotations = mergeAnnotations(
                            Optional.ofNullable(properties.get(name)).map(PropertyDescriptor::annotations).orElse(List.of()),
                            annotationDescriptors(component.getAnnotationMirrors()));
                    properties.put(name, new PropertyDescriptor(
                            name, typeName(component.asType()), component.asType().toString(), annotations));
                });
        return List.copyOf(properties.values());
    }

    private List<ExecutableElement> executableElements(TypeElement type) {
        return type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD
                                   || element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .sorted(Comparator.comparing((ExecutableElement e) -> e.getSimpleName().toString())
                                .thenComparing(e -> e.asType().toString()))
                .toList();
    }

    private ExecutableDescriptor executableDescriptor(ExecutableElement executable) {
        ExecutableKind kind = executable.getKind() == ElementKind.CONSTRUCTOR
                ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
        String returnType = kind == ExecutableKind.CONSTRUCTOR ? "void" : typeName(executable.getReturnType());
        List<ParameterDescriptor> parameters = executable.getParameters().stream()
                .map(parameter -> new ParameterDescriptor(
                        parameter.getSimpleName().toString(), typeName(parameter.asType()),
                        annotationDescriptors(parameter.getAnnotationMirrors())))
                .toList();
        return new ExecutableDescriptor(
                kind, executable.getSimpleName().toString(), returnType, parameters,
                annotationDescriptors(executable.getAnnotationMirrors()));
    }

    private List<WebRouteDescriptor> webRoutes(AnnotationDescriptor annotation, HandlerSpec spec,
                                               PackageMetadata packageMetadata,
                                               List<AnnotationDescriptor> typeAnnotations,
                                               ExecutableDescriptor executable) {
        String packagePath = packageMetadata.path();
        String typePath = pathValue(typeAnnotations).orElse("");
        String methodPath = pathValue(executable.annotations()).orElse("");
        List<String> handlerPaths = annotation.values("value");
        if (handlerPaths.isEmpty()) {
            handlerPaths = methodPath.isBlank() ? List.of("") : List.of(methodPath);
        }
        List<String> methods = annotation.qualifiedName().equals("io.fluxzero.sdk.web.HandleWeb")
                && !annotation.values("method").isEmpty() ? annotation.values("method") : spec.webMethods();
        boolean autoHead = annotation.booleanValue("autoHead", spec.defaultAutoHead());
        boolean autoOptions = annotation.booleanValue("autoOptions", spec.defaultAutoOptions());
        String basePath = combinePath(packagePath, typePath);
        List<String> paths = handlerPaths.stream().map(path -> combinePath(basePath, path)).distinct().toList();
        return List.of(new WebRouteDescriptor(paths, methods, autoHead, autoOptions));
    }

    private boolean isSelfHandler(HandlerSpec spec, TypeElement type, ExecutableElement executable) {
        return (spec.messageType().isRequest() || spec.messageType() == MessageType.SCHEDULE)
               && !spec.web()
               && executable.getKind() == ElementKind.METHOD
               && executable.getParameters().isEmpty()
               && requiresPayloadInstance(type);
    }

    private boolean requiresPayloadInstance(TypeElement type) {
        if (type.getKind() == ElementKind.RECORD) {
            return true;
        }
        if (type.getKind() != ElementKind.CLASS) {
            return false;
        }
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        return !constructors.isEmpty()
               && constructors.stream().noneMatch(constructor -> constructor.getParameters().isEmpty());
    }

    private PackageMetadata packageMetadata(String packageName) {
        for (String current = packageName; current != null; current = parentPackage(current)) {
            PackageElement packageElement = packages.get(current);
            if (packageElement == null) {
                packageElement = processingEnv.getElementUtils().getPackageElement(current);
            }
            if (packageElement == null) {
                continue;
            }
            List<AnnotationDescriptor> annotations = annotationDescriptors(packageElement.getAnnotationMirrors());
            if (!annotations.isEmpty()) {
                return new PackageMetadata(
                        localHandlerConfig(annotations).orElse(null),
                        consumerDescriptor(annotations).orElse(null),
                        pathValue(annotations).orElse(""),
                        hasTrackSelf(annotations));
            }
        }
        return PackageMetadata.empty();
    }

    private List<AnnotationDescriptor> annotationDescriptors(Collection<? extends AnnotationMirror> annotations) {
        return annotations.stream()
                .map(this::annotationDescriptor)
                .sorted(Comparator.comparing(AnnotationDescriptor::qualifiedName))
                .toList();
    }

    private static List<AnnotationDescriptor> mergeAnnotations(
            List<AnnotationDescriptor> first, List<AnnotationDescriptor> second) {
        Map<String, AnnotationDescriptor> result = new LinkedHashMap<>();
        first.forEach(annotation -> result.putIfAbsent(annotation.qualifiedName(), annotation));
        second.forEach(annotation -> result.putIfAbsent(annotation.qualifiedName(), annotation));
        return List.copyOf(result.values());
    }

    private AnnotationDescriptor annotationDescriptor(AnnotationMirror annotation) {
        Element annotationElement = annotation.getAnnotationType().asElement();
        String qualifiedName = annotationElement instanceof TypeElement type
                ? type.getQualifiedName().toString() : annotation.getAnnotationType().toString();
        String simpleName = annotationElement.getSimpleName().toString();
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().getSimpleName().toString()))
                .forEach(entry -> attributes.put(entry.getKey().getSimpleName().toString(), values(entry.getValue())));
        return new AnnotationDescriptor(simpleName, qualifiedName, attributes);
    }

    private List<String> values(AnnotationValue value) {
        Object raw = value.getValue();
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof AnnotationValue annotationValue) {
                    result.addAll(values(annotationValue));
                } else {
                    result.add(value(item));
                }
            }
            return result;
        }
        return List.of(value(raw));
    }

    private String value(Object value) {
        if (value instanceof TypeMirror type) {
            return typeName(type);
        }
        if (value instanceof VariableElement enumConstant) {
            return enumConstant.getSimpleName().toString();
        }
        if (value instanceof AnnotationMirror annotation) {
            Element annotationElement = annotation.getAnnotationType().asElement();
            String name = annotationElement instanceof TypeElement type
                    ? type.getQualifiedName().toString() : annotation.getAnnotationType().toString();
            return "@" + name;
        }
        return String.valueOf(value);
    }

    private List<RegisteredTypeDescriptor> registeredTypes(
            List<AnnotationDescriptor> annotations, String defaultRoot, List<String> allTypeNames) {
        return annotations.stream()
                .filter(annotation -> annotation.qualifiedName().equals(REGISTER_TYPE))
                .map(annotation -> {
                    String root = annotationRoot(annotation, defaultRoot);
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

    private static String annotationRoot(AnnotationDescriptor annotation, String defaultRoot) {
        return annotation.firstValue("root")
                .filter(value -> !value.isBlank())
                .or(() -> annotation.firstValue("rootClass").filter(value -> !value.isBlank())
                        .filter(value -> !value.equals(Void.class.getName())))
                .orElse(defaultRoot);
    }

    private Optional<ConsumerDescriptor> consumerDescriptor(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.qualifiedName().equals(CONSUMER))
                .findFirst()
                .map(annotation -> new ConsumerDescriptor(
                        annotation.firstValue("name").or(() -> annotation.firstValue("value")).orElse(""),
                        annotation.attributes(), annotation));
    }

    private Optional<LocalHandlerConfig> localHandlerConfig(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.qualifiedName().equals(LOCAL_HANDLER))
                .reduce((first, second) -> second)
                .map(annotation -> {
                    boolean enabled = annotation.booleanValue("value", true);
                    boolean allowExternalMessages = enabled && annotation.booleanValue("allowExternalMessages", false);
                    return new LocalHandlerConfig(enabled, allowExternalMessages);
                });
    }

    private static boolean hasTrackSelf(List<AnnotationDescriptor> annotations) {
        return annotations.stream().anyMatch(annotation -> annotation.qualifiedName().equals(TRACK_SELF));
    }

    private Optional<String> pathValue(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.qualifiedName().equals(PATH))
                .reduce((first, second) -> second)
                .flatMap(annotation -> annotation.firstValue("value"));
    }

    private Set<ComponentCapability> componentCapabilities(TypeElement type, Set<HandlerRoute> routes,
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
                .map(INFRASTRUCTURE_CAPABILITIES::get)
                .filter(Objects::nonNull)
                .forEach(result::add);
        INFRASTRUCTURE_CAPABILITIES.forEach((typeName, capability) -> {
            if (isAssignable(type, typeName)) {
                result.add(capability);
            }
        });
        if (result.contains(ComponentCapability.HANDLER_INTERCEPTOR)) {
            result.add(ComponentCapability.HANDLER_DECORATOR);
        }
        if (result.contains(ComponentCapability.WEB_RESPONSE_MAPPER)) {
            result.add(ComponentCapability.RESPONSE_MAPPER);
        }
        return Set.copyOf(result);
    }

    private boolean isAssignable(TypeElement type, String targetTypeName) {
        TypeElement targetType = processingEnv.getElementUtils().getTypeElement(targetTypeName);
        if (targetType == null) {
            return false;
        }
        return processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(type.asType()),
                processingEnv.getTypeUtils().erasure(targetType.asType()));
    }

    private List<String> superTypeNames(TypeElement type) {
        List<String> result = new ArrayList<>();
        TypeMirror superclass = type.getSuperclass();
        if (superclass != null && superclass.getKind() != TypeKind.NONE
            && !"java.lang.Object".equals(superclass.toString())) {
            result.add(superclass.toString());
        }
        type.getInterfaces().stream().map(TypeMirror::toString).forEach(result::add);
        return List.copyOf(result);
    }

    private Optional<ComponentKind> componentKind(TypeElement type) {
        return switch (type.getKind()) {
            case CLASS -> Optional.of(ComponentKind.CLASS);
            case RECORD -> Optional.of(ComponentKind.RECORD);
            case INTERFACE, ANNOTATION_TYPE -> Optional.of(ComponentKind.INTERFACE);
            case ENUM -> Optional.of(ComponentKind.ENUM);
            default -> Optional.empty();
        };
    }

    private String binaryName(TypeElement type) {
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    private String className(TypeElement type) {
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String binaryName = binaryName(type);
        return packageName.isBlank() ? binaryName : binaryName.substring(packageName.length() + 1);
    }

    private String typeName(TypeElement type) {
        return type.getQualifiedName().toString();
    }

    private String typeName(TypeMirror type) {
        if (type == null) {
            return "";
        }
        if (type.getKind().name().equals("VOID")) {
            return "void";
        }
        return processingEnv.getTypeUtils().erasure(type).toString();
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

    private record PackageMetadata(LocalHandlerConfig localHandler, ConsumerDescriptor consumer, String path,
                                   boolean trackSelf) {
        private static PackageMetadata empty() {
            return new PackageMetadata(null, null, "", false);
        }
    }
}
