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

package io.fluxzero.sdk.publishing;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

class TimeoutTest {

    @Test
    void testHandleSelf() {
        @Timeout(10)
        class HandleSelfRequest {
            @HandleQuery
            CompletableFuture<String> handle() {
                return new CompletableFuture<>();
            }
        }

        TestFixture.create().whenApplying(fc -> Fluxzero.queryAndWait(new HandleSelfRequest()))
                .expectExceptionalResult(TimeoutException.class);
    }

    @Test
    void testUnhandled() {
        @Timeout(10)
        class UnhandledRequest { }

        TestFixture.create().whenApplying(fc -> Fluxzero.queryAndWait(new UnhandledRequest()))
                .expectExceptionalResult(TimeoutException.class);
    }
}