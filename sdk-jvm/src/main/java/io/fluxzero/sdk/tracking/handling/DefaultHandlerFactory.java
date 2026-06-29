/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.handling.DefaultHandler;
import io.fluxzero.common.handling.ExecutableAnnotationResolver;
import io.fluxzero.common.handling.ExecutableInvocationBackend;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ComponentRegistryException;
import io.fluxzero.sdk.registry.ExecutableKind;
import io.fluxzero.sdk.registry.InvocationPlanDescriptor;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;
import io.fluxzero.sdk.registry.JvmGeneratedExecutionInstaller;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.web.ApiReferenceEndpoint;
import io.fluxzero.sdk.web.DefaultWebRequestContext;
import io.fluxzero.sdk.web.HandleWeb;
import io.fluxzero.sdk.web.HandleWebResponse;
import io.fluxzero.sdk.web.OpenApiDocumentEndpoint;
import io.fluxzero.sdk.web.SocketEndpoint;
import io.fluxzero.sdk.web.SocketEndpointHandler;
import io.fluxzero.sdk.web.StaticFileHandler;
import io.fluxzero.sdk.web.WebHandlerMatcher;
import lombok.NonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Executable;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.fluxzero.common.handling.HandlerInspector.hasHandlerMethods;
import static io.fluxzero.sdk.common.ClientUtils.memoize;

/**
 * Default implementation of the {@link HandlerFactory} for creating message handlers based on reflection.
 * <p>
 * This factory supports a wide range of handler types including:
 * <ul>
 *     <li>Simple class-based handlers (e.g., annotated with {@code @HandleCommand}, {@code @HandleQuery}, etc.)</li>
 *     <li>{@link Stateful} handlers — persisted and associated via {@link Association}</li>
 *     <li>{@link SocketEndpoint} handlers — WebSocket-based interaction handlers</li>
 *     <li>{@link TrackSelf} annotated classes — handlers for self-tracking message types</li>
 * </ul>
 *
 * <h2>Customization</h2>
 * The factory is configured with the following pluggable components:
 * <ul>
 *     <li>A {@link MessageType} indicating the type of messages it supports (e.g., COMMAND, QUERY)</li>
 *     <li>A {@link HandlerDecorator} used to wrap all created handlers with additional behavior</li>
 *     <li>A list of {@link ParameterResolver}s to inject method parameters during handler invocation</li>
 *     <li>A {@link MessageFilter} that determines whether a message is applicable to a handler method</li>
 *     <li>A {@link HandlerRepository} supplier for managing persisted state in {@code @Stateful} handlers</li>
 *     <li>A {@link RepositoryProvider} for shared caching of handler state (e.g., in {@code SocketEndpointHandler})</li>
 * </ul>
 *
 * <h2>Handler Resolution Process</h2>
 * The factory inspects the provided target object (or class) and applies the following logic:
 * <ol>
 *     <li>If the target is annotated with {@link Stateful}, a {@link StatefulHandler} is created</li>
 *     <li>If the target is annotated with {@link SocketEndpoint}, a {@link SocketEndpointHandler} is created</li>
 *     <li>If the target is annotated with {@link TrackSelf}, a handler is created with a filter ensuring messages are routed to matching payload types</li>
 *     <li>Otherwise, a default handler is created using {@link DefaultHandler}</li>
 * </ol>
 *
 * <h2>Decorator Chaining</h2>
 * Any additional {@link HandlerInterceptor}s passed at creation are composed with the default decorator
 * and applied to the resulting handler.
 *
 * <h2>Search-Specific Filtering</h2>
 * For {@link MessageType#DOCUMENT} and {@link MessageType#CUSTOM}, additional filters like
 * {@link HandleDocumentFilter} and {@link HandleCustomFilter} are applied automatically.
 *
 * <p>
 * This class is the main entry point for reflective handler generation in Fluxzero. It is used by both local
 * and tracking-based handler registries to resolve method targets dynamically.
 *
 * @see HandlerFactory
 * @see HandlerConfiguration
 * @see HandlerInspector
 * @see StatefulHandler
 * @see SocketEndpointHandler
 * @see DefaultHandler
 */
public class DefaultHandlerFactory implements HandlerFactory {

    private final MessageType messageType;
    private final HandlerDecorator defaultDecorator;
    private final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    private final MessageFilter<? super DeserializingMessage> messageFilter;
    private final Class<? extends Annotation> handlerAnnotation;
    private final MethodInvocationValidator<? super DeserializingMessage> methodInvocationValidator;
    private final ExecutableAnnotationResolver executableAnnotationResolver;
    private final Function<Class<?>, HandlerRepository> handlerRepositorySupplier;
    private final RepositoryProvider repositoryProvider;
    private final boolean trackingMetricsEnabled;
    private final Serializer serializer;

