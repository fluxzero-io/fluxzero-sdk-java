/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.common;

import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class NamespacedComponentsTest {

    @Test
    void allApplicationComponentsShareOneCanonicalNamespaceGraph() {
        var fluxzero = TestFixture.create().getFluxzero();
        String applicationNamespace = fluxzero.client().namespace();

        assertCanonical(fluxzero.client(), applicationNamespace);
        assertCanonical(fluxzero.aggregateRepository(), applicationNamespace);
        assertCanonical(fluxzero.eventStore(), applicationNamespace);
        assertCanonical(fluxzero.snapshotStore(), applicationNamespace);
        assertCanonical(fluxzero.keyValueStore(), applicationNamespace);
        assertCanonical(fluxzero.documentStore(), applicationNamespace);
        assertCanonical(fluxzero.messageScheduler(), applicationNamespace);
        assertCanonical(fluxzero.commandGateway(), applicationNamespace);
        assertCanonical(fluxzero.queryGateway(), applicationNamespace);
        assertCanonical(fluxzero.eventGateway(), applicationNamespace);
        assertCanonical(fluxzero.resultGateway(), applicationNamespace);
        assertCanonical(fluxzero.errorGateway(), applicationNamespace);
        assertCanonical(fluxzero.metricsGateway(), applicationNamespace);
        assertCanonical(fluxzero.webRequestGateway(), applicationNamespace);
        assertCanonical(fluxzero.customGateway("canonical-test"), applicationNamespace);
    }

    private static <T extends Namespaced<T>> void assertCanonical(T applicationResource,
                                                                   String applicationNamespace) {
        T customerResource = applicationResource.forNamespace("customer");
        assertSame(applicationResource, customerResource.forNamespace(null));
        assertSame(applicationResource, customerResource.forNamespace(applicationNamespace));
        assertSame(customerResource, applicationResource.forNamespace("other").forNamespace("customer"));
    }
}
