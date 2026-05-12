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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

class HandleNotificationTests {

    TestFixture testFixture = TestFixture.createAsync(new Object() {
        @HandleNotification
        void handle(Integer event) {
            Fluxzero.publishEvent(String.valueOf(event));
        }
    }).atFixedTime(Instant.now().minus(1, ChronoUnit.DAYS));

    @Test
    void handlerReceivesNotification() {
        testFixture.whenEvent(1).expectEvents("1");
    }

    @Test
    void handlerReceivesEventAndNotification() {
        TestFixture.createAsync(new CombinedHandler())
                .whenEvent(new CombinedInput(1))
                .expectOnlyEvents(new EventHandled(1), new NotificationHandled(1));
    }

    @Test
    void consumerAnnotatedHandlerReceivesEventAndNotification() {
        TestFixture.createAsync(new ConsumerAnnotatedCombinedHandler())
                .whenEvent(new CombinedInput(2))
                .expectOnlyEvents(new EventHandled(2), new NotificationHandled(2));
    }

    record CombinedInput(int value) {
    }

    record EventHandled(int value) {
    }

    record NotificationHandled(int value) {
    }

    static class CombinedHandler {
        @HandleEvent
        void handleEvent(CombinedInput event) {
            Fluxzero.publishEvent(new EventHandled(event.value()));
        }

        @HandleNotification
        void handleNotification(CombinedInput event) {
            Fluxzero.publishEvent(new NotificationHandled(event.value()));
        }
    }

    @Consumer(name = "combined")
    static class ConsumerAnnotatedCombinedHandler extends CombinedHandler {
    }
}
