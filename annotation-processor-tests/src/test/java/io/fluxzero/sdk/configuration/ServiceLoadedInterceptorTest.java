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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLoadedInterceptorTest {

    @Test
    void autoLoadsInterceptorsFromServiceLoader() {
        int before = ServiceLoadedInterceptor.batchInvocations.get();

        TestFixture.createAsync(new Handler())
                .whenCommand(new ServiceLoadedCommand())
                .expectEvents("dispatch service-loaded")
                .expectResult("handler service-loaded");

        assertTrue(ServiceLoadedInterceptor.batchInvocations.get() > before);
    }

    static class Handler {
        @HandleCommand
        String handle(ServiceLoadedCommand command) {
            Fluxzero.publishEvent("service-loaded");
            return "service-loaded";
        }
    }

    static class ServiceLoadedCommand {
    }
}
