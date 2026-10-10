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

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.application.SimplePropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CollectionValuesDefaultsTest {
    @ParameterizedTest
    @CsvSource({",,false", "2026.10.08,,false", "2026.10.09,,true", "2027.01.01,,true",
                ",true,true", "2026.10.08,true,true", "2026.10.09,false,false", "2027.01.01,false,false"})
    void versionAndPropertyPrecedence(String date,String override,boolean expected) {
        Map<String,String> properties = new HashMap<>();
        if(date!=null) properties.put(ApplicationProperties.DEFAULTS_VERSION_PROPERTY,date);
        if(override!=null) properties.put(ApplicationProperties.COLLECTION_VALUES_PROPERTY,override);
        assertEquals(expected,ApplicationProperties.collectionValues(new SimplePropertySource(properties)));
    }
    @Test void invalidDateFails() {
        assertThrows(IllegalArgumentException.class,()->ApplicationProperties.collectionValues(
                new SimplePropertySource(Map.of(ApplicationProperties.DEFAULTS_VERSION_PROPERTY,"invalid"))));
    }
    @Test
    void applicationsAndNamespacesResolveTheirOwnDefaults() {
        try (var modern = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.10.09"))).build(io.fluxzero.sdk.configuration.client.LocalClient.newInstance());
             var legacy = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .replacePropertySource(ignored -> new SimplePropertySource(Map.of())).build(io.fluxzero.sdk.configuration.client.LocalClient.newInstance())) {
            for (var app : java.util.List.of(modern, legacy)) {
                for (String namespace : java.util.List.of("one", "two")) {
                    var store = app.documentStore().forNamespace(namespace);
                    store.index(new Prices(java.util.List.of(10, 100)), "sparse", "values").join();
                    store.index(new Prices(java.util.List.of(40, 60)), "middle", "values").join();
                    // Deliberately execute under the other application's context.
                    (app == modern ? legacy : modern).apply(ignored -> {
                        assertEquals(app == modern ? "sparse" : "middle", store.search("values").sortBy("prices")
                                .streamHits(1).findFirst().orElseThrow().getId());
                        return null;
                    });
                }
            }
        }
    }

    record Prices(@io.fluxzero.common.search.Sortable java.util.List<Integer> prices) { }

}
