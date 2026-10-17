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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.AssertCurrent;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ModelAfterHandlerTest {
    static final List<String> calls = new CopyOnWriteArrayList<>();
    TestFixture fixture;

    TestFixture fixture(boolean async) {
        calls.clear();
        fixture = async ? TestFixture.createAsync(Counter.class, Container.class) : TestFixture.create(Counter.class, Container.class);
        return fixture.givenCommands(new Seed("counter", "document"));
    }

    @AfterEach void close() { if (fixture != null) fixture.getFluxzero().close(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryOutputChecksSeeLaterWritesAndKeepTheirOwnContext(boolean async) {
        fixture(async).whenCommand(new Issue("counter", "document", true))
                .expectSuccessfulResult().expectNoErrors();
        assertEquals(List.of("before:0", "payload:1:issued", "nested:1:issued", "counter:1:issued"), calls);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedFinalCheckRollsBackEveryOutput(boolean async) {
        fixture(async).whenCommand(new Issue("counter", "document", false))
                .expectExceptionalResult(IllegalCommandException.class).expectNoEvents()
                .expectThat(fc -> {
                    assertEquals(0, Fluxzero.loadGraph("counter", Counter.class).get().value());
                    assertFalse(Fluxzero.loadGraph("document", Document.class).get().issued());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void standaloneNumberChangeCannotPassWithoutTheDocument(boolean async) {
        fixture(async).whenCommand(new Advance("counter", "document"))
                .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eachOutputChecksTheSameFinalValueAfterTemporaryInvalidState(boolean async) {
        fixture(async).whenCommand(new Repair("counter"))
                .expectSuccessfulResult().expectNoErrors();
        assertEquals(List.of("before:0", "before:-1", "set:-1:2", "set:2:2"), calls);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryAfterCheckSeesAutomaticChildDeletion(boolean async) {
        fixture(async).givenCommands(new CreateRoot("root"), new CreateChild("child", "root"))
                .whenCommand(new DeleteRoot("root", "child"))
                .expectSuccessfulResult().expectNoErrors();
        assertEquals(List.of("child-absent"), calls);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retainedOutputsAreNotCheckedTwiceAndSeeLaterChanges(boolean async) {
        fixture(async).whenCommand(new RetainAndRepair("counter"))
                .expectSuccessfulResult().expectNoErrors();
        assertEquals(List.of("retained:2"), calls);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void memberChecksSeeFinalMemberAndDoNotRunDuringReplay(boolean async) {
        fixture(async).givenCommands(new CreateContainer("container"))
                .whenCommand(new RepairMember("container", "member"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(List.of("member:-1:2", "member:2:2"), calls);
                    ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) fc.modelRepository())
                            .invalidateModels(List.of("container"));
                    assertEquals(2, Fluxzero.loadModel("container", Container.class).get().part().value());
                    assertEquals(List.of("member:-1:2", "member:2:2"), calls);
                });
    }
    @Model record Container(@EntityId String containerId, @Member Part part) {}
    record Part(@EntityId String partId, int value) {
        @Apply Part apply(SetMember command) { return new Part(partId, command.value()); }
        @AssertLegal(afterHandler = true) void after(SetMember command) {
            assertEquals(2, value); calls.add("member:" + command.value() + ":" + value);
        }
    }
    record CreateContainer(String containerId) {
        @Apply Container apply() { return new Container(containerId, new Part("member", 0)); }
    }
    record RepairMember(String containerId, String partId) {
        @InterceptApply List<?> split() {
            return List.of(new SetMember(containerId, partId, -1), new SetMember(containerId, partId, 2));
        }
    }
    record SetMember(String containerId, String partId, int value) {}

    @Model record Counter(@EntityId String counterId, int value) {
        @AssertLegal(afterHandler = true)
        void issued(Advance action) {
            Document document = Fluxzero.loadGraph(action.documentId(), Document.class).get();
            if (!document.issued()) throw new IllegalCommandException("Document must be issued");
            calls.add("counter:" + value + ":issued");
        }
    }
    @Model record Document(@EntityId String documentId, boolean issued) {}
    record Seed(String counterId, String documentId) {
        @Apply Counter counter() { return new Counter(counterId, 0); }
        @Apply Document document() { return new Document(documentId, false); }
    }
    record Issue(String counterId, String documentId, boolean publish) {
        @InterceptApply List<?> split() {
            return List.of(new Message(new Advance(counterId, documentId), Metadata.of("part", "advance")),
                           new Publish(documentId, publish));
        }
    }
    record Advance(String counterId, String documentId) {
        @AssertLegal void before(Counter counter) { calls.add("before:" + counter.value()); }
        @Apply Counter apply(Counter counter) { return new Counter(counterId, counter.value() + 1); }
        @AssertLegal(afterHandler = true)
        Object after(Counter counter, Document document, Metadata metadata) {
            if (!document.issued()) throw new IllegalCommandException("Document must be issued");
            assertEquals("advance", metadata.get("part"));
            calls.add("payload:" + counter.value() + ":issued");
            return new FinalGuard();
        }
    }
    record FinalGuard() {
        @AssertLegal(afterHandler = true) void after(Advance source, Counter counter, Document document) {
            assertEquals("counter", source.counterId());
            assertTrue(document.issued());
            calls.add("nested:" + counter.value() + ":issued");
        }
    }
    record Publish(String documentId, boolean publish) {
        @Apply Document apply(Document document) { return new Document(documentId, publish); }
    }
    record Repair(String counterId) {
        @InterceptApply List<?> split() { return List.of(new SetValue(counterId, -1), new SetValue(counterId, 2)); }
    }
    record SetValue(String counterId, int value) {
        @AssertLegal void before(Counter counter) { calls.add("before:" + counter.value()); }
        @Apply Counter apply(Counter counter) { return new Counter(counterId, value); }
        @AssertLegal(afterHandler = true) void after(Counter counter) {
            if (counter.value() < 0) throw new IllegalCommandException("Final value must be nonnegative");
            calls.add("set:" + value + ":" + counter.value());
        }
    }
    @Model record Root(@EntityId String rootId) {}
    @Model record Child(@EntityId String childId, @Parent(value = Root.class, deleteOnParentDeletion = true) String rootId) {}
    record CreateRoot(String rootId) { @Apply Root apply() { return new Root(rootId); } }
    record CreateChild(String childId, String rootId) { @Apply Child apply() { return new Child(childId, rootId); } }
    record DeleteRoot(String rootId, String childId) {
        @Apply Root apply(Root root) { return null; }
        @AssertLegal(afterHandler = true) void absent(@Nullable Child child) {
            assertNull(child); calls.add("child-absent");
        }
    }
    record RetainAndRepair(String counterId) {
        @InterceptApply List<?> split() { return List.of(new Retained(counterId), new PlainValue(counterId, 2)); }
    }
    record Retained(String counterId) {
        @InterceptApply(assertCurrent = AssertCurrent.ENABLED) Object retain() { return this; }
        @Apply Counter apply(Counter counter) { return new Counter(counterId, -1); }
        @AssertLegal(afterHandler = true) void after(Counter counter) {
            assertEquals(2, counter.value()); calls.add("retained:" + counter.value());
        }
    }
    record PlainValue(String counterId, int value) {
        @Apply Counter apply(Counter counter) { return new Counter(counterId, value); }
    }
}
