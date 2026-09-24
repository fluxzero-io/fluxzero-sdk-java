/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared structural shape for implicit polymorphic ID properties (not scalar root values). */
final class IdWireSchema {
    static ObjectNode schema(boolean modelOnly) {
        if (modelOnly) {
            return variant("name");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.putArray("oneOf").add(variant("name")).add(variant("@class"));
        return result;
    }

    private static ObjectNode variant(String discriminator) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
        var properties = result.putObject("properties");
        properties.putObject(discriminator).put("type", "string");
        properties.putObject("id").put("type", "string");
        result.putArray("required").add(discriminator).add("id");
        return result;
    }

    static ObjectNode retainNullability(ObjectNode original, ObjectNode result) {
        if (original == result) return result;
        if (original.has("nullable")) result.set("nullable", original.get("nullable"));
        if (original.path("type").isArray()) {
            for (var type : original.path("type")) {
                if ("null".equals(type.asText())) {
                    ObjectNode nullable = JsonNodeFactory.instance.objectNode();
                    nullable.putArray("anyOf").add(result).add(JsonNodeFactory.instance.objectNode().put("type", "null"));
                    return nullable;
                }
            }
        }
        return result;
    }

    private IdWireSchema() { }
}
