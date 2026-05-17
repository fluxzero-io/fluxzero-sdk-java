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

package io.fluxzero.sdk.test;

import io.fluxzero.sdk.common.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureResultTest {

    @Test
    void immutableResultCopiesMutableMessageCollections() {
        FixtureResult result = new FixtureResult();
        result.getEvents().add(new Message("event"));
        result.getCustomMessages().put("topic", new CopyOnWriteArrayList<>(List.of(new Message("custom"))));

        ImmutableFixtureResult snapshot = ImmutableFixtureResult.from(result);

        result.getEvents().add(new Message("late-event"));
        result.getCustomMessages().get("topic").add(new Message("late-custom"));

        assertEquals(List.of("event"), snapshot.getEvents().stream().map(Message::getPayload).toList());
        assertEquals(List.of("custom"),
                     snapshot.getCustomMessages().get("topic").stream().map(Message::getPayload).toList());
    }

    @Test
    void testFixtureExposesSnapshotResult() {
        TestFixture fixture = TestFixture.create();
        try {
            fixture.registerEvent(new Message("event"));

            ImmutableFixtureResult snapshot = fixture.getFixtureResult();

            fixture.registerEvent(new Message("late-event"));

            assertEquals(List.of("event"), snapshot.getEvents().stream().map(Message::getPayload).toList());
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }
}
