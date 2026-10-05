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

package io.fluxzero.sdk.test;

import io.fluxzero.common.TestTask;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.EVENT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFixtureAutomaticRegistrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void concurrentFirstDispatchWaitsForHandlerRegistration(boolean modelCommand, boolean explicitRegistration)
            throws Exception {
        Object firstCommand = modelCommand ? new CreateModel("first") : new NewCommand("first");
        Object secondCommand = modelCommand ? new CreateModel("second") : new NewCommand("second");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockOnce = new AtomicBoolean(true);
        var fixtureRef = new AtomicReference<TestFixture>();
        var builder = DefaultFluxzero.builder().addHandlerDecorator(handler -> {
            if (handler.getTargetClass() == firstCommand.getClass() && blockOnce.getAndSet(false)) {
                // Initialization can re-enter discovery on the same thread without registering the type twice.
                fixtureRef.get().registerAutomaticHandler(new Message(firstCommand));
                entered.countDown();
                try {
                    assertTrue(release.await(5, SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return handler;
        }, COMMAND);
        var fixture = TestFixture.createAsync(builder, Warmup.class);
        fixtureRef.set(fixture);
        fixture.givenCommands(new Warmup());
        var firstResult = new AtomicReference<CompletableFuture<Object>>();
        var secondResult = new AtomicReference<CompletableFuture<Object>>();
        try (var first = new TestTask(() -> {
            if (explicitRegistration) {
                fixture.registerHandlers(firstCommand.getClass());
            }
            firstResult.set(fixture.getFluxzero().apply(ignored -> Fluxzero.sendCommand(firstCommand)));
        }, release::countDown)) {
            assertTrue(entered.await(5, SECONDS));
            // An unrelated cold registration must not block already active automatic handlers.
            assertEquals("ready", fixture.getFluxzero().apply(
                    ignored -> Fluxzero.sendCommand(new Warmup())).get(5, SECONDS));
            try (var second = new TestTask(() -> secondResult.set(fixture.getFluxzero().apply(
                    ignored -> Fluxzero.sendCommand(secondCommand))), release::countDown)) {
                second.awaitBlockedIn(TestFixture.class, "registerAutomaticHandler", TIMEOUT);
                release.countDown();
                first.awaitCompletion(TIMEOUT);
                second.awaitCompletion(TIMEOUT);
            }
        }
        assertEquals(modelCommand ? null : "first", firstResult.get().get(5, SECONDS));
        assertEquals(modelCommand ? null : "second", secondResult.get().get(5, SECONDS));
        if (modelCommand) {
            fixture.getFluxzero().execute(ignored -> {
                assertEquals(new Item("first"), Fluxzero.loadModel("first", Item.class).get());
                assertEquals(new Item("second"), Fluxzero.loadModel("second", Item.class).get());
            });
        }
    }

    @Test
    void failedRegistrationIsRetainedWithoutInstallingDuplicateHandlers() {
        var commandRegistrations = new AtomicInteger();
        var failure = new IllegalStateException("Event registration failed");
        var builder = DefaultFluxzero.builder().addHandlerDecorator(handler -> {
            if (handler.getTargetClass() == PartiallyRegisteredCommand.class) {
                commandRegistrations.incrementAndGet();
            }
            return handler;
        }, COMMAND).addHandlerDecorator(handler -> {
            if (handler.getTargetClass() == PartiallyRegisteredCommand.class) {
                throw failure;
            }
            return handler;
        }, EVENT);
        var fixture = TestFixture.createAsync(builder, Warmup.class);
        var message = new Message(new PartiallyRegisteredCommand());
        fixture.getFluxzero().execute(ignored -> {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> fixture.registerAutomaticHandler(message)));
            assertTrue(commandRegistrations.get() > 0);
            int installed = commandRegistrations.get();
            assertSame(failure, assertThrows(CompletionException.class,
                    () -> fixture.registerAutomaticHandler(message)).getCause());
            assertEquals(installed, commandRegistrations.get());
        });
    }

    @TrackSelf
    @Consumer(name = "automatic-registration")
    record PartiallyRegisteredCommand() {
        @HandleCommand
        String handle() {
            return "handled";
        }

        @HandleEvent
        static void handleEvent(Integer ignored) {}
    }

    @Model
    record Item(@EntityId String id) {}

    @Consumer(name = "automatic-registration")
    record CreateModel(String id) {
        @Apply
        Item apply() {
            return new Item(id);
        }
    }

    @TrackSelf
    @Consumer(name = "automatic-registration")
    record Warmup() {
        @HandleCommand
        String handle() {
            return "ready";
        }
    }

    @TrackSelf
    @Consumer(name = "automatic-registration")
    record NewCommand(String value) {
        @HandleCommand
        String handle() {
            return value;
        }
    }
}
