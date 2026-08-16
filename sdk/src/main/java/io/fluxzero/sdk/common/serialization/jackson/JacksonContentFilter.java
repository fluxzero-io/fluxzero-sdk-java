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

package io.fluxzero.sdk.common.serialization.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.serialization.ContentFilter;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.tracking.handling.InputParameterResolver;
import io.fluxzero.sdk.tracking.handling.authentication.CurrentUserParameterResolver;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.memoize;
import static java.util.stream.Collectors.toMap;

/**
 * A {@link ContentFilter} implementation that uses Jackson to filter content dynamically for a specific {@link User}.
 * <p>
 * This class enables context-aware filtering based on {@link FilterContent} annotated handler methods in the value’s
 * class. These handlers can compute and return a filtered version of an object based on the current user context.
 * <p>
 * Filtering is performed via Jackson serialization, where a custom serializer is installed to intercept object
 * serialization and invoke the appropriate content filtering logic.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li>A {@link FilteringSerializer} is registered with Jackson using a {@link BeanSerializerModifier}.</li>
 *   <li>The serializer tries to invoke a {@link FilterContent}-annotated method using the {@link HandlerInspector}
 *       and passes in the root object and current {@link User} (via parameter resolvers).</li>
 *   <li>If filtering returns a different value, that value is serialized instead.</li>
 * </ul>
 *
 * @see FilterContent
 * @see User
 * @see HandlerMatcher
 */
@Slf4j
public class JacksonContentFilter implements ContentFilter {

    private final ObjectMapper mapper;
    private final Function<Class<?>, HandlerMatcher<Object, Object>> graphMatcherCache = memoize(
            type -> HandlerInspector.inspect(type, List.of(new CurrentUserParameterResolver(),
                                                           new GraphParameterResolver(),
                                                           new InputParameterResolver()), FilterContent.class));
    private final Function<Class<?>, HandlerMatcher<Object, Object>> descendantGraphMatcherCache = memoize(
            type -> HandlerInspector.inspect(
                    type, List.of(new CurrentUserParameterResolver(),
                                  new GraphParameterResolver(), new InputParameterResolver()),
                    HandlerConfiguration.builder().methodAnnotation(FilterContent.class)
                            .handlerFilter((ignored, method) -> ReflectionUtils
                                    .<FilterContent>getMethodAnnotation(method, FilterContent.class)
                                    .map(FilterContent::descendants).orElse(false))
                            .build()));

