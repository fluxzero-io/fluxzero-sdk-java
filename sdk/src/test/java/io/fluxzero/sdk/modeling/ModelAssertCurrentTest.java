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

class ModelAssertCurrentTest {
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final IllegalCommandException REJECTED = new IllegalCommandException("Rejected");
    TestFixture fixture;
    TestFixture fixture(boolean async) { return fixture(async, Map.of()); }
    TestFixture fixture(boolean async, Map<String,String> properties) {
        calls.clear();
        var builder = DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(properties));
        fixture = async ? TestFixture.createAsync(builder) : TestFixture.create(builder);
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
        assertEquals(List.of("retained:true:0", "retain", "retained:false:0", "retained-after:false:1", "retained-after:true:1"), calls);
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
    void retainedCurrentStillChecksApplyCompatibility(boolean async) {
        fixture(async).whenCommand(new Duplicate("state")).expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION);
    }
    record Duplicate(String stateId) {
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(State state) {return this;}
        @Apply State apply() {return new State(stateId,10);}
    }

    static final java.util.concurrent.atomic.AtomicReference<Runnable> race=new java.util.concurrent.atomic.AtomicReference<>();
    @ParameterizedTest @ValueSource(booleans={false,true})
    void currentReadsCannotBeDiscardedByAccept(boolean async) {
        fixture(async).givenCommands(new SeedGate("gate"));
        race.set(()->java.util.concurrent.CompletableFuture.runAsync(()->fixture.getFluxzero().apply(fc->{
            Fluxzero.assertAndApply(new CloseGate("gate"));return null;
        })).join());
        fixture.whenExecuting(fc->Fluxzero.assertAndApply(new Guarded("state","gate",false)))
                .expectExceptionalResult(ModelCommitConflictException.class);
        assertEquals(0,fixture.getFluxzero().modelRepository().load("state",State.class).get().count());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void retryRechecksCurrentInput(boolean async) {
        fixture(async).givenCommands(new SeedGate("gate"));
        race.set(()->java.util.concurrent.CompletableFuture.runAsync(()->fixture.getFluxzero().apply(fc->{
            Fluxzero.assertAndApply(new CloseGate("gate"));return null;
        })).join());
        fixture.whenExecuting(fc->Fluxzero.assertAndApply(new Guarded("state","gate",true)))
                .expectExceptionalResult(REJECTED);
        assertEquals(0,fixture.getFluxzero().modelRepository().load("state",State.class).get().count());
    }
    @Model(searchable=false,cached=false,conflictPolicy=io.fluxzero.common.api.modeling.ModelConflictPolicy.ACCEPT) record Gate(@EntityId String gateId, boolean closed) {}
    record SeedGate(String gateId) { @Apply Gate apply(){return new Gate(gateId,false);} }
    record CloseGate(String gateId) { @Apply Gate apply(Gate gate){return new Gate(gateId,true);} }
    record Guarded(String stateId,String gateId,boolean retry) {
        @AssertLegal void check(Gate gate) {if(gate.closed())throw REJECTED;}
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(State state) {
            Runnable action=race.getAndSet(null);if(action!=null)action.run();
            return retry?new Update(stateId):new AcceptUpdate(stateId);
        }
    }
    record AcceptUpdate(String stateId) {
        @Apply(conflictPolicy=io.fluxzero.common.api.modeling.ModelConflictPolicy.ACCEPT)
        State apply(State state) {return new State(stateId,state.count()+1);}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void directGraphOutputRetainsCurrentFinalChecks(boolean async) {
        fixture(async).whenCommand(new GraphChange("state")).expectSuccessfulResult();
        assertEquals(List.of("graph-before:0","graph-after:1"),calls);
    }
    record GraphChange(String stateId) {
        @AssertLegal void before(State state) {calls.add("graph-before:"+state.count());}
        @AssertLegal(afterHandler=true) void after(State state) {calls.add("graph-after:"+state.count());}
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(Graph<State> state) {
            return state.update(value->new State(stateId,value.count()+1));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void currentFinalCheckSeesAutomaticChildDeletion(boolean async) {
        fixture(async).givenCommands(new SeedRoot("root"),new SeedChild("child","root"))
                .whenCommand(new RemoveRoot("root","child")).expectSuccessfulResult();
        assertEquals(List.of("child-deleted"),calls);
    }
    @Model record Root(@EntityId String rootId) {}
    @Model record Child(@EntityId String childId,@Parent(value=Root.class,deleteOnParentDeletion=true) String rootId) {}
    record SeedRoot(String rootId){@Apply Root apply(){return new Root(rootId);}}
    record SeedChild(String childId,String rootId){@Apply Child apply(){return new Child(childId,rootId);}}
    record RemoveRoot(String rootId,String childId) {
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(Root root){return new DeleteRoot(rootId);}
        @AssertLegal(afterHandler=true) void after(@jakarta.annotation.Nullable Child child) {
            if(child!=null)throw REJECTED;calls.add("child-deleted");
        }
    }
    record DeleteRoot(String rootId){@Apply Root apply(Root root){return null;}}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void memberSelectedInterceptorRetainsItsAnnotation(boolean async) {
        fixture(async).givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new Rewrite("part")))
                .expectExceptionalResult(REJECTED);
        assertEquals(List.of("member-current"),calls);
    }
    @Model record Owner(@EntityId String ownerId,@Member List<Part> parts) {}
    record Part(@EntityId String partId) {
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(Rewrite input) {
            calls.add("unexpected-member-interceptor");return null;
        }
        @AssertLegal void check(Rewrite input) {calls.add("member-current");throw REJECTED;}
    }
    record CreateOwner(String ownerId) { @Apply Owner apply(){return new Owner(ownerId,List.of(new Part("part")));} }
    record Rewrite(String partId) {}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void newEnvelopeRunsOwnAssertions(boolean async) {
        fixture(async).givenCommands(new SeedOther("other"))
                .whenExecuting(fc->Fluxzero.loadGraph("state",State.class).assertAndApply(new Multi("state","other")))
                .expectSuccessfulResult();
        assertEquals(List.of("multi:0","wrapper","multi:2"),calls);
    }
    @Model record Other(@EntityId String otherId) {
        @AssertLegal void check(Multi input) {throw REJECTED;}
    }
    record SeedOther(String otherId){@Apply Other apply(){return new Other(otherId);}}
    record Multi(String stateId,String otherId) {
        @AssertLegal void check(State state) {calls.add("multi:"+calls.size());}
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Message intercept() {calls.add("wrapper");return new Message(this);}
        @Apply State change(State state){return state;}
        @Apply Other change(Other other){return other;}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void deferredRejectionRollsBackAllOutputs(boolean async) {
        fixture(async).whenCommand(new RejectedAfter("state")).expectExceptionalResult(REJECTED);
        assertEquals(0,fixture.getFluxzero().modelRepository().load("state",State.class).get().count());
    }
    record RejectedAfter(String stateId) {
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(State state) {
            return List.of(new Update(stateId),new Update(stateId));
        }
        @AssertLegal(afterHandler=true) void after(State state) {
            assertEquals(2,state.count());throw REJECTED;
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void assertionOnlyDoesNotRunDeferredChecks(boolean async) {
        fixture(async).whenExecuting(fc->Fluxzero.assertLegal(new RejectedAfter("state"))).expectSuccessfulResult();
        assertEquals(0,fixture.getFluxzero().modelRepository().load("state",State.class).get().count());
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

    @Model record State(@EntityId String stateId, int count) {}
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
