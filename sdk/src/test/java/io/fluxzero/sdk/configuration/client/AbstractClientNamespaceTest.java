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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class AbstractClientNamespaceTest {

    @Test
    void shuttingDownApplicationClientAlsoShutsDownNamespaceClients() {
        LocalClient applicationClient = LocalClient.newInstance();
        Client namespaceClient = applicationClient.forNamespace("customer");
        AtomicBoolean childShutdown = new AtomicBoolean();
        namespaceClient.beforeShutdown(() -> childShutdown.set(true));

        applicationClient.shutDown();

        assertTrue(childShutdown.get());
    }

    @Test
    void namespaceViewsAlwaysResolveThroughApplicationClient() {
        LocalClient applicationClient = LocalClient.newInstance();
        Client customerClient = applicationClient.forNamespace("customer");

        assertSame(applicationClient, customerClient.forNamespace(null));
        assertSame(applicationClient, customerClient.forNamespace(applicationClient.namespace()));
        assertSame(customerClient, applicationClient.forNamespace("other").forNamespace("customer"));
    }
}
