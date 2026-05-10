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
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector.MethodHandlerMatcher;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.asClass;
import static io.fluxzero.common.reflection.ReflectionUtils.getAllMethods;
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
 * @see HandlerMatcher
 * @see WebPattern
 * @see WebRequest
 * @see WebUtils#getWebPatterns
 */
public class WebHandlerMatcher implements HandlerMatcher<Object, DeserializingMessage> {
    private final WebRouteMatcher<WebMethodMatcher> routes = new WebRouteMatcher<>();
    private final boolean hasAnyHandlers;

    public static WebHandlerMatcher create(
            Object handler, List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerConfiguration<DeserializingMessage> config) {
        return create(handler, ReflectionUtils.asClass(handler), parameterResolvers, config);
    }

    protected static WebHandlerMatcher create(Object handler, Class<?> type,
                                              List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
                                              HandlerConfiguration<DeserializingMessage> config) {
        var matchers = concat(getAllMethods(type).stream(), stream(type.getDeclaredConstructors()))
                .filter(m -> config.methodMatches(type, m))
                .flatMap(m -> Stream.of(new MethodHandlerMatcher<>(m, type, parameterResolvers, config))).toList();
        return new WebHandlerMatcher(handler, matchers);
    }

    protected WebHandlerMatcher(Object handler,
                                List<MethodHandlerMatcher<DeserializingMessage>> methodHandlerMatchers) {
        boolean hasAnyHandlers = false;
        for (MethodHandlerMatcher<DeserializingMessage> m : methodHandlerMatchers) {
            List<WebPattern> webPatterns = getWebPatterns(asClass(handler), handler, m.getExecutable());
            for (WebPattern pattern : webPatterns) {
                if (HttpRequestMethod.ANY.equals(pattern.getMethod())) {
                    hasAnyHandlers = true;
                }
                routes.add(pattern, new WebMethodMatcher(m, pattern));
            }
        }
        this.hasAnyHandlers = hasAnyHandlers;
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

    protected Optional<MethodHandlerMatcher<DeserializingMessage>> methodMatcher(DeserializingMessage message) {
        if (message.getMessageType() != MessageType.WEBREQUEST) {
            return Optional.empty();
        }
        DefaultWebRequestContext context = getWebRequestContext(message);
        Optional<WebRouteMatcher.Match<WebMethodMatcher>> match =
                routes.match(context.getMethod(), context.getOrigin(), context.getRequestPath());
        if (match.isEmpty() && hasAnyHandlers) {
            match = routes.match(HttpRequestMethod.ANY, context.getOrigin(), context.getRequestPath());
        }
        return match
                .filter(m -> Objects.equals(context.getOrigin(), m.pattern().getOrigin()))
                .map(m -> {
                    context.setPathMap(m.pathParameters());
                    return m.value();
                })
                .map(WebMethodMatcher::matcher);
    }

    record WebMethodMatcher(MethodHandlerMatcher<DeserializingMessage> matcher, WebPattern pattern) { }

}
