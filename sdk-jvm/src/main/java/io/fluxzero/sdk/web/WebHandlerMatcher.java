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

package io.fluxzero.sdk.web;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.ExecutableInvocation;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector.ExecutableViewHandlerMatcher;
import io.fluxzero.common.handling.HandlerInspector.MethodHandlerMatcher;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ComponentRegistryException;
import io.fluxzero.sdk.registry.GeneratedPropertyAccesses;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.MessageDescription;
import io.fluxzero.sdk.registry.PropertyDescriptor;
import io.fluxzero.sdk.registry.RegistryExecutableViews;
import io.fluxzero.sdk.registry.WebRouteDescriptor;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;

import java.lang.reflect.Executable;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.fluxzero.sdk.web.HttpRequestMethod.ANY;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.HEAD;
import static io.fluxzero.sdk.web.HttpRequestMethod.OPTIONS;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_HANDSHAKE;
import static io.fluxzero.sdk.web.HttpRequestMethod.isWebsocket;
import static io.fluxzero.sdk.web.DefaultWebRequestContext.getWebRequestContext;
import static io.fluxzero.sdk.web.WebUtils.getWebPatterns;
import static java.util.Arrays.stream;
import static java.util.stream.Stream.concat;

/**
 * Specialized {@link HandlerMatcher} that routes {@link DeserializingMessage}s of type {@link MessageType#WEBREQUEST}
 * to matching handler methods based on annotated URI patterns, HTTP methods, and optional origins.
 * <p>
 * This matcher is created internally by the {@link HandlerFactory} when registering a handler class that contains
 * methods annotated for web request handling (e.g., {@code @HandleWeb}).
 *
 * <h2>Routing Logic</h2>
 * The matcher builds an internal route table that maps:
 * <ul>
 *   <li>HTTP method (e.g., GET, POST)</li>
 *   <li>Normalized path (optionally prefixed by {@code @Path} at class or package level)</li>
 *   <li>Optional request origin (e.g., scheme and host) when specified in the handler method</li>
 * </ul>
 * During matching, the {@link DefaultWebRequestContext} is used to extract the URI path, method, and origin
 * from the incoming request metadata.
 *
 * <h2>WebPattern Matching</h2>
 * Each handler method may be associated with one or more {@link WebPattern}s, derived from {@link WebParameters}
 * annotations. These patterns define the matchable paths and methods.
 * <p>
 * If multiple routes match the same request, the most specific route is selected. Literal path parts outrank path
 * parameters, constrained path parameters outrank unconstrained parameters, and wildcard or catch-all routes are treated
 * as fallbacks. For example, {@code /a/b/c} wins over {@code /a/{value}/c}, which wins over {@code /a/*&#47;c}.
 *
 * <h2>Support for @Path Annotations</h2>
 * This matcher also respects {@code @Path} annotations on the method, declaring class, or package level,
 * combining those with the {@code WebPattern#getPath()} when routing requests.
 *
 * <h2>Fallback to ANY Method</h2>
 * If no handler matches the exact request method, but any handlers exist that declare {@code HttpRequestMethod.ANY},
 * these are checked as a fallback.
 *
 * <h2>Automatic HTTP Helpers</h2>
 * {@code HEAD} can fall back to a matching {@code GET} route, and {@code OPTIONS} can be generated from matching
 * routes. A factory-scoped route registry ensures explicit {@code HEAD}, {@code OPTIONS}, or {@code ANY} handlers
 * registered in another handler class win over generated helpers.
 *
 * @see HandlerMatcher
 * @see WebPattern
 * @see WebRequest
 * @see WebUtils#getWebPatterns
 */
public class WebHandlerMatcher implements HandlerMatcher<Object, DeserializingMessage> {
    private final WebRouteMatcher<WebMethodMatcher> routes = new WebRouteMatcher<>();
    private final WebRouteRegistry routeRegistry;
    private final boolean hasAnyHandlers;

    public static WebHandlerMatcher create(
            Object handler, List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerConfiguration<DeserializingMessage> config) {
        return create(handler, asClass(handler), parameterResolvers, config);
    }

    public static Object createRouteRegistry() {
        return new WebRouteRegistry();
    }

    protected static WebHandlerMatcher create(Object handler, Class<?> type,
                                              List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
                                              HandlerConfiguration<DeserializingMessage> config) {
        return create(handler, type, parameterResolvers, config, new WebRouteRegistry());
    }

