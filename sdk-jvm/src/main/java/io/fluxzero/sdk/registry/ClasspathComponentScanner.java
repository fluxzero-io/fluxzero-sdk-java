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
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.tracking.BatchInterceptor;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.HandleError;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.HandleResult;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
import io.fluxzero.sdk.tracking.handling.validation.Validator;
import io.fluxzero.sdk.web.HandleDelete;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleHead;
import io.fluxzero.sdk.web.HandleOptions;
import io.fluxzero.sdk.web.HandlePatch;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.HandlePut;
import io.fluxzero.sdk.web.HandleSocketClose;
import io.fluxzero.sdk.web.HandleSocketHandshake;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.HandleSocketPong;
import io.fluxzero.sdk.web.HandleTrace;
import io.fluxzero.sdk.web.HandleWeb;
import io.fluxzero.sdk.web.HandleWebResponse;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.WebResponseMapper;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;

/**
 * Incubating scanner for already compiled Fluxzero component classes.
 * <p>
 * This scanner does not crawl the whole classpath. It indexes the classes supplied by the caller, which is the same
 * shape normal Fluxzero applications already use when registering handler instances or handler classes.
 */
public class ClasspathComponentScanner {
    private static final Map<Class<? extends Annotation>, HandlerSpec> HANDLERS = Map.ofEntries(
            entry(HandleCommand.class, new HandlerSpec(MessageType.COMMAND, false, false, false, List.of(), false, false)),
            entry(HandleQuery.class, new HandlerSpec(MessageType.QUERY, false, true, false, List.of(), false, false)),
            entry(HandleEvent.class, new HandlerSpec(MessageType.EVENT, false, false, false, List.of(), false, false)),
            entry(HandleNotification.class, new HandlerSpec(MessageType.NOTIFICATION, false, false, false, List.of(), false, false)),
            entry(HandleError.class, new HandlerSpec(MessageType.ERROR, false, false, false, List.of(), false, false)),
            entry(HandleMetrics.class, new HandlerSpec(MessageType.METRICS, false, false, false, List.of(), false, false)),
            entry(HandleResult.class, new HandlerSpec(MessageType.RESULT, false, false, false, List.of(), false, false)),
            entry(HandleCustom.class, new HandlerSpec(MessageType.CUSTOM, false, false, false, List.of(), false, false)),
            entry(HandleDocument.class, new HandlerSpec(MessageType.DOCUMENT, false, false, false, List.of(), false, false)),
            entry(HandleSchedule.class, new HandlerSpec(MessageType.SCHEDULE, false, false, false, List.of(), false, false)),
            entry(HandleWebResponse.class, new HandlerSpec(MessageType.WEBRESPONSE, false, false, false, List.of(), false, false)),
            entry(HandleWeb.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.ANY), true, true)),
            entry(HandleGet.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.GET), true, true)),
            entry(HandlePost.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.POST), false, true)),
            entry(HandlePut.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.PUT), false, true)),
            entry(HandlePatch.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.PATCH), false, true)),
            entry(HandleDelete.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.DELETE), false, true)),
            entry(HandleHead.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.HEAD), false, true)),
            entry(HandleOptions.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.OPTIONS), false, false)),
            entry(HandleTrace.class, new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of(HttpRequestMethod.TRACE), false, true)),
            entry(HandleSocketHandshake.class, new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of(HttpRequestMethod.WS_HANDSHAKE), false, false)),
            entry(HandleSocketOpen.class, new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of(HttpRequestMethod.WS_OPEN), false, false)),
            entry(HandleSocketMessage.class, new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of(HttpRequestMethod.WS_MESSAGE), false, false)),
            entry(HandleSocketPong.class, new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of(HttpRequestMethod.WS_PONG), false, false)),
            entry(HandleSocketClose.class, new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of(HttpRequestMethod.WS_CLOSE), false, false)));
    private static final List<Class<? extends Annotation>> HANDLER_TYPES = List.of(
            HandleCommand.class, HandleQuery.class, HandleEvent.class, HandleNotification.class, HandleError.class,
            HandleMetrics.class, HandleResult.class, HandleCustom.class, HandleDocument.class, HandleSchedule.class,
            HandleWebResponse.class, HandleGet.class, HandlePost.class, HandlePut.class, HandlePatch.class,
            HandleDelete.class, HandleHead.class, HandleOptions.class, HandleTrace.class, HandleSocketHandshake.class,
            HandleSocketOpen.class, HandleSocketMessage.class, HandleSocketPong.class, HandleSocketClose.class,
            HandleWeb.class);
    private static final ClassValue<ConcurrentMap<List<String>, ComponentRegistry>> SCAN_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentMap<List<String>, ComponentRegistry> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /**
     * Scans compiled component classes and returns an indexed component registry.
     */
    public ComponentRegistry scan(Class<?>... componentTypes) {
        return scan(Arrays.asList(componentTypes));
    }

    /**
     * Scans compiled component classes and returns an indexed component registry.
     */
    public ComponentRegistry scan(Collection<Class<?>> componentTypes) {
        List<Class<?>> types = componentTypes.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(Class::getName))
                .toList();
        if (types.isEmpty()) {
            return ComponentRegistry.empty();
        }
        List<String> cacheKey = types.stream().map(ClasspathComponentScanner::typeName).toList();
        return SCAN_CACHE.get(types.getFirst()).computeIfAbsent(cacheKey, ignored -> scanUncached(types));
    }

    private ComponentRegistry scanUncached(List<Class<?>> types) {
        types.forEach(this::loadPackageInfoChain);
        List<String> allTypeNames = types.stream().map(ClasspathComponentScanner::typeName).sorted().toList();
        List<PackageDescriptor> packageDescriptors = packages(types)
                .map(p -> packageDescriptor(p, allTypeNames))
                .filter(descriptor -> !descriptor.annotations().isEmpty())
                .toList();
        List<ComponentDescriptor> components = types.stream()
                .map(type -> componentDescriptor(type, allTypeNames))
                .toList();
        return new ComponentRegistry(null, packageDescriptors, components);
    }

    private ComponentDescriptor componentDescriptor(Class<?> type, List<String> allTypeNames) {
        List<AnnotationDescriptor> annotations = annotationDescriptors(JvmComponentIntrospector.getInstance().getTypeAnnotations(type));
        List<RegisteredTypeDescriptor> registeredTypes =
                registeredTypes(annotations, typeName(type), allTypeNames);
        ConsumerDescriptor consumer = consumerDescriptor(annotations)
                .orElseGet(() -> consumerDescriptor(packageAnnotations(type.getPackage())).orElse(null));
        LocalHandlerConfig typeLocalHandler = localHandlerConfig(annotations).orElse(null);
        List<AnnotationDescriptor> packageAnnotations = packageAnnotations(type.getPackage());
        List<PropertyDescriptor> properties = propertyDescriptors(type);
        List<Executable> reflectionExecutables = reflectionExecutables(type);
        List<ExecutableDescriptor> executables = reflectionExecutables.stream().map(this::executableDescriptor).toList();
        Set<HandlerRoute> routes = new LinkedHashSet<>();
        for (int i = 0; i < reflectionExecutables.size(); i++) {
            Executable reflectionExecutable = reflectionExecutables.get(i);
            ExecutableDescriptor executable = executables.get(i);
            for (Annotation annotation : handlerAnnotations(reflectionExecutable)) {
                HandlerSpec spec = HANDLERS.get(annotation.annotationType());
                AnnotationDescriptor annotationDescriptor = annotationDescriptor(annotation);
                LocalHandlerConfig localHandler = localHandlerConfig(executable.annotations())
                        .orElse(typeLocalHandler != null ? typeLocalHandler
                                : localHandlerConfig(packageAnnotations).orElse(null));
                boolean selfHandler = isSelfHandler(spec, type, reflectionExecutable);
                boolean selfTracking = hasTrackSelf(executable.annotations()) || hasTrackSelf(annotations)
                                       || hasTrackSelf(packageAnnotations);
                boolean local = localHandler != null ? localHandler.enabled() : selfHandler && !selfTracking;
                boolean tracked = localHandler != null ? !local || localHandler.allowExternalMessages() : !local;
                boolean disabled = booleanAttribute(annotation, "disabled", false);
                boolean passive = booleanAttribute(annotation, "passive", spec.defaultPassive());
                boolean skipExpiredRequests =
                        booleanAttribute(annotation, "skipExpiredRequests", spec.defaultSkipExpiredRequests());
                Set<String> allowedClasses = new LinkedHashSet<>(classArrayAttribute(annotation, "allowedClasses"));
                Set<String> payloadTypes = !allowedClasses.isEmpty() ? allowedClasses
                        : spec.web() ? Set.of() : executable.parameters().stream()
                                .map(ParameterDescriptor::typeName).findFirst()
                                .map(Set::of).orElseGet(() -> selfHandler ? Set.of(typeName(type)) : Set.of());
                routes.add(new HandlerRoute(
                        spec.messageType(), annotationDescriptor, executable, disabled, passive, skipExpiredRequests,
                        local, tracked, payloadTypes, allowedClasses,
                        spec.web() ? webRoutes(annotation, spec, type, reflectionExecutable) : List.of()));
            }
        }
        return new ComponentDescriptor(
                null, null, componentKind(type), type.getPackageName(), className(type), superTypeNames(type),
                annotations, properties, executables, Set.copyOf(routes), registeredTypes, consumer,
                componentCapabilities(type, routes, registeredTypes, consumer));
    }

    private List<PropertyDescriptor> propertyDescriptors(Class<?> type) {
        Map<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .sorted(Comparator.comparing(Field::getName))
                .forEach(field -> properties.putIfAbsent(field.getName(), new PropertyDescriptor(
                        field.getName(), typeName(field.getType()), field.getGenericType().getTypeName(),
                        annotationDescriptors(JvmComponentIntrospector.getInstance().getAnnotations(field)),
                        typeUseDescriptor(field.getAnnotatedType()))));
        if (type.isRecord()) {
            Arrays.stream(type.getRecordComponents())
                    .sorted(Comparator.comparing(RecordComponent::getName))
                    .forEach(component -> {
                        List<AnnotationDescriptor> annotations = mergeAnnotations(
                                Optional.ofNullable(properties.get(component.getName()))
                                        .map(PropertyDescriptor::annotations).orElse(List.of()),
                                annotationDescriptors(component.getAnnotations()));
                        TypeUseDescriptor typeUse = typeUseDescriptor(component.getAnnotatedType());
                        properties.put(component.getName(), new PropertyDescriptor(
                                component.getName(), typeName(component.getType()),
                                component.getGenericType().getTypeName(), annotations, typeUse));
                    });
        }
        Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> method.getReturnType() != void.class)
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> {
                    Optional<String> propertyName = methodPropertyName(method);
                    List<AnnotationDescriptor> annotations =
                            annotationDescriptors(JvmComponentIntrospector.getInstance().getAnnotations(method));
                    propertyName.filter(name -> properties.containsKey(name) || !annotations.isEmpty()).ifPresent(name -> {
                        PropertyDescriptor existing = properties.get(name);
                        List<AnnotationDescriptor> mergedAnnotations = mergeAnnotations(
                                Optional.ofNullable(existing).map(PropertyDescriptor::annotations).orElse(List.of()),
                                annotations);
                        properties.put(name, existing == null
                                ? new PropertyDescriptor(
                                        name, typeName(method.getReturnType()),
                                        method.getGenericReturnType().getTypeName(), mergedAnnotations,
                                        typeUseDescriptor(method.getAnnotatedReturnType()))
                                : new PropertyDescriptor(
                                        existing.name(), existing.typeName(), existing.genericTypeName(),
                                        mergedAnnotations, existing.typeUse()));
                    });
                });
        return List.copyOf(properties.values());
    }

    private Optional<String> methodPropertyName(Method method) {
        String methodName = method.getName();
        if (methodName.equals("getClass") || methodName.equals("hashCode") || methodName.equals("toString")) {
            return Optional.empty();
        }
        if (methodName.startsWith("get") && methodName.length() > 3
            && Character.isUpperCase(methodName.charAt(3))) {
            return Optional.of(decapitalize(methodName.substring(3)));
        }
        if (methodName.startsWith("is") && methodName.length() > 2
            && Character.isUpperCase(methodName.charAt(2))
            && (method.getReturnType().equals(boolean.class) || method.getReturnType().equals(Boolean.class))) {
            return Optional.of(decapitalize(methodName.substring(2)));
        }
        return Optional.of(methodName);
    }

    private static String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.length() > 1 && Character.isUpperCase(value.charAt(0))
            && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private PackageDescriptor packageDescriptor(Package p, List<String> allTypeNames) {
        List<AnnotationDescriptor> annotations = directPackageAnnotations(p);
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(annotations, p.getName(), allTypeNames);
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
        return new PackageDescriptor(p.getName(), null, annotations, registeredTypes, consumer, Set.copyOf(capabilities));
    }

    private List<Executable> reflectionExecutables(Class<?> type) {
        return Stream.concat(Arrays.stream(type.getDeclaredConstructors()), Arrays.stream(type.getDeclaredMethods()))
                .filter(executable -> !executable.isSynthetic())
                .sorted(Comparator.comparing(Executable::getName).thenComparing(Executable::toGenericString))
                .toList();
    }

    private ExecutableDescriptor executableDescriptor(Executable executable) {
        ExecutableKind kind = executable instanceof Constructor<?> ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
        String returnType = executable instanceof Method method ? typeName(method.getReturnType()) : "void";
        TypeUseDescriptor returnTypeUse = executable instanceof Method method
                ? typeUseDescriptor(method.getAnnotatedReturnType()) : TypeUseDescriptor.EMPTY;
        List<ParameterDescriptor> parameters = Arrays.stream(executable.getParameters())
                .map(parameter -> new ParameterDescriptor(
                        parameter.getName(), typeName(parameter.getType()),
                        annotationDescriptors(parameter.getAnnotations()),
                        typeUseDescriptor(parameter.getAnnotatedType())))
                .toList();
        return new ExecutableDescriptor(kind, executable.getName(), returnType, returnTypeUse, parameters,
                                        annotationDescriptors(JvmComponentIntrospector.getInstance().getAnnotations(executable)),
                                        java.lang.reflect.Modifier.isStatic(executable.getModifiers()));
    }

    private static TypeUseDescriptor typeUseDescriptor(AnnotatedType type) {
        if (type == null) {
            return TypeUseDescriptor.EMPTY;
        }
        TypeUseDescriptor componentType = type instanceof AnnotatedArrayType arrayType
                ? typeUseDescriptor(arrayType.getAnnotatedGenericComponentType()) : null;
        List<TypeUseDescriptor> typeArguments = type instanceof AnnotatedParameterizedType parameterizedType
                ? Arrays.stream(parameterizedType.getAnnotatedActualTypeArguments())
                        .map(ClasspathComponentScanner::typeUseDescriptor)
                        .toList()
                : List.of();
        return new TypeUseDescriptor(
                typeName(JvmComponentIntrospector.getInstance().rawClass(type.getType())),
                annotationDescriptors(type.getAnnotations()), typeArguments, componentType);
    }

    private List<Annotation> handlerAnnotations(Executable executable) {
        List<Annotation> result = new ArrayList<>();
        for (Annotation annotation : JvmComponentIntrospector.getInstance().getAnnotations(executable)) {
            if (HANDLERS.containsKey(annotation.annotationType())) {
                result.add(annotation);
            } else {
                handlerAnnotation(annotation).ifPresent(result::add);
            }
        }
        return result;
    }

    private Optional<Annotation> handlerAnnotation(Annotation annotation) {
        return HANDLER_TYPES.stream()
                .filter(type -> annotation.annotationType().isAnnotationPresent(type))
                .findFirst()
                .map(type -> annotation.annotationType().getAnnotation(type));
    }

    private List<WebRouteDescriptor> webRoutes(Annotation annotation, HandlerSpec spec, Class<?> type,
                                               Executable executable) {
        List<String> packagePaths = packagePaths(type);
        Optional<String> typePath = typePath(type);
        Optional<String> methodPath = WebRoutePaths.pathValue(
                annotationDescriptors(JvmComponentIntrospector.getInstance().getAnnotations(executable)),
                JvmComponentIntrospector.getInstance().getSimpleName(executable.getDeclaringClass()));
        List<String> handlerPaths = stringArrayAttribute(annotation, "value");
        List<String> methods = annotation.annotationType().equals(HandleWeb.class)
                ? stringArrayAttribute(annotation, "method") : spec.webMethods();
        boolean autoHead = booleanAttribute(annotation, "autoHead", spec.defaultAutoHead());
        boolean autoOptions = booleanAttribute(annotation, "autoOptions", spec.defaultAutoOptions());
        List<String> paths = WebRoutePaths.paths(packagePaths, typePath, methodPath, handlerPaths);
        return List.of(new WebRouteDescriptor(paths, methods, autoHead, autoOptions));
    }

    private Optional<String> typePath(Class<?> type) {
        return JvmComponentIntrospector.getInstance().getAnnotation(type, Path.class)
                .map(Path::value)
                .map(path -> path.isBlank() ? simplePackageName(type.getPackage()) : path);
    }

    private List<String> packagePaths(Class<?> type) {
        if (type == null || type.getPackageName().isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String current = type.getPackageName(); current != null; current = parentPackage(current)) {
            Package p = packageMetadata(current, type);
            if (p == null) {
                continue;
            }
            WebRoutePaths.pathValue(directPackageAnnotations(p), simplePackageName(p))
                    .ifPresent(path -> result.add(0, path));
        }
        return List.copyOf(result);
    }

    private Package packageMetadata(String packageName, Class<?> type) {
        ClassLoader classLoader = classLoader(type);
        try {
            return Class.forName(packageName + ".package-info", false, classLoader).getPackage();
        } catch (ClassNotFoundException ignored) {
            return classLoader.getDefinedPackage(packageName);
        }
    }

    private String simplePackageName(Package p) {
        return p == null ? "" : JvmComponentIntrospector.getInstance().getSimpleName(p);
    }

    private static boolean isSelfHandler(HandlerSpec spec, Class<?> type, Executable executable) {
        return (spec.messageType().isRequest() || spec.messageType() == MessageType.SCHEDULE)
               && !spec.web()
               && executable instanceof Method
               && executable.getParameterCount() == 0
               && requiresPayloadInstance(type);
    }

    private static boolean requiresPayloadInstance(Class<?> type) {
        return type.isRecord() || JvmComponentIntrospector.getInstance().getDefaultConstructor(type).isEmpty();
    }

    private static boolean hasTrackSelf(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .anyMatch(annotation -> annotation.isOrHas("TrackSelf", TrackSelf.class.getName()));
    }

    private static Set<ComponentCapability> componentCapabilities(Set<HandlerRoute> routes,
                                                                  List<RegisteredTypeDescriptor> registeredTypes,
                                                                  ConsumerDescriptor consumer) {
        return componentCapabilities(null, routes, registeredTypes, consumer);
    }

    private static Set<ComponentCapability> componentCapabilities(Class<?> type, Set<HandlerRoute> routes,
                                                                  List<RegisteredTypeDescriptor> registeredTypes,
                                                                  ConsumerDescriptor consumer) {
        Set<ComponentCapability> result = EnumSet.noneOf(ComponentCapability.class);
        result.add(ComponentCapability.CLASSPATH_COMPONENT);
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
        if (type != null) {
            if (DispatchInterceptor.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.DISPATCH_INTERCEPTOR);
            }
            if (HandlerDecorator.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.HANDLER_DECORATOR);
            }
            if (HandlerInterceptor.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.HANDLER_INTERCEPTOR);
            }
            if (BatchInterceptor.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.BATCH_INTERCEPTOR);
            }
            if (ResponseMapper.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.RESPONSE_MAPPER);
            }
            if (WebResponseMapper.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.WEB_RESPONSE_MAPPER);
            }
            if (Validator.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.VALIDATOR);
            }
            if (ParameterResolver.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.PARAMETER_RESOLVER);
            }
            if (Serializer.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.SERIALIZER);
            }
            if (DocumentSerializer.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.DOCUMENT_SERIALIZER);
            }
            if (CorrelationDataProvider.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.CORRELATION_DATA_PROVIDER);
            }
            if (IdentityProvider.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.IDENTITY_PROVIDER);
            }
            if (UserProvider.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.USER_PROVIDER);
            }
            if (Cache.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.CACHE);
            }
            if (TaskScheduler.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.TASK_SCHEDULER);
            }
            if (PropertySource.class.isAssignableFrom(type)) {
                result.add(ComponentCapability.PROPERTY_SOURCE);
            }
        }
        return Set.copyOf(result);
    }

    private static List<String> superTypeNames(Class<?> type) {
        List<String> result = new ArrayList<>();
        Class<?> superClass = type.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            result.add(typeName(superClass));
        }
        Arrays.stream(type.getInterfaces()).map(ClasspathComponentScanner::typeName).forEach(result::add);
        return List.copyOf(result);
    }

    private static List<RegisteredTypeDescriptor> registeredTypes(
            List<AnnotationDescriptor> annotations, String defaultRoot, List<String> allTypeNames) {
        return annotations.stream()
                .map(annotation -> annotation.find("RegisterType", RegisterType.class.getName()))
                .flatMap(Optional::stream)
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

    private static Optional<ConsumerDescriptor> consumerDescriptor(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(annotation -> annotation.find("Consumer", Consumer.class.getName()))
                .flatMap(Optional::stream)
                .findFirst()
                .map(annotation -> new ConsumerDescriptor(
                        annotation.firstValue("name").or(() -> annotation.firstValue("value")).orElse(""),
                        annotation.attributes(), annotation));
    }

    private static Optional<LocalHandlerConfig> localHandlerConfig(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(annotation -> annotation.find("LocalHandler", LocalHandler.class.getName()))
                .flatMap(Optional::stream)
                .findFirst()
                .map(annotation -> {
                    boolean enabled = annotation.booleanValue("value", true);
                    boolean allowExternalMessages = enabled && annotation.booleanValue("allowExternalMessages", false);
                    return new LocalHandlerConfig(enabled, allowExternalMessages);
                });
    }

    private static List<AnnotationDescriptor> directPackageAnnotations(Package p) {
        return annotationDescriptors(JvmComponentIntrospector.getInstance().getPackageAnnotations(p, false));
    }

    private static List<AnnotationDescriptor> packageAnnotations(Package p) {
        return annotationDescriptors(JvmComponentIntrospector.getInstance().getPackageAnnotations(p));
    }

    private static List<AnnotationDescriptor> annotationDescriptors(Collection<? extends Annotation> annotations) {
        return annotations.stream().map(ClasspathComponentScanner::annotationDescriptor).toList();
    }

    private static List<AnnotationDescriptor> annotationDescriptors(Annotation[] annotations) {
        return Arrays.stream(annotations).map(ClasspathComponentScanner::annotationDescriptor).toList();
    }

    private static List<AnnotationDescriptor> mergeAnnotations(
            List<AnnotationDescriptor> first, List<AnnotationDescriptor> second) {
        Map<String, AnnotationDescriptor> result = new LinkedHashMap<>();
        first.forEach(annotation -> result.putIfAbsent(annotation.qualifiedName(), annotation));
        second.forEach(annotation -> result.putIfAbsent(annotation.qualifiedName(), annotation));
        return List.copyOf(result.values());
    }

    private static AnnotationDescriptor annotationDescriptor(Annotation annotation) {
        return annotationDescriptor(annotation, new LinkedHashSet<>());
    }

    private static AnnotationDescriptor annotationDescriptor(
            Annotation annotation, Set<Class<? extends Annotation>> visited) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        Map<String, List<AnnotationDescriptor>> nestedAnnotations = new LinkedHashMap<>();
        Arrays.stream(annotation.annotationType().getDeclaredMethods())
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> {
                    invoke(method, annotation).ifPresent(value -> {
                        attributes.put(method.getName(), values(value));
                        List<AnnotationDescriptor> nested = nestedAnnotations(value);
                        if (!nested.isEmpty()) {
                            nestedAnnotations.put(method.getName(), nested);
                        }
                    });
                });
        visited.add(annotation.annotationType());
        List<AnnotationDescriptor> metaAnnotations = Arrays.stream(annotation.annotationType().getAnnotations())
                .filter(metaAnnotation -> includeMetaAnnotation(metaAnnotation, visited))
                .map(metaAnnotation -> annotationDescriptor(metaAnnotation, new LinkedHashSet<>(visited)))
                .toList();
        return new AnnotationDescriptor(
                annotation.annotationType().getSimpleName(), annotation.annotationType().getName(), attributes,
                nestedAnnotations,
                metaAnnotations);
    }

    private static boolean includeMetaAnnotation(
            Annotation annotation, Set<Class<? extends Annotation>> visited) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        return !annotationType.getName().startsWith("java.lang.annotation.")
               && !visited.contains(annotationType);
    }

    private static Optional<Object> invoke(Method method, Annotation annotation) {
        try {
            return Optional.ofNullable(method.invoke(annotation));
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<String> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<String> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                result.add(value(Array.get(value, i)));
            }
            return result;
        }
        return List.of(value(value));
    }

    private static String value(Object value) {
        if (value instanceof Class<?> type) {
            return typeName(type);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Annotation annotation) {
            return "@" + annotation.annotationType().getName();
        }
        return String.valueOf(value);
    }

    private static List<AnnotationDescriptor> nestedAnnotations(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<AnnotationDescriptor> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                result.addAll(nestedAnnotations(Array.get(value, i)));
            }
            return result;
        }
        if (value instanceof Annotation annotation) {
            return List.of(annotationDescriptor(annotation));
        }
        return List.of();
    }

    private static boolean booleanAttribute(Annotation annotation, String name, boolean defaultValue) {
        return attribute(annotation, name).map(Boolean.class::cast).orElse(defaultValue);
    }

    private static List<String> stringArrayAttribute(Annotation annotation, String name) {
        return attribute(annotation, name)
                .map(value -> value instanceof String[] array ? List.of(array) : List.of(String.valueOf(value)))
                .orElseGet(List::of);
    }

    private static List<String> classArrayAttribute(Annotation annotation, String name) {
        return attribute(annotation, name)
                .map(value -> value instanceof Class<?>[] array
                        ? Arrays.stream(array).map(ClasspathComponentScanner::typeName).toList() : List.<String>of())
                .orElseGet(List::of);
    }

    private static Optional<Object> attribute(Annotation annotation, String name) {
        try {
            return Optional.of(annotation.annotationType().getDeclaredMethod(name).invoke(annotation));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            throw new ComponentRegistryException("Failed to read annotation attribute `%s` from %s".formatted(
                    name, annotation), e);
        }
    }

    private Stream<Package> packages(List<Class<?>> types) {
        Map<String, Package> result = new LinkedHashMap<>();
        for (Class<?> type : types) {
            ClassLoader classLoader = classLoader(type);
            for (String name = type.getPackageName(); name != null; name = parentPackage(name)) {
                loadPackageInfo(classLoader, name);
                Package p = classLoader.getDefinedPackage(name);
                if (p != null) {
                    result.putIfAbsent(p.getName(), p);
                }
            }
        }
        return result.values().stream().sorted(Comparator.comparing(Package::getName));
    }

    private void loadPackageInfoChain(Class<?> type) {
        ClassLoader classLoader = classLoader(type);
        for (String name = type.getPackageName(); name != null; name = parentPackage(name)) {
            loadPackageInfo(classLoader, name);
        }
    }

    private static void loadPackageInfo(ClassLoader classLoader, String packageName) {
        try {
            Class.forName(packageName + ".package-info", false, classLoader);
        } catch (ClassNotFoundException ignored) {
        }
    }

    private static ClassLoader classLoader(Class<?> type) {
        return type.getClassLoader() == null ? ClassLoader.getSystemClassLoader() : type.getClassLoader();
    }

    private static String parentPackage(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? null : packageName.substring(0, lastDot);
    }

    private static ComponentKind componentKind(Class<?> type) {
        if (type.isRecord()) {
            return ComponentKind.RECORD;
        }
        if (type.isInterface()) {
            return ComponentKind.INTERFACE;
        }
        if (type.isEnum()) {
            return ComponentKind.ENUM;
        }
        return ComponentKind.CLASS;
    }

    private static String className(Class<?> type) {
        String packageName = type.getPackageName();
        return packageName.isBlank() ? type.getName() : type.getName().substring(packageName.length() + 1);
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
    }

    private record HandlerSpec(MessageType messageType, boolean defaultPassive, boolean defaultSkipExpiredRequests,
                               boolean web, List<String> webMethods, boolean defaultAutoHead,
                               boolean defaultAutoOptions) {
    }

    private record LocalHandlerConfig(boolean enabled, boolean allowExternalMessages) {
    }
}