    private final Set<StaticFileHandler> staticFileHandlers = ConcurrentHashMap.newKeySet();
    private final Set<OpenApiDocumentEndpoint> openApiDocumentEndpoints = ConcurrentHashMap.newKeySet();
    private final Set<ApiReferenceEndpoint> apiReferenceEndpoints = ConcurrentHashMap.newKeySet();
    private final Object webRouteRegistry = WebHandlerMatcher.createRouteRegistry();

    public DefaultHandlerFactory(MessageType messageType, HandlerDecorator defaultDecorator,
                                 List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
                                 MethodInvocationValidator<? super DeserializingMessage> methodInvocationValidator,
                                 Function<Class<?>, HandlerRepository> handlerRepositorySupplier,
                                 RepositoryProvider repositoryProvider,
                                 boolean trackingMetricsEnabled,
                                 Serializer serializer) {
        this.messageType = messageType;
        this.defaultDecorator = defaultDecorator;
        this.parameterResolvers = parameterResolvers;
        this.methodInvocationValidator = methodInvocationValidator;
        this.handlerRepositorySupplier = handlerRepositorySupplier;
        this.repositoryProvider = repositoryProvider;
        this.trackingMetricsEnabled = trackingMetricsEnabled;
        this.serializer = serializer;
        this.handlerAnnotation = getHandlerAnnotation(messageType);
        this.executableAnnotationResolver = MetadataExecutableAnnotationResolver.create();
        this.messageFilter = computeMessageFilter();
    }

    @Override
    public Optional<Handler<DeserializingMessage>> createHandler(Object target, HandlerFilter handlerFilter,
                                                                 List<HandlerInterceptor> extraInterceptors) {
        Class<?> targetClass = asClass(target);
        HandlerDecorator handlerDecorator =
                ObjectUtils.concat(extraInterceptors.stream(), Stream.of(defaultDecorator))
                        .reduce(HandlerDecorator::andThen).orElseThrow();
        return Optional.of(handlerAnnotation)
                .map(a -> HandlerConfiguration.<DeserializingMessage>builder().methodAnnotation(a)
                        .handlerFilter(handlerFilter).messageFilter(messageFilter)
                        .methodInvocationValidator(methodInvocationValidator)
                        .executableAnnotationResolver(executableAnnotationResolver)
                        .executableInvocationBackend(executableInvocationBackend())
                        .build())
                .filter(config -> isHandler(targetClass, config))
                .map(config -> buildHandler(target, config))
                .map(handler -> messageType.isRequest()
                        ? new ExpiredRequestDecorator(trackingMetricsEnabled, handlerAnnotation).wrap(handler)
                        : handler)
                .map(handlerDecorator::wrap);
    }

