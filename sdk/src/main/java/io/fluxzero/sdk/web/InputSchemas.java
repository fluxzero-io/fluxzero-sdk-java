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

package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resolves directional component references after all recursive input and output schemas have been built. */
final class InputSchemas {
    private static final String INPUT = "#/x-fluxzero-input/";
    private static final String COMPONENT = "#/components/schemas/";
    private static final Set<String> OPAQUE = Set.of("example", "examples", "default", "enum", "const");
    private static final Set<String> NAMED_ENTRIES = Set.of(
            "properties", "patternProperties", "$defs", "definitions", "dependentSchemas", "schemas",
            "paths", "webhooks", "callbacks", "content", "responses", "headers", "links", "requestBodies",
            "parameters", "securitySchemes");

    private InputSchemas() { }

    static String reference(String name) {
        return INPUT + name;
    }

    static void resolve(ObjectNode document, Map<String, ObjectNode> generatedInputs, Map<String, JsonNode> overrides) {
        Map<String, ObjectNode> inputs = new LinkedHashMap<>(generatedInputs);
        overrides.forEach((path, schema) -> {
            if (path.equals("schemas") && schema.isObject()) {
                schema.fieldNames().forEachRemaining(inputs::remove);
            } else if (path.startsWith("schemas.")) {
                inputs.remove(path.substring("schemas.".length()).split("\\.")[0]);
            }
        });
        if (generatedInputs.isEmpty()) {
            return;
        }
        ObjectNode schemas = document.withObject("/components/schemas");
        Map<String, String> names = new LinkedHashMap<>();
        inputs.keySet().forEach(name -> names.put(name, name));
        // A nested directional difference also makes its containing schema directional. Resolve to a fixed point;
        // every pass adds at least one split, so even recursive schemas finish after at most inputs.size() passes.
        boolean changed;
        do {
            changed = false;
            for (var entry : inputs.entrySet()) {
                String name = entry.getKey();
                if (names.get(name).equals(name)
                    && !rewrite(entry.getValue().deepCopy(), names).equals(rewrite(schemas.get(name).deepCopy(), names))) {
                    String candidate = name + "Input";
                    int index = 2;
                    while (schemas.has(candidate) || names.containsValue(candidate)) {
                        candidate = name + "Input" + index++;
                    }
                    names.put(name, candidate);
                    changed = true;
                }
            }
        } while (changed);
        inputs.forEach((name, schema) -> schemas.set(names.get(name), rewrite(schema.deepCopy(), names)));
        rewrite(document, names);
    }

    private static JsonNode rewrite(JsonNode node, Map<String, String> names) {
        if (node instanceof ObjectNode object) {
            if (object.has("$ref")) {
                object.set("$ref", referenceValue(object.get("$ref"), names));
            }
            if (object.path("discriminator").path("mapping") instanceof ObjectNode mapping) {
                mapping.properties().forEach(entry -> mapping.set(entry.getKey(), referenceValue(entry.getValue(), names)));
            }
            object.properties().forEach(entry -> {
                // Examples and extensions are opaque application data, not schema references.
                String key = entry.getKey();
                if (NAMED_ENTRIES.contains(key) && entry.getValue().isObject()) {
                    // Names in these maps are application data, not schema keywords (including "$ref").
                    entry.getValue().forEach(value -> rewrite(value, names));
                } else if (!key.startsWith("x-") && !OPAQUE.contains(key)) {
                    rewrite(entry.getValue(), names);
                }
            });
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> rewrite(child, names));
        }
        return node;
    }

    private static JsonNode referenceValue(JsonNode value, Map<String, String> names) {
        if (value.isTextual() && value.asText().startsWith(INPUT)) {
            String name = value.asText().substring(INPUT.length());
            return TextNode.valueOf(COMPONENT + names.getOrDefault(name, name));
        }
        return value;
    }
}