    /**
     * Creates a new content filter using the provided {@link ObjectMapper}.
     * <p>
     * The provided mapper is kept unchanged.
     * A single internal copy is configured with:
     * <ul>
     *     <li>ALWAYS inclusion policy (to serialize nulls)</li>
     *     <li>A {@link FilteringSerializer} for applying {@link FilterContent} annotations</li>
     *     <li>Disabled {@link JsonIgnore} handling, to ensure all fields are considered for filtering</li>
     * </ul>
     *
     * @param mapper an ObjectMapper used for filtering and serialization
     */
    public JacksonContentFilter(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.setDefaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS));
        this.mapper.registerModule(new SimpleModule() {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addBeanSerializerModifier(new BeanSerializerModifier() {
                    @SuppressWarnings("unchecked")
                    @Override
                    public JsonSerializer<?> modifySerializer(
                            SerializationConfig config, BeanDescription desc, JsonSerializer<?> serializer) {
                        return new FilteringSerializer((JsonSerializer<Object>) serializer);
                    }

                    @SuppressWarnings("unchecked")
                    @Override
                    public JsonSerializer<?> modifyMapSerializer(SerializationConfig config, MapType valueType,
                                                                 BeanDescription beanDesc,
                                                                 JsonSerializer<?> serializer) {
                        return new FilteringSerializer((JsonSerializer<Object>) serializer);
                    }

                });
            }
        });
        JsonUtils.disableJsonIgnore(this.mapper);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T filterContent(T value, User viewer) {
        return switch (value) {
            case null -> null;
            case Graph<?> graph -> (T) filterGraph(graph, viewer);
            case Optional<?> optional -> (T) optional.map(v -> filterContent(v, viewer));
            case Collection<?> collection -> (T) collection.stream().flatMap(
                    v -> v == null ? Stream.of((Object) null)
                            : Optional.ofNullable(filterContent(v, viewer)).stream()).toList();
            case Map<?, ?> map -> (T) map.entrySet().stream().flatMap(entry -> {
                var v = filterContent(entry.getValue(), viewer);
                return v == null ? Stream.empty() : Stream.of(new AbstractMap.SimpleEntry<>(entry.getKey(), v));
            }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v2, LinkedHashMap::new));
            default -> {
                try {
                    FilteringSerializer.rootValue.set(value);
                    yield viewer == null ? mapper.convertValue(value, (Class<T>) value.getClass()) :
                            viewer.apply(() -> mapper.convertValue(value, (Class<T>) value.getClass()));
                } catch (Exception e) {
                    log.error("Failed to filter content (type {}) for viewer {}", value.getClass(), viewer, e);
                    yield value;
                } finally {
                    FilteringSerializer.rootValue.remove();
                }
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Graph<?> filterGraph(Graph<?> graph, User viewer) {
        Graph<?> selected = filterGraphView(graph, viewer);
        if (selected == null) {
            return null;
        }
        Graph<?> root = selected.root();
        Graph<?> filtered = Graphs.mapValues((Graph) selected, node -> filterGraphValue(node, root, viewer));
        return filtered.isEmpty() ? null : filtered;
    }

    @SneakyThrows
    private Graph<?> filterGraphView(Graph<?> graph, User viewer) {
        Object value = graph.get();
        if (value == null) {
            return graph;
        }
        Optional<HandlerInvoker> invoker = graphMatcherCache.apply(value.getClass()).getInvoker(value, graph);
        if (invoker.isEmpty() || !returnsGraph(invoker.get())) {
            return graph;
        }
        Object result = viewer == null ? invoker.get().invoke() : viewer.apply(invoker.get()::invoke);
        if (result == null) {
            return null;
        }
        if (!(result instanceof Graph<?> filtered)
            || !Objects.equals(graph.id(), filtered.id())
            || graph.type() != filtered.type()
            || graph.stateIndex() != filtered.stateIndex()) {
            throw new IllegalStateException(
                    "A graph content filter must return a view of the graph it filters");
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    @SneakyThrows
    private Object filterGraphValue(Graph<?> graph, Graph<?> root, User viewer) {
        Object value = graph.get();
        if (value == null) {
            return null;
        }
        Object previousRoot = FilteringSerializer.rootValue.get();
        Graph<?> previousGraph = FilteringSerializer.currentGraph.get();
        FilteringSerializer.rootValue.set(root);
        FilteringSerializer.currentGraph.set(graph);
        try {
            if (graph != root) {
                Object rootValue = root.get();
                Optional<HandlerInvoker> rootInvoker = rootValue == null ? Optional.empty()
                        : descendantGraphMatcherCache.apply(rootValue.getClass()).getInvoker(rootValue, root);
                if (rootInvoker.isPresent()) {
                    value = viewer == null ? rootInvoker.get().invoke() : viewer.apply(rootInvoker.get()::invoke);
                    if (value == null) {
                        return null;
                    }
                }
            }
            Optional<HandlerInvoker> invoker = graphMatcherCache.apply(value.getClass()).getInvoker(value, root);
            return invoker.isEmpty()
                    || returnsGraph(invoker.get()) ? value
                    : viewer == null ? invoker.get().invoke() : viewer.apply(invoker.get()::invoke);
        } finally {
            restore(FilteringSerializer.currentGraph, previousGraph);
            restore(FilteringSerializer.rootValue, previousRoot);
        }
    }

    private static <T> void restore(ThreadLocal<T> context, T previous) {
        if (previous == null) {
            context.remove();
        } else {
            context.set(previous);
        }
    }

    private static boolean returnsGraph(HandlerInvoker invoker) {
        return invoker.getMethod() instanceof Method method
               && Graph.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Custom Jackson serializer that attempts to invoke a {@link FilterContent} handler method during serialization.
     * <p>
     * It caches matchers by class and uses {@link HandlerInspector} to find the appropriate handler methods.
     * <p>
     * The serializer behaves gracefully:
     * <ul>
     *   <li>If the handler returns {@code null} and the object is not part of an array, {@code null} is written out.</li>
     *   <li>If filtering fails, the original object is serialized as a fallback.</li>
     * </ul>
     *
     * The root object (used for matching context) is tracked using a thread-local field {@link #rootValue}.
     */
    @AllArgsConstructor
    @Slf4j
    protected static class FilteringSerializer extends JsonSerializer<Object>
            implements ContextualSerializer, ResolvableSerializer {

        protected static final ThreadLocal<Object> rootValue = new ThreadLocal<>();
        protected static final ThreadLocal<Graph<?>> currentGraph = new ThreadLocal<>();

        private final Function<Class<?>, HandlerMatcher<Object, Object>> matcherCache = memoize(
                type -> HandlerInspector.inspect(type, List.of(new CurrentUserParameterResolver(),
                                                               new GraphParameterResolver(),
                                                               new InputParameterResolver()), FilterContent.class));
        private final JsonSerializer<Object> defaultSerializer;

        @Override
        @SneakyThrows
        public void serialize(Object input, JsonGenerator jsonGenerator, SerializerProvider provider) {
            serializeAndThen(input, jsonGenerator, value -> defaultSerializer.serialize(
                    value, jsonGenerator, provider));
        }

        @Override
        public void serializeWithType(Object input, JsonGenerator jsonGenerator, SerializerProvider provider,
                                      TypeSerializer typeSerializer) {
            serializeAndThen(input, jsonGenerator, value -> defaultSerializer.serializeWithType(
                    value, jsonGenerator, provider, typeSerializer));
        }

        /**
         * Invokes the content filter if available and serializes the filtered result.
         * If filtering fails, it logs a warning and falls back to serializing the original object.
         *
         * @param input           the object to serialize
         * @param jsonGenerator   the JSON generator
         * @param followUp        logic to continue serialization with the possibly filtered result
         */
        @SneakyThrows
        public void serializeAndThen(Object input, JsonGenerator jsonGenerator, ThrowingConsumer<Object> followUp) {
            Object value = input instanceof Map<?, ?> map ? filterMapEntries(map) : input;
            try {
                value = filterValue(value);
                if (value == null) {
                    if (!jsonGenerator.getOutputContext().inArray()) {
                        jsonGenerator.writeNull();
                    }
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed to filter content (type {}) for viewer {}", input.getClass(), User.getCurrent(), e);
                throw e;
            }
            followUp.accept(value);
        }

        private Map<Object, Object> filterMapEntries(Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object filteredValue = filterValue(entry.getValue());
                if (filteredValue != null) {
                    result.put(entry.getKey(), filteredValue);
                }
            }
            return result;
        }

        @SneakyThrows
        private Object filterValue(Object value) {
            if (value == null) {
                return null;
            }
            Optional<HandlerInvoker> invoker = matcherCache.apply(value.getClass()).getInvoker(value, rootValue.get());
            return invoker.isPresent() ? invoker.get().invoke() : value;
        }

        /**
         * Determines if the value should be considered empty, based on whether filtering returns null.
         */
        @Override
        public boolean isEmpty(SerializerProvider provider, Object value) {
            if (super.isEmpty(provider, value)) {
                return true;
            }
            try {
                return matcherCache.apply(value.getClass()).getInvoker(value, rootValue.get())
                        .filter(handlerInvoker -> handlerInvoker.invoke() == null).isPresent();
            } catch (Exception ignored) {
                return false;
            }
        }

        @Override
        @SneakyThrows
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property) {
            if (defaultSerializer instanceof ContextualSerializer contextualSerializer) {
                @SuppressWarnings("unchecked")
                JsonSerializer<Object> contextualized =
                        (JsonSerializer<Object>) contextualSerializer.createContextual(provider, property);
                return contextualized == defaultSerializer ? this : new FilteringSerializer(contextualized);
            }
            return this;
        }

        @Override
        @SneakyThrows
        public void resolve(SerializerProvider provider) {
            if (defaultSerializer instanceof ResolvableSerializer resolvableSerializer) {
                resolvableSerializer.resolve(provider);
            }
        }
    }

    private static final class GraphParameterResolver implements ParameterResolver<Object> {
        @Override
        public Function<Object, Object> resolve(Parameter parameter, Annotation methodAnnotation) {
            Class<?> modelType = graphModelType(parameter);
            if (modelType == null) {
                return null;
            }
            return ignored -> resolve(modelType);
        }

        @Override
        public boolean matches(Parameter parameter, Annotation methodAnnotation, Object value) {
            Class<?> modelType = graphModelType(parameter);
            return modelType != null && resolve(modelType) != null;
        }

        @Override
        public boolean mayApply(java.lang.reflect.Executable method, Class<?> targetClass) {
            return java.util.Arrays.stream(method.getParameters())
                    .anyMatch(parameter -> Graph.class.isAssignableFrom(parameter.getType()));
        }

        private static Graph<?> resolve(Class<?> modelType) {
            Graph<?> graph = FilteringSerializer.currentGraph.get();
            if (graph == null) {
                return null;
            }
            if (modelType.isAssignableFrom(graph.type())) {
                return graph;
            }
            return graph.ancestor(modelType).orElse(null);
        }

        private static Class<?> graphModelType(Parameter parameter) {
            if (!Graph.class.isAssignableFrom(parameter.getType())) {
                return null;
            }
            List<Type> arguments = ReflectionUtils.getTypeArguments(parameter.getParameterizedType());
            return arguments.size() == 1 ? ReflectionUtils.rawClass(arguments.getFirst()) : Object.class;
        }
    }
}
