/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelAncestorSubstepReplayTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterSubstepsResolveAncestorsCreatedWithinTheCommit(boolean existingHistory) throws Exception {
        LocalClient client = LocalClient.newInstance(Duration.ofDays(1));
        try (Fluxzero writer = application(client)) {
            if (existingHistory) {
                writer.executeModelCommit(new Message(new CreateRoot("unrelated"))).get(10, TimeUnit.SECONDS);
            }
            writeAndVerify(writer);
            writer.cache().clear();
            verifyMemberships(writer);
            verifyState(writer);
            try (Fluxzero reader = application(client)) {
                verifyMemberships(reader);
                verifyState(reader);
            }
        }
    }

    private static Fluxzero application(Client client) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().disableTrackingMetrics().build(client);
    }

    private static void writeAndVerify(Fluxzero writer) throws Exception {
        writer.executeModelCommit(new Message(new CreateFamily("root", "child"))).get(10, TimeUnit.SECONDS);
        verifyState(writer);
    }

    private static void verifyMemberships(Fluxzero reader) {
        var events = reader.client().getEventStoreClient().getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("root", -1L, 10),
                        new ModelEventStreamRequest("child", -1L, 10)), ModelReadBoundary.current(), 0L));
        assertEquals(List.of(0, 2), events.getStreams().getFirst().getMemberships().stream()
                .map(ModelEventMembership::getSubstep).toList());
        assertEquals(List.of(1, 3), events.getStreams().getLast().getMemberships().stream()
                .map(ModelEventMembership::getSubstep).toList());
        assertEquals(1, events.getStreams().stream().flatMap(s -> s.getMemberships().stream())
                .map(ModelEventMembership::getCommitId).distinct().count());
        assertEquals(4, events.getPayloads().size());
    }

    private static void verifyState(Fluxzero reader) {
        assertEquals(new Root("root", 2), reader.modelRepository().load("root", Root.class).get());
        assertEquals(new Child("child", "root", 2), reader.modelRepository().load("child", Child.class).get());
    }

    @Model(name = "AncestorSubstepRoot")
    record Root(@EntityId String rootId, int value) {}

    @Model(name = "AncestorSubstepChild")
    record Child(@EntityId String childId, @Parent(Root.class) String rootId, int observedRootValue) {}

    record CreateFamily(String rootId, String childId) {
        @InterceptApply
        List<?> split() {
            return List.of(new CreateRoot(rootId), new CreateChild(childId, rootId),
                           new IncrementRoot(rootId), new RefreshChild(childId));
        }
    }

    record CreateRoot(String rootId) {
        @Apply Root apply() { return new Root(rootId, 1); }
    }

    record CreateChild(String childId, String rootId) {
        @Apply Child apply(Root root) { return new Child(childId, rootId, root.value()); }
    }

    record RefreshChild(String childId) {
        @Apply Child apply(Child child, Root root) {
            return new Child(childId, child.rootId(), root.value());
        }
    }

    record IncrementRoot(String rootId) {
        @Apply Root apply(Root root) { return new Root(rootId, root.value() + 1); }
    }
}
