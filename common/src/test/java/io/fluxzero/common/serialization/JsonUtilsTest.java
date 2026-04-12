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

package io.fluxzero.common.serialization;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {

    @Test
    void modulesFromServiceLoaderOverrideDefaultModules() {
        Map<?,?> map = JsonUtils.fromJson(
                """
                        {
                              "foo": "bar "
                        }
                        """, Map.class);
        assertEquals("bar ", map.get("foo"));
    }

    @Test
    void disableJsonIgnoreKeepsIgnoredJavaFieldInRoundTrip() throws Exception {
        ObjectMapper mapper = JsonUtils.writer.copy();
        JsonUtils.disableJsonIgnore(mapper);
        JsonIgnoredSample input = new JsonIgnoredSample("public", "private");

        var tree = mapper.valueToTree(input);
        var output = mapper.treeToValue(tree, JsonIgnoredSample.class);

        assertTrue(tree.has("hidden"));
        assertEquals(input.visible, output.visible);
        assertEquals(input.hidden, output.hidden);
    }

    @Value
    private static class JsonIgnoredSample {
        String visible;
        @JsonIgnore
        String hidden;
    }
}