    public static WebHandlerMatcher create(
            Object handler, List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerConfiguration<DeserializingMessage> config, Object routeRegistry) {
        if (!(routeRegistry instanceof WebRouteRegistry webRouteRegistry)) {
            throw new IllegalArgumentException(
                    "Route registry must be created by WebHandlerMatcher.createRouteRegistry()");
        }
        return create(handler, asClass(handler), parameterResolvers, config, webRouteRegistry);
    }

    protected static WebHandlerMatcher create(Object handler, Class<?> type,
                                              List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
                                              HandlerConfiguration<DeserializingMessage> config,
                                              WebRouteRegistry routeRegistry) {
        if (ComponentMetadataLookups.generatedOnlyMode()) {
            return createFromRegistry(handler, type, parameterResolvers, config, routeRegistry);
        }
        var matchers = concat(JvmCompatibilityBackend.introspector().getAllMethods(type).stream(), stream(type.getDeclaredConstructors()))
                .filter(m -> config.methodMatches(type, m))
                .flatMap(m -> Stream.of(new MethodHandlerMatcher<>(m, type, parameterResolvers, config))).toList();
        return new WebHandlerMatcher(handler, matchers, routeRegistry);
    }

    protected WebHandlerMatcher(Object handler,
                                List<MethodHandlerMatcher<DeserializingMessage>> methodHandlerMatchers,
                                WebRouteRegistry routeRegistry) {
        this(handler, routeRegistry, webMethodMatchers(handler, methodHandlerMatchers));
    }

    private WebHandlerMatcher(Object handler, WebRouteRegistry routeRegistry,
                              List<WebMethodMatcher> methodHandlerMatchers) {
        this.routeRegistry = routeRegistry;
        boolean hasAnyHandlers = false;
        List<WebPattern> registeredPatterns = new ArrayList<>();
        for (WebMethodMatcher m : methodHandlerMatchers) {
            List<WebPattern> webPatterns = List.of(m.pattern());
            registeredPatterns.addAll(webPatterns);
            for (WebPattern pattern : webPatterns) {
                if (ANY.equals(pattern.getMethod())) {
                    hasAnyHandlers = true;
                }
                routes.add(pattern, m);
            }
        }
        routeRegistry.register(this, registeredPatterns);
        this.hasAnyHandlers = hasAnyHandlers;
    }

    private static List<WebMethodMatcher> webMethodMatchers(
            Object handler, List<MethodHandlerMatcher<DeserializingMessage>> methodHandlerMatchers) {
        List<WebMethodMatcher> result = new ArrayList<>();
        Class<?> targetClass = asClass(handler);
        for (MethodHandlerMatcher<DeserializingMessage> matcher : methodHandlerMatchers) {
            for (WebPattern pattern : getWebPatterns(targetClass, handler, matcher.getExecutable())) {
                result.add(new WebMethodMatcher(matcher, pattern));
            }
        }
        return result;
    }

    private static WebHandlerMatcher createFromRegistry(
            Object handler, Class<?> type,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerConfiguration<DeserializingMessage> config,
            WebRouteRegistry routeRegistry) {
        List<WebMethodMatcher> matchers = ComponentMetadataLookups.registeredLookup(type)
                .map(lookup -> metadataRouteTypes(type, lookup).stream()
                        .flatMap(metadataType -> targetClassNames(metadataType).stream()
                                .flatMap(name -> lookup.routes(name, MessageType.WEBREQUEST).stream())
                                .filter(route -> !route.disabled())
                                .flatMap(route -> webMethodMatchers(
                                        handler, metadataType, route, parameterResolvers, config, lookup)))
                        .toList())
                .orElseGet(List::of);
        return new WebHandlerMatcher(handler, routeRegistry, matchers);
    }

    private static Stream<WebMethodMatcher> webMethodMatchers(
            Object handler, Class<?> type, HandlerRoute route,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerConfiguration<DeserializingMessage> config,
            ComponentMetadataLookup lookup) {
        return route.executableMetadata().stream()
                .map(executable -> RegistryExecutableViews.executableView(type, executable))
                .filter(view -> config.methodMatches(type, view))
                .flatMap(view -> generatedInvocation(type, view).stream()
                        .flatMap(invocation -> webPatterns(handler, type, route, lookup)
                                .map(pattern -> new WebMethodMatcher(new ExecutableViewHandlerMatcher<>(
                                        0, view, type, invocation, parameterResolvers, config), pattern))));
    }

