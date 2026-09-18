/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelIdentityContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parentScopedIdentitiesHaveIndependentHistoriesAndDeletion(boolean async) {
        var left = new FolderId("left");
        var right = new FolderId("right");
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateFolder(left), new CreateFolder(right),
                              new CreateEntry("entry", left, "old"))
                .whenExecuting(fc -> {
                    var oldGraph = Fluxzero.loadGraph(left, Folder.class, "entry", Entry.class);
                    String oldId = oldGraph.id().toString();
                    oldGraph.delete().commit();
                    Fluxzero.assertAndApply(new CreateEntry("entry", right, "moved"));
                    var newGraph = Fluxzero.loadGraph(right, Folder.class, "entry", Entry.class);
                    String newId = newGraph.id().toString();
                    assertNotEquals(oldId, newId);
                    assertEquals("entry", newGraph.get().entryId());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of(oldId, newId));
                    assertNull(Fluxzero.loadGraph(left, Folder.class, "entry", Entry.class).get());
                    assertEquals(new Entry("entry", right, "moved"),
                                 Fluxzero.loadGraph(right, Folder.class, "entry", Entry.class).get());
                    assertTrue(fc.eventStore().getEvents(oldId).count() >= 1);
                    assertEquals(1, fc.eventStore().getEvents(newId).count());
                    Fluxzero.loadGraph(left).delete().commit();
                    assertNotNull(Fluxzero.loadGraph(right, Folder.class, "entry", Entry.class).get());
                    Fluxzero.loadGraph(right).delete().commit();
                    assertNull(Fluxzero.loadGraph(right, Folder.class, "entry", Entry.class).get());
                }).expectSuccessfulResult().expectNoErrors();
    }

    static final class FolderId extends Id<Folder> { FolderId(String value) { super(value); } }
    @Model record Folder(@EntityId FolderId folderId) {}
    @Model record Entry(@EntityId(parentScoped = true) String entryId,
                        @Parent(pathInParent = "entries") FolderId folderId, String value) {}
    record CreateFolder(FolderId folderId) {
        @Apply Folder create() { return new Folder(folderId); }
    }
    record CreateEntry(String entryId, FolderId folderId, String value) {
        @Apply Entry create() { return new Entry(entryId, folderId, value); }
    }
}
