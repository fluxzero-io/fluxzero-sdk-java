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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared compile-time/runtime schema for the exact Decimal wire representation. */
final class DecimalSchema {
    private DecimalSchema() { }

    static ObjectNode create(boolean response) {
        ObjectNode string = JsonNodeFactory.instance.objectNode().put("type", "string").put("format", "decimal");
        ObjectNode schema;
        if (response) {
            schema = string;
        } else {
            schema = JsonNodeFactory.instance.objectNode();
            schema.putArray("oneOf").add(string).addObject().put("type", "number");
        }
        return schema.put("x-decimal", true).put("description",
                "Exact decimal string. Numeric input is accepted for compatibility. Keep strings intact in JavaScript.");
    }

    static void bounds(ObjectNode schema, String minimum, String maximum) {
        // Numeric schema keywords do not constrain strings. Preserve server bounds as explicit documentation.
        if (minimum != null && !minimum.isBlank()) {
            schema.put("x-decimal-minimum", minimum);
        }
        if (maximum != null && !maximum.isBlank()) {
            schema.put("x-decimal-maximum", maximum);
        }
    }
}
