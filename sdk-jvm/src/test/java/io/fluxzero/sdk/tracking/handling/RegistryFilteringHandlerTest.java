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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistryFilteringHandlerTest {

    @Test
    void skipsDelegateForNonMatchingPayloadRoute() {
        CountingHandler delegate = new CountingHandler(RegistryCommandHandler.class);
        Handler<DeserializingMessage> handler = RegistryFilteringHandler.wrap(delegate, MessageType.COMMAND);

        handler.getInvokerOrNull(command(new OtherRegistryCommand("miss")));
        assertEquals(0, delegate.invocations());

        handler.getInvokerOrNull(command(new RegistryCommand("hit")));
        assertEquals(1, delegate.invocations());
    }

    @Test
    void keepsWebRoutesOnNormalDispatchSemantics() {
        CountingHandler delegate = new CountingHandler(RegistryWebHandler.class);
        Handler<DeserializingMessage> handler = RegistryFilteringHandler.wrap(delegate, MessageType.WEBREQUEST);

        handler.getInvokerOrNull(web("/registry/other/42"));
        assertEquals(1, delegate.invocations());

        handler.getInvokerOrNull(web("/registry/items/42"));
        assertEquals(2, delegate.invocations());
    }

    @Test
    void acceptsAssignablePayloadRoutes() {
        CountingHandler delegate = new CountingHandler(InterfaceCommandHandler.class);
        Handler<DeserializingMessage> handler = RegistryFilteringHandler.wrap(delegate, MessageType.COMMAND);

        handler.getInvokerOrNull(command(new InterfaceCommand("hit")));
        assertEquals(1, delegate.invocations());
    }

    @Test
    void keepsMixedRuntimeResolvedRoutesOnNormalDispatchSemantics() {
        CountingHandler delegate = new CountingHandler(MixedCommandHandler.class);
        Handler<DeserializingMessage> handler = RegistryFilteringHandler.wrap(delegate, MessageType.COMMAND);

        handler.getInvokerOrNull(command(new OtherRegistryCommand("runtime")));
        assertEquals(1, delegate.invocations());
    }

    @Test
    void generatedOnlyModeUsesGeneratedClasspathMetadata() {
        GeneratedOnlyMetadataMode.run(() -> {
            CountingHandler delegate = new CountingHandler(GeneratedClasspathCommandHandler.class);
            Handler<DeserializingMessage> handler = RegistryFilteringHandler.wrap(delegate, MessageType.COMMAND);

            handler.getInvokerOrNull(command(new OtherRegistryCommand("miss")));
            assertEquals(0, delegate.invocations());
        });
    }

    @Test
    void generatedOnlyModeFiltersWithRegisteredMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(RegisteredGeneratedOnlyCommandHandler.class).registry());
            GeneratedOnlyMetadataMode.run(() -> {
                CountingHandler delegate = new CountingHandler(RegisteredGeneratedOnlyCommandHandler.class);
                Handler<DeserializingMessage> handler = RegistryFilteringHandler.wrap(delegate, MessageType.COMMAND);

                handler.getInvokerOrNull(command(new OtherRegistryCommand("miss")));
                assertEquals(0, delegate.invocations());

                handler.getInvokerOrNull(command(new RegistryCommand("hit")));
                assertEquals(1, delegate.invocations());
            });
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static DeserializingMessage command(Object payload) {
        return new DeserializingMessage(new Message(payload), MessageType.COMMAND, null);
    }

    private static DeserializingMessage web(String path) {
        return new DeserializingMessage(WebRequest.get(path).build(), MessageType.WEBREQUEST, null);
    }

    private static class CountingHandler implements Handler<DeserializingMessage> {
        private final Class<?> targetClass;
        private final AtomicInteger invocations = new AtomicInteger();

        private CountingHandler(Class<?> targetClass) {
            this.targetClass = targetClass;
        }

        @Override
        public Class<?> getTargetClass() {
            return targetClass;
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
            return Optional.ofNullable(getInvokerOrNull(message));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
            invocations.incrementAndGet();
            return HandlerInvoker.noOp();
        }

        @Override
        public HandlerMethod<DeserializingMessage> getHandlerMethodOrNull(DeserializingMessage message) {
            invocations.incrementAndGet();
            return null;
        }

        private int invocations() {
            return invocations.get();
        }
    }

    private record RegistryCommand(String value) {
    }

    private record OtherRegistryCommand(String value) {
    }

    private interface CommandInterface {
    }

    private record InterfaceCommand(String value) implements CommandInterface {
    }

    private static class RegistryCommandHandler {
        @HandleCommand
        void handle(RegistryCommand command) {
        }
    }

    private static class InterfaceCommandHandler {
        @HandleCommand
        void handle(CommandInterface command) {
        }
    }

    private static class MixedCommandHandler {
        @HandleCommand
        void handle(Object command, Metadata metadata) {
        }

        @HandleCommand
        void handle(RegistryCommand command) {
        }
    }

    private static class RegistryWebHandler {
        @HandleGet("/registry/items/{id}")
        String get() {
            return "ok";
        }
    }

    private static class RegisteredGeneratedOnlyCommandHandler {
        @HandleCommand
        void handle(RegistryCommand command) {
        }
    }

    private static class GeneratedClasspathCommandHandler {
        @HandleCommand
        void handle(RegistryCommand command) {
        }
    }
}