    private static Optional<ExecutableInvocation> generatedInvocation(Class<?> type, io.fluxzero.common.handling.ExecutableView view) {
        ComponentMetadataLookups.ensureGeneratedExecutions(type);
        Optional<ExecutableInvocation> invocation = GeneratedExecutableInvocations.find(type, view.executableId());
        if (invocation.isEmpty()) {
            throw new ComponentRegistryException("""
                    Generated-only web handler execution for %s requires generated invocation plans.
                    Register generated invocations for matching web handler executables or run outside generated-only \
                    mode for JVM reflection compatibility.
                    """.formatted(type.getName()));
        }
        return invocation;
    }

    private static Stream<WebPattern> webPatterns(WebRouteDescriptor route) {
        return route.paths().stream()
                .flatMap(path -> route.methods().stream()
                        .map(method -> new WebPattern(
                                path, normalizeMethod(method), route.autoHead(), route.autoOptions())));
    }

    private static Stream<WebPattern> webPatterns(
            Object handler, Class<?> metadataType, HandlerRoute route, ComponentMetadataLookup lookup) {
        Optional<String> dynamicRoot = dynamicHandlerPath(handler, metadataType, lookup);
        if (dynamicRoot.isEmpty()) {
            return route.webRoutes().stream().flatMap(WebHandlerMatcher::webPatterns);
        }
        AnnotationDescriptor handlerAnnotation = route.annotationMetadata().orElse(null);
        return route.webRoutes().stream()
                .flatMap(webRoute -> dynamicWebPatterns(handlerAnnotation, webRoute, dynamicRoot.orElseThrow()));
    }

    private static Stream<WebPattern> dynamicWebPatterns(
            AnnotationDescriptor handlerAnnotation, WebRouteDescriptor route, String root) {
        List<String> paths = handlerAnnotation == null || handlerAnnotation.values("value").isEmpty()
                ? List.of("") : handlerAnnotation.values("value");
        return paths.stream()
                .flatMap(path -> route.methods().stream()
                        .map(method -> new WebPattern(
                                WebUtils.concatenateUrlParts(root, path),
                                normalizeMethod(method), route.autoHead(), route.autoOptions())));
    }

    private static String normalizeMethod(String method) {
        return "ANY".equals(method) ? ANY : method;
    }

    private static Optional<String> dynamicHandlerPath(
            Object handler, Class<?> metadataType, ComponentMetadataLookup lookup) {
        if (handler == null || handler instanceof Class<?>) {
            return Optional.empty();
        }
        return ComponentMetadataLookups.annotatedProperties(lookup, metadataType, Path.class).stream()
                .filter(WebHandlerMatcher::isDynamicPathProperty)
                .findFirst()
                .flatMap(property -> GeneratedPropertyAccesses.findReader(handler.getClass(), property.name())
                        .map(reader -> reader.read(handler)))
                .map(String::valueOf);
    }

    private static boolean isDynamicPathProperty(PropertyDescriptor property) {
        return property.annotations().stream()
                .map(annotation -> annotation.find(Path.class.getSimpleName(), Path.class.getName()))
                .flatMap(Optional::stream)
                .anyMatch(annotation -> annotation.firstValue("value").orElse("").isBlank());
    }

    private static List<Class<?>> metadataRouteTypes(Class<?> targetClass, ComponentMetadataLookup lookup) {
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        collectMetadataRouteTypes(targetClass, lookup, result);
        return List.copyOf(result);
    }

    private static void collectMetadataRouteTypes(
            Class<?> type, ComponentMetadataLookup lookup, LinkedHashSet<Class<?>> result) {
        if (type == null || Object.class.equals(type)) {
            return;
        }
        if (targetClassNames(type).stream().anyMatch(name -> lookup.component(name).isPresent())) {
            result.add(type);
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            collectMetadataRouteTypes(interfaceType, lookup, result);
        }
        collectMetadataRouteTypes(type.getSuperclass(), lookup, result);
    }

    private static List<String> targetClassNames(Class<?> targetClass) {
        Set<String> result = new LinkedHashSet<>();
        result.add(targetClass.getName());
        if (targetClass.getCanonicalName() != null) {
            result.add(targetClass.getCanonicalName());
        }
        return List.copyOf(result);
    }

    private static Class<?> asClass(Object value) {
        return value instanceof Class<?> type ? type : value.getClass();
    }

