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
import io.fluxzero.sdk.modeling.Graph;

import java.io.IOException;
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

        Map<String, List<Graph<?>>> childrenByPath = new LinkedHashMap<>();
        for (Graph<?> child : graph.children()) {
            String path = child.relationshipPath();
            if (path == null || path.isBlank()) {
                continue;
            }
            childrenByPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(child);
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
