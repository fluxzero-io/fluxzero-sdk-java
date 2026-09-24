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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.scheduling.ScheduleAutoCancelled;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ParentOwnedScheduleTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant DEADLINE = NOW.plusSeconds(3600);
    final RootId rootId = new RootId("root");
    final ChildId childId = new ChildId("child");

    TestFixture fixture(boolean async) {
        return (async ? TestFixture.createAsync() : TestFixture.create()).atFixedTime(NOW)
                .givenCommands(new CreateRoot(rootId));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void returningNullFromApplyCancelsOwnedSchedule(boolean async) {
        var schedule = new Schedule(new RunRoot(rootId), "owned", DEADLINE);
        fixture(async).given(fc -> fc.messageScheduler().scheduleCommand(schedule))
                .whenCommand(new DeleteRoot(rootId))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands()
                .expectMetrics(ScheduleAutoCancelled.class)
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule("owned").isEmpty()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cascadeCancelsScheduleOwnedByDeletedDescendant(boolean async) {
        fixture(async).givenCommands(new CreateChild(childId, rootId))
                .given(fc -> fc.messageScheduler().scheduleCommand(new Schedule(new RunChild(childId), "owned", DEADLINE)))
                .whenCommand(new DeleteRoot(rootId))
                .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands()
                .expectMetrics(ScheduleAutoCancelled.class);
    }

    @Test
    void directScheduleIsCancelledWithoutAnyCleanupHandler() {
        AtomicInteger calls = new AtomicInteger();
        Object listener = new Object() {
            @HandleSchedule void handle(RunRoot ignored) { calls.incrementAndGet(); }
        };
        TestFixture.create(listener).atFixedTime(NOW).givenCommands(new CreateRoot(rootId))
                .given(fc -> fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "owned", DEADLINE)))
                .givenCommands(new DeleteRoot(rootId)).whenTimeAdvancesTo(DEADLINE)
                .expectNoErrors().expectThat(fc -> assertEquals(0, calls.get()));
    }

    @Test
    void survivingScheduledCommandCanApplyNormally() {
        fixture(false).given(fc -> fc.messageScheduler().scheduleCommand(
                new Schedule(new RunRoot(rootId), "owned", DEADLINE)))
                .whenTimeAdvancesTo(DEADLINE).expectNoErrors().expectOnlyActiveScheduledCommands();
    }

    @Test
    void ordinaryReplacementIsNotCancelledByOldOwnership() {
        fixture(false).given(fc -> fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "same", DEADLINE)))
                .given(fc -> fc.messageScheduler().schedule(new Schedule("ordinary", "same", DEADLINE)))
                .whenCommand(new DeleteRoot(rootId)).expectNoErrors()
                .expectNoMetricsLike(ScheduleAutoCancelled.class)
                .expectThat(fc -> assertEquals("ordinary", fc.messageScheduler().getSchedule("same").orElseThrow().getPayload()));
    }

    @Test
    void explicitSelectionOverridesAnnotationsAndEmptySelectionOptsOut() {
        RootId other = new RootId("other");
        fixture(false).givenCommands(new CreateRoot(other))
                .given(fc -> {
                    fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "other-owner", DEADLINE).withParents(other));
                    fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "unowned", DEADLINE).withParents());
                })
                .whenCommand(new DeleteRoot(rootId)).expectNoErrors().expectNoMetricsLike(ScheduleAutoCancelled.class)
                .expectThat(fc -> {
                    assertTrue(fc.messageScheduler().getSchedule("other-owner").isPresent());
                    assertTrue(fc.messageScheduler().getSchedule("unowned").isPresent());
                }).andThen().whenCommand(new DeleteRoot(other)).expectNoErrors()
                .expectThat(fc -> {
                    assertTrue(fc.messageScheduler().getSchedule("other-owner").isEmpty());
                    assertTrue(fc.messageScheduler().getSchedule("unowned").isPresent());
                });
    }

    @Test
    void missingParentIsRejectedAndNullOrNonOwningReferencesAreIgnored() {
        TestFixture.create().atFixedTime(NOW).whenExecuting(fc -> {
            assertThrows(Exception.class, () -> fc.messageScheduler().scheduleCommand(
                    new Schedule(new RunRoot(rootId), "missing", DEADLINE)));
            fc.messageScheduler().schedule(new Schedule(new RunRoot(null), "null", DEADLINE));
            fc.messageScheduler().schedule(new Schedule(new NonOwning(rootId), "non-owning", DEADLINE));
        }).expectNoErrors().expectThat(fc -> {
            assertTrue(fc.messageScheduler().getSchedule("missing").isEmpty());
            assertTrue(fc.messageScheduler().getSchedule("null").isPresent());
            assertTrue(fc.messageScheduler().getSchedule("non-owning").isPresent());
        });
    }

    @Test
    void updateDoesNotCancelAndIfAbsentDoesNotTransferOwnership() {
        RootId other = new RootId("other");
        fixture(false).givenCommands(new CreateRoot(other))
                .given(fc -> {
                    fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "same", DEADLINE));
                    fc.messageScheduler().schedule(new Schedule(new RunRoot(other), "same", DEADLINE), true, Guarantee.STORED).join();
                })
                .givenCommands(new TouchRoot(rootId))
                .whenCommand(new DeleteRoot(other)).expectNoErrors()
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule("same").isPresent()))
                .andThen().whenCommand(new DeleteRoot(rootId)).expectNoErrors()
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule("same").isEmpty()));
    }

    @Test
    void staleBindingCannotReplaceNewLifetimeSchedule() {
        fixture(false).whenExecuting(fc -> {
            var client = fc.client().getSchedulingClient();
            var oldBinding = client.bindScheduleParents(List.of(rootId.toString())).join();
            Fluxzero.sendCommandAndWait(new DeleteRoot(rootId));
            Fluxzero.sendCommandAndWait(new CreateRoot(rootId));
            fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "same", DEADLINE));
            var current = client.getSchedule("same");
            assertThrows(Exception.class, () -> client.scheduleBoundToParents(Guarantee.STORED, oldBinding, current).join());
            assertEquals(current.getMessage().getMessageId(), client.getSchedule("same").getMessage().getMessageId());
        }).expectNoErrors();
    }

    @ParameterizedTest @EnumSource(Continuation.class)
    void automaticContinuationCannotRebindAfterDeleteAndRecreate(Continuation continuation) {
        Object listener = new Object() {
            @HandleSchedule @Periodic(autoStart = false, delay = 60000)
            Object handle(RunRoot tick) {
                Fluxzero.sendCommandAndWait(new DeleteRoot(tick.rootId()));
                Fluxzero.sendCommandAndWait(new CreateRoot(tick.rootId()));
                return switch (continuation) {
                    case NULL -> null;
                    case DURATION -> Duration.ofMinutes(1);
                    case MESSAGE -> new Message(tick);
                    case SCHEDULE -> new Schedule(tick, "owned", DEADLINE.plusSeconds(60));
                };
            }
        };
        TestFixture.create(listener).atFixedTime(NOW).givenCommands(new CreateRoot(rootId))
                .given(fc -> fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "owned", DEADLINE)))
                .whenTimeAdvancesTo(DEADLINE).expectNoErrors()
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule("owned").isEmpty()));
    }

    @Test
    void explicitParentsAllowDeliberateNewLifetime() {
        fixture(false).whenExecuting(fc -> {
            fc.messageScheduler().schedule(new Schedule(new RunRoot(rootId), "owned", DEADLINE));
            var old = fc.messageScheduler().getSchedule("owned").orElseThrow();
            Fluxzero.sendCommandAndWait(new DeleteRoot(rootId));
            Fluxzero.sendCommandAndWait(new CreateRoot(rootId));
            assertThrows(Exception.class, () -> fc.messageScheduler().schedule(old));
            fc.messageScheduler().schedule(old.withParents(rootId));
            assertTrue(fc.messageScheduler().getSchedule("owned").isPresent());
        }).expectNoErrors();
    }

    @Test
    void retrievedScheduledCommandRetainsOwnershipAndOriginalLifetime() {
        fixture(false).whenExecuting(fc -> {
            fc.messageScheduler().scheduleCommand(new Schedule(new RunRoot(rootId), "owned", DEADLINE));
            var old = fc.messageScheduler().getSchedule("owned").orElseThrow();
            fc.messageScheduler().schedule(old);
            Fluxzero.sendCommandAndWait(new DeleteRoot(rootId));
            assertTrue(fc.messageScheduler().getSchedule("owned").isEmpty());
            Fluxzero.sendCommandAndWait(new CreateRoot(rootId));
            assertThrows(Exception.class, () -> fc.messageScheduler().schedule(old));
            assertTrue(fc.messageScheduler().getSchedule("owned").isEmpty());
        }).expectNoErrors();
    }

    @Test
    void inferredOwnershipDoesNotDuplicateParentIdsInMetadata() {
        fixture(false).whenExecuting(fc -> {
            fc.messageScheduler().scheduleCommand(new Schedule(new RunRoot(rootId), "owned", DEADLINE));
            var metadata = fc.client().getSchedulingClient().getSchedule("owned").getMessage().getMetadata();
            assertFalse(metadata.containsKey(ScheduleParents.METADATA_KEY));
            assertTrue(metadata.containsKey(ScheduleParents.BINDINGS_KEY));
            assertFalse(metadata.get(ScheduleParents.BINDINGS_KEY).contains(rootId.toString()));
        }).expectNoErrors();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void protectedParentIsStillOwnedWithoutLeakingIntoTheStoredSchedule(boolean command) {
        fixture(false).given(fc -> {
            var schedule = new Schedule(new PrivateTick(rootId.toString()), "private", DEADLINE);
            if (command) {
                fc.messageScheduler().scheduleCommand(schedule);
            } else {
                fc.messageScheduler().schedule(schedule);
            }
            var stored = fc.client().getSchedulingClient().getSchedule("private");
            assertFalse(new String(stored.getMessage().getData().getValue(), java.nio.charset.StandardCharsets.UTF_8)
                                .contains("\"root\""));
            assertFalse(stored.getMessage().getMetadata().containsKey(ScheduleParents.METADATA_KEY));
        }).whenCommand(new DeleteRoot(rootId)).expectNoErrors()
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule("private").isEmpty()));
    }

    enum Continuation { NULL, DURATION, MESSAGE, SCHEDULE }

    @Test
    void missingProtectedParentFailsBeforePrimitiveConversion() {
        var store = org.mockito.Mockito.mock(io.fluxzero.sdk.persisting.keyvalue.KeyValueStore.class);
        org.mockito.Mockito.when(store.forNamespace(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(store);
        var serializer = new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer();
        var restoration = new io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor(store, serializer);
        var message = new Message(new PrivateNumericTick(0), io.fluxzero.common.api.Metadata.of(
                io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.METADATA_KEY,
                java.util.Map.of("rootId", "erased-reference")));
        assertThrows(IllegalStateException.class, () -> ScheduleParents.resolve(message,
                io.fluxzero.common.MessageType.SCHEDULE, serializer, restoration::restoreScheduleParents));
    }

    @Test
    void unrelatedProtectedValuesAreNotRestoredForOwnership() {
        var serializer = new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer();
        var message = new Message(new RunRoot(rootId), io.fluxzero.common.api.Metadata.of(
                io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.METADATA_KEY,
                java.util.Map.of("unrelated", "unavailable-reference")));
        assertEquals(List.of(rootId.toString()), ScheduleParents.resolve(message,
                io.fluxzero.common.MessageType.SCHEDULE, serializer, ignored -> {
                    throw new AssertionError("Unrelated vault reads are not part of ownership");
                }));
    }

    @ParameterizedTest @ValueSource(strings = {"copy", "payload", "serialized", "command"})
    void scheduleInterceptorsCanCopyOrReplaceInnerCommandWithoutLosingOwnership(String mode) {
        RootId other = new RootId("other");
        var serializer = new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer();
        var interceptor = new io.fluxzero.sdk.publishing.DispatchInterceptor() {
            public Message interceptDispatch(Message message, io.fluxzero.common.MessageType type, String topic) {
                if (message.getPayload() instanceof ScheduledCommand current && !mode.equals("serialized")) {
                    return message.withPayload(new ScheduledCommand(mode.equals("copy") ? current.getCommand()
                            : new Message(new RunRoot(other)).serialize(serializer)));
                }
                return message;
            }
            public io.fluxzero.common.api.SerializedMessage modifySerializedMessage(
                    io.fluxzero.common.api.SerializedMessage serialized, Message message,
                    io.fluxzero.common.MessageType type, String topic) {
                return mode.equals("command") && message.getPayload() instanceof RunRoot
                        ? serialized.withData(serializer.serialize(new RunRoot(other)))
                        : mode.equals("serialized") ? serialized.withData(serializer.serialize(
                                new ScheduledCommand(new Message(new RunRoot(other)).serialize(serializer)))) : serialized;
            }
        };
        TestFixture.create(io.fluxzero.sdk.configuration.DefaultFluxzero.builder()
                .addDispatchInterceptor(interceptor, mode.equals("command") ? io.fluxzero.common.MessageType.COMMAND
                                                                          : io.fluxzero.common.MessageType.SCHEDULE))
                .atFixedTime(NOW).givenCommands(new CreateRoot(rootId), new CreateRoot(other))
                .given(fc -> fc.messageScheduler().scheduleCommand(new Schedule(new RunRoot(rootId), "owned", DEADLINE)))
                .whenCommand(new DeleteRoot(mode.equals("copy") ? rootId : other)).expectNoErrors()
                .expectThat(fc -> assertTrue(fc.messageScheduler().getSchedule("owned").isEmpty()));
    }

    @Model record Root(@EntityId RootId rootId) {}
    @Model record Child(@EntityId ChildId childId, @Parent RootId rootId) {}
    record CreateRoot(RootId rootId) { @Apply Root apply() { return new Root(rootId); } }
    record TouchRoot(RootId rootId) { @Apply Root apply(Root root) { return new Root(rootId); } }
    record DeleteRoot(RootId rootId) { @Apply Root apply(Root root) { return null; } }
    record CreateChild(ChildId childId, RootId rootId) { @Apply Child apply() { return new Child(childId, rootId); } }
    record RunRoot(@Parent RootId rootId) { @Apply Root apply(Root root) { return root; } }
    record RunChild(@Parent ChildId childId) {}
    record NonOwning(@Parent(deleteOnParentDeletion = false) RootId rootId) {}
    record PrivateTick(@Parent(Root.class) @io.fluxzero.sdk.publishing.dataprotection.ProtectData String rootId) {}
    record PrivateNumericTick(@Parent(Root.class) @io.fluxzero.sdk.publishing.dataprotection.ProtectData long rootId) {}
    static class RootId extends Id<Root> { RootId(String value) { super(value); } }
    static class ChildId extends Id<Child> { ChildId(String value) { super(value); } }
}
