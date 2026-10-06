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

package io.fluxzero.sdk.configuration.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalNamespaceDiscoveryTest {
    @Test
    void sharesSnapshotsAcrossSiblingsButNotIndependentClients() {
        var root = LocalClient.newInstance();
        var other = LocalClient.newInstance();
        try {
            var before = root.getNamespaces().join();
            var a = root.forNamespace("a");
            IntStream.range(0, 20).parallel().forEach(i -> a.forNamespace("b"));
            assertEquals(List.of("public"), before);
            assertEquals(List.of("a", "b", "public"), root.getNamespaces().join());
            assertEquals(root.getNamespaces().join(), a.getNamespaces().join());
            assertEquals(List.of("public"), other.getNamespaces().join());
            a.shutDown();
            assertEquals(List.of("a", "b", "public"), root.getNamespaces().join());
        } finally {
            root.shutDown();
            other.shutDown();
        }
    }
}
