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

package io.fluxzero.sdk.givenwhenthen;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.RecursivePublicationException;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.scheduling.ScheduledCommand;
import io.fluxzero.sdk.scheduling.SchedulerException;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.sdk.publishing.RecursivePublicationGuard.PUBLICATION_DEPTH_METADATA_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GivenWhenThenScheduledCommandDepthTest {

    private static final String ID = "scheduled-command-depth";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commandHooksRunOncePerPreparationAndDelivery(boolean async) {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger serializations = new AtomicInteger();
        var serializer = new JacksonSerializer();
        DispatchInterceptor interceptor = new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic) {
                if (message.getPayload() instanceof Deferred value) {
                    dispatches.incrementAndGet();
                    return message.withPayload(new Deferred(value.value() + "-dispatch"))
                            .addMetadata("dispatch-hook", "retained");
                }
                return message;
            }

            @Override
            public SerializedMessage modifySerializedMessage(SerializedMessage wire, Message message,
                                                             MessageType type, String topic) {
                if (message.getPayload() instanceof Deferred value) {
                    serializations.incrementAndGet();
                    assertEquals("retained", wire.getMetadata().get("dispatch-hook"));
                    return wire.withData(serializer.serialize(new Deferred(value.value() + "-wire")))
                            .withMetadata(wire.getMetadata().with("wire-hook", "retained"));
                }
                return wire;
            }
        };
        List<Deferred> deliveries = new CopyOnWriteArrayList<>();
        Object receiver = new Object() {
            @HandleCommand
            void handle(Deferred value) {
                deliveries.add(value);
            }
        };
        var fixture = fixture(async, DefaultFluxzero.builder().setMaxPublicationDepth(2)
                .addDispatchInterceptor(interceptor, COMMAND), () -> schedule(new Deferred("original")), receiver);
        fixture.whenCommand(new Step(0)).expectNoErrors().expectThat(fc -> {
            var command = stored(fc.messageScheduler().getSchedule(ID).orElseThrow());
            assertEquals("0", command.getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY));
            assertEquals("retained", command.getMetadata().get("wire-hook"));
            assertEquals(1, dispatches.get());
            assertEquals(1, serializations.get());
            assertTrue(deliveries.isEmpty());
        });
        fixture.whenTimeElapses(Duration.ofSeconds(1)).expectNoErrors().expectThat(fc -> {
            assertEquals(List.of(new Deferred("original-dispatch-wire-dispatch" + (async ? "-wire" : ""))), deliveries);
            assertEquals(2, dispatches.get());
            assertEquals(async ? 2 : 1, serializations.get(), "Local delivery does not serialize again");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void namespaceAwareCommandHookIsPreservedAtTheBoundary(boolean async) {
        List<String> namespaces = new CopyOnWriteArrayList<>();
        DispatchInterceptor interceptor = new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic) {
                return message;
            }

            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic, String namespace) {
                if (message.getPayload() instanceof Deferred) {
                    namespaces.add(namespace);
                }
                return message;
            }
        };
        fixture(async, DefaultFluxzero.builder().setMaxPublicationDepth(2)
                .addDispatchInterceptor(interceptor, COMMAND), () -> Fluxzero.get().messageScheduler()
                .forNamespace("test-namespace").scheduleCommand(
                        new Schedule(new Deferred("tenant"), ID, Fluxzero.currentTime().plusSeconds(1))))
                .whenCommand(new Step(0)).expectNoErrors().expectThat(fc -> {
                    assertEquals(List.of("test-namespace"), namespaces);
                    assertTrue(fc.messageScheduler().getSchedule(ID).isEmpty());
                    assertEquals("0", stored(fc.messageScheduler().forNamespace("test-namespace")
                            .getSchedule(ID).orElseThrow()).getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY));
                });
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false",
                "false,false,true", "true,false,true", "false,true,true", "true,true,true"})
    void suppressionAndExceptionsStillPreventStorage(boolean async, boolean serializedPhase, boolean throwing) {
        AtomicInteger calls = new AtomicInteger();
        DispatchInterceptor interceptor = new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic) {
                if (!serializedPhase && message.getPayload() instanceof Deferred) {
                    calls.incrementAndGet();
                    if (throwing) {
                        throw new IllegalArgumentException("blocked by command interceptor");
                    }
                    return null;
                }
                return message;
            }

            @Override
            public SerializedMessage modifySerializedMessage(SerializedMessage wire, Message message,
                                                             MessageType type, String topic) {
                if (serializedPhase && message.getPayload() instanceof Deferred) {
                    calls.incrementAndGet();
                    if (throwing) {
                        throw new IllegalArgumentException("blocked by command interceptor");
                    }
                    return null;
                }
                return wire;
            }
        };
        Runnable create = () -> {
            if (throwing) {
                var failure = assertThrows(SchedulerException.class,
                        () -> schedule(new Deferred("blocked")));
                assertEquals("blocked by command interceptor",
                             assertInstanceOf(IllegalArgumentException.class, failure.getCause()).getMessage());
            } else {
                schedule(new Deferred("blocked"));
            }
        };
        fixture(async, DefaultFluxzero.builder().setMaxPublicationDepth(2)
                .addDispatchInterceptor(interceptor, COMMAND), create)
                .whenCommand(new Step(0)).expectNoErrors().expectThat(fc -> {
                    assertEquals(1, calls.get());
                    assertTrue(fc.messageScheduler().getSchedule(ID).isEmpty());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void immediateCommandFromPreparationInterceptorStillUsesTheOriginalBranch(boolean async) {
        AtomicInteger rejections = new AtomicInteger();
        DispatchInterceptor interceptor = (message, type, topic) -> {
            if (message.getPayload() instanceof Deferred) {
                assertThrows(RecursivePublicationException.class, () -> Fluxzero.sendCommandAndWait(new Step(3)));
                rejections.incrementAndGet();
            }
            return message;
        };
        fixture(async, DefaultFluxzero.builder().setMaxPublicationDepth(2)
                .addDispatchInterceptor(interceptor, COMMAND), () -> schedule(new Deferred("nested")))
                .whenCommand(new Step(0)).expectNoErrors().expectThat(fc -> {
                    assertEquals(1, rejections.get());
                    assertTrue(fc.messageScheduler().getSchedule(ID).isPresent());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualDeadlineDeliveryStillLimitsImmediateRecursion(boolean async) {
        List<Integer> depths = new CopyOnWriteArrayList<>();
        Object receiver = new Object() {
            @HandleCommand void handle(Recursive value, DeserializingMessage message) {
                depths.add(Integer.parseInt(message.getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY)));
                Fluxzero.get().commandGateway().sendAndForget(new Recursive());
            }
        };
        var fixture = fixture(async, DefaultFluxzero.builder().setMaxPublicationDepth(2),
                              () -> schedule(new Recursive()), receiver);
        fixture.whenCommand(new Step(0)).expectNoErrors();
        fixture.whenTimeElapses(Duration.ofSeconds(1)).expectError(RecursivePublicationException.class)
                .expectThat(fc -> assertEquals(List.of(1, 2), depths));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroLimitPermitsPreparationButStillRejectsNormalDelivery(boolean async) {
        AtomicInteger deliveries = new AtomicInteger();
        Object handler = new Object() {
            @HandleCommand
            void handle(Deferred ignored) {
                deliveries.incrementAndGet();
            }

            @HandleCommand
            void handle(Zero ignored) {
                schedule(new Deferred("zero"));
            }
        };
        var builder = DefaultFluxzero.builder().setMaxPublicationDepth(0);
        var fixture = async ? TestFixture.createAsync(builder, handler) : TestFixture.create(builder, handler);
        fixture.whenCommand(new Zero()).expectNoErrors().expectThat(fc -> assertEquals("0",
                stored(fc.messageScheduler().getSchedule(ID).orElseThrow())
                        .getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY)));
        fixture.whenTimeElapses(Duration.ofSeconds(1)).expectError(RecursivePublicationException.class)
                .expectThat(fc -> assertEquals(0, deliveries.get()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledGuardRetainsExplicitDepthMetadata(boolean async) {
        fixture(async, DefaultFluxzero.builder().setMaxPublicationDepth(-1), () -> Fluxzero.get().messageScheduler()
                .scheduleCommand(new Schedule(new Deferred("disabled"), ID, Fluxzero.currentTime().plusSeconds(1))
                        .addMetadata(PUBLICATION_DEPTH_METADATA_KEY, "77")))
                .whenCommand(new Step(0)).expectNoErrors().expectThat(fc -> assertEquals("77",
                        stored(fc.messageScheduler().getSchedule(ID).orElseThrow())
                                .getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY)));
    }

    private TestFixture fixture(boolean async, FluxzeroBuilder builder, Runnable atBoundary, Object... receivers) {
        Object first = new Object() {
            @HandleCommand
            void handle(Step step) {
                Fluxzero.sendCommandAndWait(new SecondStep());
            }
        };
        Object second = new Object() {
            @HandleCommand
            void handle(SecondStep step) {
                Fluxzero.sendCommandAndWait(new ThirdStep());
            }
        };
        Object third = new Object() {
            @HandleCommand
            void handle(ThirdStep step) {
                atBoundary.run();
            }
        };
        var fixture = async ? TestFixture.createAsync(builder, first, second, third)
                            : TestFixture.create(builder, first, second, third);
        return fixture.registerHandlers(receivers);
    }

    private static void schedule(Object payload) {
        Fluxzero.scheduleCommand(payload, ID, Fluxzero.currentTime().plusSeconds(1));
    }

    private static SerializedMessage stored(Schedule schedule) {
        return assertInstanceOf(ScheduledCommand.class, schedule.getPayload()).getCommand();
    }

    private record Step(int depth) {
    }

    private record SecondStep() {
    }

    private record ThirdStep() {
    }

    private record Deferred(String value) {
    }

    private record Recursive() {
    }

    private record Zero() {
    }
}
