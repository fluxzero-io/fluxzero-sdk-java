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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Strictly combines independently generated OpenAPI documents.
 * <p>
 * Object members that occur in only one document are combined. Values present in more than one document must be
 * identical, while objects are compared recursively so that distinct routes and components can be combined. Arrays
 * are treated as atomic values because their order can be significant in OpenAPI. Operation ids must remain unique
 * across the resulting document.
 */
final class OpenApiDocumentMerger {
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private OpenApiDocumentMerger() {
    }

    static ObjectNode merge(Map<String, ? extends JsonNode> documents) {
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge an empty collection of OpenAPI documents");
        }
        Map<String, JsonNode> ordered = new TreeMap<>(documents);
        validateOperationIds(ordered);

        ObjectNode result = null;
        Map<String, String> origins = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : ordered.entrySet()) {
            if (!(entry.getValue() instanceof ObjectNode document)) {
                throw new IllegalArgumentException(
                        "OpenAPI document from '%s' must have a JSON object root".formatted(entry.getKey()));
            }
            if (result == null) {
                result = document.deepCopy();
                recordOrigins(result, "", entry.getKey(), origins);
            } else {
                mergeObject(result, document, "", entry.getKey(), origins);
            }
        }
        return result;
    }

    private static void mergeObject(ObjectNode target, ObjectNode addition, String pointer, String source,
                                    Map<String, String> origins) {
        addition.properties().forEach(entry -> {
            String childPointer = append(pointer, entry.getKey());
            JsonNode existing = target.get(entry.getKey());
            JsonNode value = entry.getValue();
            if (existing == null) {
                target.set(entry.getKey(), value.deepCopy());
                recordOrigins(value, childPointer, source, origins);
            } else if (existing.equals(value)) {
                // Exact duplicates are deliberately accepted.
            } else if (existing instanceof ObjectNode existingObject && value instanceof ObjectNode valueObject) {
                mergeObject(existingObject, valueObject, childPointer, source, origins);
            } else {
                String originalSource = origins.getOrDefault(childPointer, origins.getOrDefault(pointer, "unknown"));
                throw conflict(childPointer, originalSource, source);
            }
        });
    }

    private static void recordOrigins(JsonNode node, String pointer, String source, Map<String, String> origins) {
        origins.putIfAbsent(pointer, source);
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry ->
                    recordOrigins(entry.getValue(), append(pointer, entry.getKey()), source, origins));
        }
    }

    private static void validateOperationIds(Map<String, JsonNode> documents) {
        Map<String, OperationOrigin> operationIds = new HashMap<>();
        documents.forEach((source, document) -> {
            JsonNode paths = document.path("paths");
            if (!paths.isObject()) {
                return;
            }
            paths.properties().forEach(pathEntry -> {
                if (!pathEntry.getValue().isObject()) {
                    return;
                }
                pathEntry.getValue().properties().forEach(operationEntry -> {
                    if (!HTTP_METHODS.contains(operationEntry.getKey()) || !operationEntry.getValue().isObject()) {
                        return;
                    }
                    JsonNode operationId = operationEntry.getValue().get("operationId");
                    if (operationId == null || !operationId.isTextual() || operationId.textValue().isBlank()) {
                        return;
                    }
                    String location = "/paths/%s/%s".formatted(escape(pathEntry.getKey()), operationEntry.getKey());
                    OperationOrigin previous = operationIds.putIfAbsent(
                            operationId.textValue(), new OperationOrigin(source, location));
                    if (previous != null && !previous.location().equals(location)) {
                        throw new IllegalArgumentException(
                                "Duplicate OpenAPI operationId '%s' at %s from '%s'; already defined at %s from '%s'"
                                        .formatted(operationId.textValue(), location, source,
                                                   previous.location(), previous.source()));
                    }
                });
            });
        });
    }

    private static IllegalArgumentException conflict(String pointer, String originalSource, String source) {
        return new IllegalArgumentException(
                "Conflicting OpenAPI value at %s between '%s' and '%s'"
                        .formatted(pointer.isEmpty() ? "/" : pointer, originalSource, source));
    }

    private static String append(String pointer, String field) {
        return pointer + "/" + escape(field);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record OperationOrigin(String source, String location) {
    }
}
