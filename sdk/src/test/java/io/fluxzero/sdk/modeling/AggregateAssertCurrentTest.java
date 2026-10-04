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
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.AssertCurrent;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregateAssertCurrentTest {
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final IllegalCommandException REJECTED = new IllegalCommandException("Rejected");
    TestFixture fixture;
    TestFixture fixture(boolean async) { return fixture(async, Map.of()); }
    TestFixture fixture(boolean async, Map<String,String> properties) {
        calls.clear();
        var builder = DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(properties));
        fixture = async ? TestFixture.createAsync(builder, new Object() { @HandleCommand void handle(Object command) { run(command); } }) : TestFixture.create(builder, new Object() { @HandleCommand void handle(Object command) { run(command); } });
        return fixture.givenCommands(new Seed("state"));
    }
    @AfterEach void close() { if (fixture != null) fixture.getFluxzero().close(); }
    static Object run(Object command) { return Fluxzero.loadAggregate("state", State.class).assertAndApply(command); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void replacementPreservesCurrentAndFinalState(boolean async) {
        fixture(async).whenCommand(new Split("state", 2)).expectSuccessfulResult();
        assertEquals(List.of("current:0", "intercept", "update:0", "update:1", "after:2"), calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void suppressionRetainsBothPhases(boolean async) {
        fixture(async).whenCommand(new Split("state", 0)).expectSuccessfulResult();
        assertEquals(List.of("current:0", "intercept", "after:0"), calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void failurePreventsInterception(boolean async) {
        fixture(async).whenCommand(new Denied("state")).expectExceptionalResult(REJECTED);
        assertEquals(List.of("rejected"), calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void retainedInputIsCheckedOnce(boolean async) {
        fixture(async).whenCommand(new Retained("state", false)).expectSuccessfulResult();
        assertEquals(List.of("retained:false:0", "retain", "retained-after:false:1"), calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sameTypeReplacementHasItsOwnAssertions(boolean async) {
        fixture(async).whenCommand(new Retained("state", true)).expectSuccessfulResult();
        assertEquals(List.of("retained:true:0", "retain", "retained:false:0", "retained-after:true:1", "retained-after:false:0"), calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void replacementChainPreservesEveryEnabledInput(boolean async) {
        fixture(async).whenCommand(new Chain("state")).expectSuccessfulResult();
        assertEquals(List.of("chain", "current:0", "intercept", "update:0", "after:1"), calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void defaultsAreApplicationScoped(boolean async) {
        fixture(async).whenCommand(new Defaulted("state")).expectSuccessfulResult();
        assertEquals(List.of("update:0"), calls);
        fixture.getFluxzero().close();
        fixture(async,Map.of("fluxzero.defaults.version","2026.10.04"))
                .whenCommand(new Defaulted("state")).expectSuccessfulResult();
        assertEquals(List.of("default", "update:0"),calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void explicitDisableOverridesNewDefault(boolean async) {
        fixture(async,Map.of("fluxzero.defaults.version","2026.10.04"))
                .whenCommand(new Disabled("state")).expectSuccessfulResult();
        assertEquals(List.of("update:0"),calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void explicitEnableOverridesApplicationFalse(boolean async) {
        fixture(async,Map.of("fluxzero.interceptApply.assertCurrent","false"))
                .whenCommand(new Denied("state")).expectExceptionalResult(REJECTED);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void changedMessageMetadataRechecksCurrent(boolean async) {
        fixture(async).whenCommand(new MetadataChange("state")).expectSuccessfulResult();
        assertEquals(List.of("metadata:null", "metadata:changed"),calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void retainedInputAfterEarlierSplitMutationIsRechecked(boolean async) {
        fixture(async).whenCommand(new RepeatAfterUpdate("state")).expectExceptionalResult(REJECTED);
        assertEquals(List.of("repeat:0","update:0","repeat:1"),calls);
    }
    record RepeatAfterUpdate(String stateId) {
        @AssertLegal void check(State state) {calls.add("repeat:"+state.count());if(state.count()>0)throw REJECTED;}
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(State state) {return List.of(new Update(stateId),this);}
        @Apply State apply(State state) {return new State(stateId,state.count()+1);}
    }

    @Aggregate record State(@EntityId String stateId, int count) {}
    record Seed(String stateId) { @Apply State apply() { return new State(stateId,0); } }
    record Update(String stateId) {
        @AssertLegal void check(State state) { calls.add("update:"+state.count()); }
        @Apply State apply(State state) { return new State(stateId,state.count()+1); }
    }
    record Split(String stateId,int count) {
        @AssertLegal void check(State state) { calls.add("current:"+state.count()); }
        @AssertLegal(afterHandler=true) void after(State state) { calls.add("after:"+state.count()); }
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(State state) {
            calls.add("intercept");
            return count==0 ? null : count==1 ? new Update(stateId) : List.of(new Update(stateId), new Update(stateId));
        }
    }
    record Denied(String stateId) {
        @AssertLegal void check(State state) { calls.add("rejected");throw REJECTED; }
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(State state) {calls.add("unexpected");return null;}
    }
    record Retained(String stateId,boolean replace) {
        @AssertLegal void check(State state) { calls.add("retained:"+replace+":"+state.count()); }
        @AssertLegal(afterHandler=true) void after(State state) {calls.add("retained-after:"+replace+":"+state.count());}
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(State state) {calls.add("retain");return replace?new Retained(stateId,false):this;}
        @Apply State apply(State state) {return new State(stateId,state.count()+1);}
    }
    record Chain(String stateId) {
        @AssertLegal void check(State state) {calls.add("chain");}
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Split intercept(State state) {return new Split(stateId,1);}
    }
    record Defaulted(String stateId) {
        @AssertLegal void check(State state) {calls.add("default");}
        @InterceptApply Update intercept(State state) {return new Update(stateId);}
    }
    record Disabled(String stateId) {
        @AssertLegal void check(State state) {throw REJECTED;}
        @InterceptApply(assertCurrent=AssertCurrent.DISABLED) Update intercept(State state) {return new Update(stateId);}
    }
    record MetadataChange(String stateId) {
        @AssertLegal void check(State state, Metadata metadata) {calls.add("metadata:"+metadata.get("test"));}
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Message intercept(State state) {return new Message(this).addMetadata("test","changed");}
        @Apply State apply(State state) {return state;}
    }
}
