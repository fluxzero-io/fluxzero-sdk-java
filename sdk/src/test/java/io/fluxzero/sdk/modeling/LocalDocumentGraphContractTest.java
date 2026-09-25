/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.contracts.DocumentGraphContract;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Execution(ExecutionMode.CONCURRENT)
@Order(7) // Start expensive classes early in the shared parallel suite.
class LocalDocumentGraphContractTest extends DocumentGraphContract {
    @Test
    void retainedBeginContextDoesNotVerifyAgainAfterItsOwnCommit() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance(null))) {
            AtomicReference<DeserializingMessage> retained = new AtomicReference<>();
            app.apply(fc -> fc.executeModelCommit(new Message(new RetainBegin("one", retained))).join());
            app.apply(fc -> fc.executeModelCommit(new Message(new RetainBegin("one", retained))).join());
            app.apply(fc -> {
                CommitAttempt context = retained.get().getContext(CommitAttempt.class).orElseThrow();
                @SuppressWarnings("unchecked") Entity<BeginDocument> before = (Entity<BeginDocument>) context.entity("one");
                Graph<BeginDocument> graph = Graphs.lazy(before, context, fc.modelRepository());
                assertEquals(new BeginDocument("one", 1), graph.get());
                assertEquals(context.readStateIndex(), graph.stateIndex());
                assertEquals(new BeginDocument("one", 2), Fluxzero.loadCurrentGraph("one", BeginDocument.class).get());
                return null;
            });
        }
    }

    @Model(persistence = ModelPersistence.DOCUMENT, eventPublication = EventPublication.NEVER)
    record BeginDocument(@EntityId String id, int value) {}
    record RetainBegin(String id, @JsonIgnore AtomicReference<DeserializingMessage> retained) {
        @Apply BeginDocument apply(@Nullable BeginDocument previous) {
            retained.set(DeserializingMessage.getCurrent());
            return new BeginDocument(id, previous == null ? 1 : previous.value() + 1);
        }
    }

    @Override protected Client[] clients(String namespace) {
        Client storage = LocalClient.newInstance(null);
        return new Client[]{storage, storage};
    }
}