    protected boolean isHandler(Class<?> targetClass,
                                HandlerConfiguration<?> handlerConfiguration) {
        @SuppressWarnings("unchecked")
        HandlerConfiguration<DeserializingMessage> config =
                (HandlerConfiguration<DeserializingMessage>) handlerConfiguration;
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            if (RegistryHandlerMatcherFactory.hasRegisteredHandlersWithoutGeneratedInvocations(
                    targetClass, messageType, config)) {
                throw missingGeneratedInvocationPlans(targetClass);
            }
            if (RegistryHandlerMatcherFactory.hasRegisteredHandlers(targetClass, messageType, config)) {
                return true;
            }
            return ComponentMetadataLookups.typeAnnotation(targetClass, Stateful.class).isPresent()
                   || messageType == MessageType.WEBREQUEST
                   && (ComponentMetadataLookups.typeAnnotation(targetClass, SocketEndpoint.class).isPresent()
                       || StaticFileHandler.isHandler(targetClass));
        }
        if (hasHandlerMethods(targetClass, handlerConfiguration)) {
            return true;
        }
        if (ComponentMetadataLookups.typeAnnotation(targetClass, Stateful.class).isPresent()
            && hasMemberHandlerMethods(targetClass, handlerConfiguration, new HashSet<>())) {
            return true;
        }
        return messageType == MessageType.WEBREQUEST && StaticFileHandler.isHandler(targetClass);
    }

    protected boolean hasMemberHandlerMethods(Class<?> targetClass, HandlerConfiguration<?> handlerConfiguration,
                                              Set<Class<?>> visitedTypes) {
        if (targetClass == null || Object.class.equals(targetClass) || !visitedTypes.add(targetClass)) {
            return false;
        }
        for (AccessibleObject location : JvmCompatibilityBackend.introspector().getAnnotatedProperties(targetClass, Member.class)) {
            Class<?> memberType = JvmCompatibilityBackend.introspector().getCollectionElementType(location)
                    .orElse(JvmCompatibilityBackend.introspector().getPropertyType(location));
            if (hasHandlerMethods(memberType, handlerConfiguration)
                || hasMemberHandlerMethods(memberType, handlerConfiguration, new HashSet<>(visitedTypes))) {
                return true;
            }
        }
        return false;
    }

    protected Handler<DeserializingMessage> buildHandler(@NonNull Object target,
                                                         HandlerConfiguration<DeserializingMessage> config) {

        if (ifClass(target) instanceof Class<?> targetClass) {
            {
                Stateful handler = ComponentMetadataLookups.typeAnnotation(targetClass, Stateful.class)
                        .orElse(null);
                if (handler != null) {
                    var statefulConfig = config;
                    return new StatefulHandler(targetClass, createStatefulHandlerMatcher(
                                               targetClass, config, parameterResolvers),
                                               handlerRepositorySupplier.apply(targetClass),
                                               parameterResolvers,
                                               e -> statefulConfig.getAnnotation(e).orElse(null),
                                               (memberType, resolvers) -> createStatefulHandlerMatcher(
                                                       memberType, statefulConfig, resolvers),
                                               serializer);
                }
            }

            {
                SocketEndpoint handler = ComponentMetadataLookups.typeAnnotation(targetClass, SocketEndpoint.class)
                        .orElse(null);
                if (handler != null) {
                    var socketConfig = config;
                    return new SocketEndpointHandler(
                            targetClass, createHandlerMatcherOrEmpty(targetClass, socketConfig, parameterResolvers),
                            socketEndpointWrapperMatcher(socketConfig),
                            repositoryProvider, parameterResolvers,
                            e -> socketConfig.getAnnotation(e).orElse(null));
                }
            }

            {
                var trackSelf = ComponentMetadataLookups.typeOrPackageAnnotation(targetClass, TrackSelf.class);
                if (trackSelf.isPresent()) {
                    MessageFilter<DeserializingMessage> selfFilter = new MessageFilter<>() {
                        @Override
                        public boolean test(DeserializingMessage message, Executable method,
                                            Class<? extends Annotation> handlerAnnotation, Class<?> t) {
                            return t.isAssignableFrom(message.getPayloadClass());
                        }

                        @Override
                        public boolean test(DeserializingMessage message, ExecutableView method,
                                            Class<? extends Annotation> handlerAnnotation, Class<?> t) {
                            return t.isAssignableFrom(message.getPayloadClass());
                        }
                    };
                    config = config.toBuilder().messageFilter(selfFilter.and(config.messageFilter())).build();
                    return createDefaultHandler(targetClass, DeserializingMessage::getPayload, config);
                }
            }

            return createDefaultHandler(targetClass, createTargetSupplier(targetClass), config);
        }
        return createDefaultHandler(target, m -> target, config);
    }

    protected Function<DeserializingMessage, ?> createTargetSupplier(Class<?> targetClass) {
        Optional<Supplier<Object>> generatedConstructor = generatedNoArgConstructor(targetClass)
                .map(invocation -> memoize(() -> invocation.invoke(null)));
        if (generatedConstructor.isPresent()) {
            Supplier<Object> instanceSupplier = generatedConstructor.orElseThrow();
            return m -> targetClass.isAssignableFrom(m.getPayloadClass()) ? m.getPayload() : instanceSupplier.get();
        }
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            // Null makes instance methods ineligible for non-self payloads without trying a reflective constructor.
            return m -> targetClass.isAssignableFrom(m.getPayloadClass()) ? m.getPayload() : null;
        }
        if (JvmCompatibilityBackend.introspector().getDefaultConstructor(targetClass).isEmpty()) {
            // Null makes instance methods ineligible for non-self payloads without trying to instantiate the class.
            return m -> targetClass.isAssignableFrom(m.getPayloadClass()) ? m.getPayload() : null;
        }
        Supplier<Object> instanceSupplier = memoize(() -> JvmCompatibilityBackend.introspector().asInstance(targetClass));
        return m -> targetClass.isAssignableFrom(m.getPayloadClass()) ? m.getPayload() : instanceSupplier.get();
    }

    private static Optional<io.fluxzero.common.handling.ExecutableInvocation> generatedNoArgConstructor(
            Class<?> targetClass) {
        return GeneratedExecutableInvocations.find(targetClass, InvocationPlanDescriptor.executableId(
                ExecutableKind.CONSTRUCTOR, "<init>", List.of()));
    }

    protected Handler<DeserializingMessage> createDefaultHandler(
            Object target, Function<DeserializingMessage, ?> targetSupplier,
            HandlerConfiguration<DeserializingMessage> config) {
        return createDefaultHandler(target, targetSupplier, config, createHandlerMatcher(target, config));
    }

    protected Handler<DeserializingMessage> createDefaultHandler(
            Object target, Function<DeserializingMessage, ?> targetSupplier,
            HandlerConfiguration<DeserializingMessage> config,
            HandlerMatcher<Object, DeserializingMessage> handlerMatcher) {
        Class<?> targetClass = asClass(target);
        Handler<DeserializingMessage> handler
                = target instanceof Class<?>
                  ? new DefaultHandler<>(targetClass, targetSupplier, handlerMatcher)
                  : DefaultHandler.forTarget(targetClass, target, handlerMatcher);
        handler = RegistryFilteringHandler.wrap(handler, messageType);
        if (messageType == MessageType.WEBREQUEST) {
            for (OpenApiDocumentEndpoint endpoint : OpenApiDocumentEndpoint.forHandler(targetClass, target)) {
                if (openApiDocumentEndpoints.add(endpoint)) {
                    handler = handler.or(createDefaultHandler(endpoint, m -> endpoint, config));
                }
            }
            for (ApiReferenceEndpoint endpoint : ApiReferenceEndpoint.forHandler(targetClass)) {
                if (apiReferenceEndpoints.add(endpoint)) {
                    handler = handler.or(createDefaultHandler(endpoint, m -> endpoint, config));
                }
            }
            for (StaticFileHandler h : StaticFileHandler.forTargetClass(targetClass)) {
                if (staticFileHandlers.add(h)) {
                    @SuppressWarnings("unchecked")
                    MessageFilter<DeserializingMessage> baseFilter =
                            (MessageFilter<DeserializingMessage>) config.messageFilter();
                    var messageFilter = baseFilter.and(ignorePathsFilter(h));
                    var staticHandlerConfig = config.toBuilder().messageFilter(messageFilter).build();
                    handler = handler.or(createDefaultHandler(h, m -> h, staticHandlerConfig));
                }
            }
        }
        return handler;
    }

    private static MessageFilter<DeserializingMessage> ignorePathsFilter(StaticFileHandler handler) {
        return new MessageFilter<>() {
            @Override
            public boolean test(DeserializingMessage message, Executable executable,
                                Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
                return accepts(message);
            }

            @Override
            public boolean test(DeserializingMessage message, ExecutableView executable,
                                Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
                return accepts(message);
            }

            private boolean accepts(DeserializingMessage message) {
                var context = DefaultWebRequestContext.getWebRequestContext(message);
                return context == null || !context.matchesAny(handler.getIgnorePaths());
            }
        };
    }

    protected Class<? extends Annotation> getHandlerAnnotation(MessageType messageType) {
        return switch (messageType) {
            case COMMAND -> HandleCommand.class;
            case EVENT -> HandleEvent.class;
            case NOTIFICATION -> HandleNotification.class;
            case QUERY -> HandleQuery.class;
            case RESULT -> HandleResult.class;
            case ERROR -> HandleError.class;
            case SCHEDULE -> HandleSchedule.class;
            case METRICS -> HandleMetrics.class;
            case WEBREQUEST -> HandleWeb.class;
            case WEBRESPONSE -> HandleWebResponse.class;
            case DOCUMENT -> HandleDocument.class;
            case CUSTOM -> HandleCustom.class;
        };
    }

    protected HandlerMatcher<Object, DeserializingMessage> createHandlerMatcher(
            Object target, HandlerConfiguration<DeserializingMessage> config) {
        return createHandlerMatcher(target, config, parameterResolvers);
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    protected HandlerMatcher<Object, DeserializingMessage> createHandlerMatcher(
            Object target, HandlerConfiguration<DeserializingMessage> config,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        return switch (messageType) {
            case WEBREQUEST -> WebHandlerMatcher.create(target, parameterResolvers, config, webRouteRegistry);
            default -> {
                Class<?> targetClass = asClass(target);
                if (ComponentMetadataLookups.generatedOnlyMode()) {
                    if (RegistryHandlerMatcherFactory.hasRegisteredHandlersWithoutGeneratedInvocations(
                            targetClass, messageType, config)) {
                        throw missingGeneratedInvocationPlans(targetClass);
                    }
                    Optional<HandlerMatcher<Object, DeserializingMessage>> registryMatcher =
                            RegistryHandlerMatcherFactory.create(targetClass, messageType, parameterResolvers, config);
                    if (registryMatcher.isPresent()) {
                        yield registryMatcher.orElseThrow();
                    }
                    if (!RegistryHandlerMatcherFactory.hasRegisteredHandlerMetadata(targetClass, messageType)) {
                        throw missingGeneratedInvocationPlans(targetClass);
                    }
                    yield emptyHandlerMatcher();
                }
                yield HandlerInspector.inspect(targetClass, parameterResolvers, config);
            }
        };
    }

    private HandlerMatcher<Object, DeserializingMessage> createStatefulHandlerMatcher(
            Class<?> targetClass, HandlerConfiguration<DeserializingMessage> config,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        return createHandlerMatcherOrEmpty(targetClass, config, parameterResolvers);
    }

    private HandlerMatcher<Object, DeserializingMessage> createHandlerMatcherOrEmpty(
            Class<?> targetClass, HandlerConfiguration<DeserializingMessage> config,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            return createHandlerMatcher(targetClass, config, parameterResolvers);
        }
        if (messageType == MessageType.WEBREQUEST) {
            return WebHandlerMatcher.create(targetClass, parameterResolvers, config, webRouteRegistry);
        }
        if (RegistryHandlerMatcherFactory.hasRegisteredHandlersWithoutGeneratedInvocations(
                targetClass, messageType, config)) {
            throw missingGeneratedInvocationPlans(targetClass);
        }
        return RegistryHandlerMatcherFactory.create(targetClass, messageType, parameterResolvers, config)
                .orElseGet(DefaultHandlerFactory::emptyHandlerMatcher);
    }

    private HandlerMatcher<Object, DeserializingMessage> socketEndpointWrapperMatcher(
            HandlerConfiguration<DeserializingMessage> config) {
        return messageType == MessageType.WEBREQUEST
               ? createHandlerMatcher(SocketEndpointHandler.SocketEndpointWrapper.class, config)
               : emptyHandlerMatcher();
    }

    private static HandlerMatcher<Object, DeserializingMessage> emptyHandlerMatcher() {
        return new HandlerMatcher<>() {
            @Override
            public boolean canHandle(DeserializingMessage message) {
                return false;
            }

            @Override
            public Stream<Executable> matchingMethods(DeserializingMessage message) {
                return Stream.empty();
            }

            @Override
            public Stream<ExecutableView> matchingExecutableViews(DeserializingMessage message) {
                return Stream.empty();
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(Object target, DeserializingMessage message) {
                return Optional.empty();
            }
        };
    }

    private static ComponentRegistryException missingGeneratedInvocationPlans(Class<?> targetClass) {
        return new ComponentRegistryException("""
                Generated-only handler execution for %s requires generated invocation plans.
                Register generated invocations for the matching handler executables or run outside generated-only mode \
                for JVM reflection compatibility.
                """.formatted(targetClass.getName()));
    }

    private static ExecutableInvocationBackend executableInvocationBackend() {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            return JvmCompatibilityBackend.introspector().executableInvocationBackend();
        }
        return JvmGeneratedExecutionInstaller.executableInvocationBackend();
    }

    private static Class<?> ifClass(Object value) {
        return value instanceof Class<?> type ? type : null;
    }

    private static Class<?> asClass(Object value) {
        return value instanceof Class<?> type ? type : value.getClass();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected MessageFilter<? super DeserializingMessage> computeMessageFilter() {
        var defaultFilter = new PayloadFilter().and(new SegmentFilter());
        MessageFilter result = switch (messageType) {
            case CUSTOM -> defaultFilter.and((MessageFilter) new HandleCustomFilter());
            case DOCUMENT -> defaultFilter.and((MessageFilter) new HandleDocumentFilter());
            default -> defaultFilter;
        };
        return parameterResolvers.stream().flatMap(r -> r instanceof MessageFilter<?>
                        ? Stream.of((MessageFilter<HasMessage>) r) : Stream.empty())
                .reduce(MessageFilter::and).map(f -> f.and(result)).orElse(result);
    }

}