    @Override
    public boolean canHandle(DeserializingMessage message) {
        return methodMatcher(message).map(m -> m.canHandle(message)).orElse(false);
    }

    @Override
    public Stream<Executable> matchingMethods(DeserializingMessage message) {
        return methodMatcher(message).stream().flatMap(m -> m.matchingMethods(message));
    }

    @Override
    public Optional<HandlerInvoker> getInvoker(Object target, DeserializingMessage message) {
        return methodMatcher(message).flatMap(m -> m.getInvoker(target, message));
    }

    protected Optional<WebRouteHandler> methodMatcher(DeserializingMessage message) {
        if (message.getMessageType() != MessageType.WEBREQUEST) {
            return Optional.empty();
        }
        DefaultWebRequestContext context = getWebRequestContext(message);
        putWebRequestDescription(message, context);
        Optional<WebRouteMatcher.Match<WebMethodMatcher>> match =
                routes.match(context.getMethod(), context.getOrigin(), context.getRequestPath());
        if (match.isEmpty() && HEAD.equals(context.getMethod())) {
            match = routeRegistry.automaticHeadOwner(context.getOrigin(), context.getRequestPath())
                    .filter(entry -> entry.owner() == this)
                    .flatMap(entry -> routes.match(GET, context.getOrigin(), context.getRequestPath(),
                                                   WebPattern::isAutoHead));
        }
        if (match.isEmpty() && hasAnyHandlers) {
            match = routes.match(ANY, context.getOrigin(), context.getRequestPath());
        }
        Optional<WebRouteHandler> handler = match
                .filter(m -> Objects.equals(context.getOrigin(), m.pattern().getOrigin()))
                .map(m -> {
                    context.setPathMap(m.pathParameters());
                    return m.value();
                });
        if (handler.isPresent()) {
            return handler;
        }
        if (OPTIONS.equals(context.getMethod())) {
            return automaticOptionsMatcher(context);
        }
        return Optional.empty();
    }

    static void putWebRequestDescription(DeserializingMessage message, DefaultWebRequestContext context) {
        message.putContext(MessageDescription.class, new MessageDescription(describeWebRequest(context)));
    }

    static String describeWebRequest(DefaultWebRequestContext context) {
        String method = context.getMethod();
        String target = requestTarget(context);
        if (WS_HANDSHAKE.equals(method)) {
            return "websocket handshake " + target;
        }
        if (isWebsocket(method)) {
            return "websocket request %s %s".formatted(method, target);
        }
        return "web request %s %s".formatted(method, target);
    }

    private static String requestTarget(DefaultWebRequestContext context) {
        String query = context.getUri().getRawQuery();
        return query == null || query.isBlank()
                ? context.getRequestPath()
                : context.getRequestPath() + "?" + query;
    }

    private Optional<WebRouteHandler> automaticOptionsMatcher(DefaultWebRequestContext context) {
        return routeRegistry.automaticOptions(context.getOrigin(), context.getRequestPath())
                .filter(options -> options.owner() == this)
                .map(options -> new AutomaticOptionsMatcher(options.allowedMethods()));
    }

    private interface WebRouteHandler {
        boolean canHandle(DeserializingMessage message);

        Stream<Executable> matchingMethods(DeserializingMessage message);

        Optional<HandlerInvoker> getInvoker(Object target, DeserializingMessage message);
    }

    record WebMethodMatcher(HandlerMatcher<Object, DeserializingMessage> matcher, WebPattern pattern)
            implements WebRouteHandler {
        @Override
        public boolean canHandle(DeserializingMessage message) {
            return matcher.canHandle(message);
        }

        @Override
        public Stream<Executable> matchingMethods(DeserializingMessage message) {
            return matcher.matchingMethods(message);
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(Object target, DeserializingMessage message) {
            return matcher.getInvoker(target, message);
        }
    }

    record AutomaticOptionsMatcher(List<String> allowedMethods) implements WebRouteHandler {
        @Override
        public boolean canHandle(DeserializingMessage message) {
            return true;
        }

        @Override
        public Stream<Executable> matchingMethods(DeserializingMessage message) {
            return Stream.empty();
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(Object target, DeserializingMessage message) {
            String allow = String.join(", ", allowedMethods);
            return Optional.of(HandlerInvoker.call(
                    () -> WebResponse.builder().status(204).header("Allow", allow).build()));
        }
    }

}
