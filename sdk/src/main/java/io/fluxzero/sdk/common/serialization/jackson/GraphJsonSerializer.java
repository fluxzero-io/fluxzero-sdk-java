/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.common.serialization.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProperty;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializes a public {@link Graph} as its model value enriched with recursively composed child paths. */
public final class GraphJsonSerializer extends JsonSerializer<Graph<?>> {

    @Override
    public void serialize(
            Graph<?> graph,
            JsonGenerator generator,
            SerializerProvider serializers) throws IOException {
        JsonNode document = document(graph, generator.getCodec(), generator);
        generator.writeTree(document);
    }

    private static JsonNode document(
            Graph<?> graph,
            ObjectCodec codec,
            JsonGenerator generator) throws IOException {
        if (!(codec instanceof ObjectMapper mapper)) {
            throw JsonMappingException.from(generator, "Graph serialization requires a Jackson ObjectMapper codec");
        }
        Object value = graph.get();
        if (value == null) {
            return NullNode.getInstance();
        }
        JsonNode model = mapper.valueToTree(value);
        if (model == null || model.isNull()) {
            return NullNode.getInstance();
        }
        if (!(model instanceof ObjectNode object)) {
            throw JsonMappingException.from(
                    generator,
                    "Graph model %s must serialize as a JSON object, but produced %s"
                            .formatted(graph.type().getName(), model.getNodeType()));
        }

        addGraphProperties(graph, object, mapper, generator);

        Map<String, List<Graph<?>>> childrenByPath = new LinkedHashMap<>();
        for (Graph<?> child : graph.children()) {
            String path = child.relationshipPath();
            if (path == null || path.isBlank()) {
                continue;
            }
            childrenByPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(child);
        }
        for (String path : graph.childPaths()) {
            childrenByPath.computeIfAbsent(path, ignored -> new ArrayList<>());
        }
        for (Map.Entry<String, List<Graph<?>>> entry : childrenByPath.entrySet()) {
            ArrayNode values = object.arrayNode();
            for (Graph<?> child : entry.getValue()) {
                JsonNode childDocument = document(child, codec, generator);
                if (childDocument != null && !childDocument.isNull()) {
                    values.add(childDocument);
                }
            }
            set(object, entry.getKey(), values, generator);
        }
        return object;
    }

    private static void addGraphProperties(
            Graph<?> graph,
            ObjectNode document,
            ObjectMapper mapper,
            JsonGenerator generator) throws JsonMappingException {
        for (Method method : ReflectionUtils.getTypeMetadata(graph.type()).annotatedMethods(GraphProperty.class)) {
            GraphProperty annotation = method.getAnnotation(GraphProperty.class);
            String property = annotation.value().isBlank()
                    ? ReflectionUtils.getPropertyName(method) : annotation.value();
            if (Modifier.isStatic(method.getModifiers())
                || method.getReturnType() == void.class || method.getParameterCount() == 0) {
                throw JsonMappingException.from(generator,
                        "@GraphProperty method %s must be an instance method that returns a value and declares at "
                        + "least one Graph parameter"
                                .formatted(method.toGenericString()));
            }
            Parameter[] parameters = method.getParameters();
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = resolveGraphParameter(graph, parameters[i], method, generator);
            }
            try {
                Object value = DefaultMemberInvoker.asInvoker(method).invoke(graph.get(), arguments);
                document.set(property, mapper.valueToTree(value));
            } catch (RuntimeException e) {
                throw JsonMappingException.from(generator,
                        "Failed to evaluate @GraphProperty '%s' using %s"
                                .formatted(property, method.toGenericString()), e);
            }
        }
    }

    private static Graph<?> resolveGraphParameter(
            Graph<?> graph,
            Parameter parameter,
            Method method,
            JsonGenerator generator) throws JsonMappingException {
        if (!Graph.class.isAssignableFrom(parameter.getType())) {
            throw JsonMappingException.from(generator,
                    "@GraphProperty method %s may only declare Graph parameters"
                            .formatted(method.toGenericString()));
        }
        List<Type> arguments = ReflectionUtils.getTypeArguments(parameter.getParameterizedType());
        Class<?> modelType = arguments.size() == 1
                ? ReflectionUtils.rawClass(arguments.getFirst()) : Object.class;
        Graph<?> resolved = modelType.isAssignableFrom(graph.type())
                ? graph : graph.ancestor(modelType).orElse(null);
        if (resolved == null) {
            throw JsonMappingException.from(generator,
                    "Could not resolve Graph<%s> for @GraphProperty method %s"
                            .formatted(modelType.getName(), method.toGenericString()));
        }
        return resolved;
    }

    private static void set(
            ObjectNode document,
            String path,
            ArrayNode value,
            JsonGenerator generator) throws JsonMappingException {
        String[] segments = path.split("/");
        ObjectNode current = document;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonNode existing = current.get(segments[i]);
            if (existing == null || existing.isNull()) {
                ObjectNode nested = current.objectNode();
                current.set(segments[i], nested);
                current = nested;
            } else if (existing instanceof ObjectNode nested) {
                current = nested;
            } else {
                throw JsonMappingException.from(
                        generator,
                        "Graph path '%s' conflicts with model property '%s'"
                                .formatted(path, segments[i]));
            }
        }
        current.set(segments[segments.length - 1], value);
    }
}
