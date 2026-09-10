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

import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InterceptApplyAssertLegalContractTest {
    private TestFixture testFixture;

    @BeforeEach
    void setUp() {
        testFixture = TestFixture.create()
                .given(fc -> loadAggregate("state", State.class).update(ignored -> new State("state", 0)));
        testFixture.registerHandlers(new Object() {
            @HandleCommand
            void handle(Object command) {
                loadAggregate("state", State.class).assertAndApply(command);
            }
        });
    }

    @Test
    void retainedPayloadRunsItsAssertionBeforeApply() {
        List<String> invocations = new ArrayList<>();

        testFixture.whenCommand(new RetainedUpdate(invocations))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    assertEquals(List.of("intercept", "assert:0", "apply:0"), invocations);
                    assertEquals(1, state().value());
                });
    }

    @Test
    void suppressedPayloadRunsNeitherItsAssertionNorApply() {
        List<String> invocations = new ArrayList<>();

        testFixture.whenCommand(new SuppressedUpdate(invocations))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    assertEquals(List.of("intercept"), invocations);
                    assertEquals(0, state().value());
                });
    }

    @Test
    void replacementRunsOnlyTheReplacementAssertionAndApply() {
        List<String> invocations = new ArrayList<>();

        testFixture.whenCommand(new ReplacedUpdate(invocations))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    assertEquals(List.of("intercept-original", "assert-replacement:0", "apply-replacement:0"),
                                 invocations);
                    assertEquals(1, state().value());
                });
    }

    @Test
    void splitPayloadsAssertAndApplySequentiallyAgainstUpdatedState() {
        List<String> invocations = new ArrayList<>();

        testFixture.whenCommand(new SplitUpdate(invocations))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    assertEquals(List.of("intercept-original", "assert-first:0", "apply-first:0",
                                         "assert-second:1", "apply-second:1"), invocations);
                    assertEquals(2, state().value());
                });
    }

    private State state() {
        return loadAggregate("state", State.class).get();
    }

    private record State(@EntityId String id, int value) {
        State increment() {
            return new State(id, value + 1);
        }
    }

    private record RetainedUpdate(List<String> invocations) {
        @InterceptApply
        Object intercept() {
            invocations.add("intercept");
            return this;
        }

        @AssertLegal
        void assertLegal(State state) {
            invocations.add("assert:" + state.value());
        }

        @Apply
        State apply(State state) {
            invocations.add("apply:" + state.value());
            return state.increment();
        }
    }

    private record SuppressedUpdate(List<String> invocations) {
        @InterceptApply
        Object intercept() {
            invocations.add("intercept");
            return null;
        }

        @AssertLegal
        void assertLegal() {
            invocations.add("assert-original");
        }

        @Apply
        State apply(State state) {
            invocations.add("apply-original");
            return state.increment();
        }
    }

    private record ReplacedUpdate(List<String> invocations) {
        @InterceptApply
        Object intercept() {
            invocations.add("intercept-original");
            return new ReplacementUpdate(invocations);
        }

        @AssertLegal
        void assertLegal() {
            invocations.add("assert-original");
        }

        @Apply
        State apply(State state) {
            invocations.add("apply-original");
            return state.increment();
        }
    }

    private record ReplacementUpdate(List<String> invocations) {
        @AssertLegal
        void assertLegal(State state) {
            invocations.add("assert-replacement:" + state.value());
        }

        @Apply
        State apply(State state) {
            invocations.add("apply-replacement:" + state.value());
            return state.increment();
        }
    }

    private record SplitUpdate(List<String> invocations) {
        @InterceptApply
        List<?> intercept() {
            invocations.add("intercept-original");
            return List.of(new FirstPart(invocations), new SecondPart(invocations));
        }

        @AssertLegal
        void assertLegal() {
            invocations.add("assert-original");
        }
    }

    private record FirstPart(List<String> invocations) {
        @AssertLegal
        void assertLegal(State state) {
            invocations.add("assert-first:" + state.value());
        }

        @Apply
        State apply(State state) {
            invocations.add("apply-first:" + state.value());
            return state.increment();
        }
    }

    private record SecondPart(List<String> invocations) {
        @AssertLegal
        void assertLegal(State state) {
            invocations.add("assert-second:" + state.value());
        }

        @Apply
        State apply(State state) {
            invocations.add("apply-second:" + state.value());
            return state.increment();
        }
    }
}
