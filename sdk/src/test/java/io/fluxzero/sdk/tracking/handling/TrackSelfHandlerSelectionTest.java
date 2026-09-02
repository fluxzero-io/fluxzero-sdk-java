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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.TrackSelf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackSelfHandlerSelectionTest {

    @Test
    void doesNotDeserializeNonMatchingPayload() {
        AtomicInteger deserializationAttempts = new AtomicInteger();
        DeserializingMessage message = malformedMessage(OtherCommand.class, deserializationAttempts);

        assertNull(handler(TrackedCommand.class).getInvokerOrNull(message));
        assertEquals(0, deserializationAttempts.get());
    }

    @Test
    void matchingSubtypeStillDeserializesAndFailsNormally() {
        AtomicInteger deserializationAttempts = new AtomicInteger();
        DeserializingMessage message = malformedMessage(ConcreteTrackedCommand.class, deserializationAttempts);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> handler(TrackedCommand.class).getInvokerOrNull(message));

        assertEquals("malformed payload", error.getMessage());
        assertEquals(1, deserializationAttempts.get());
    }

    private static Handler<DeserializingMessage> handler(Class<?> targetClass) {
        return new DefaultHandlerFactory(
                MessageType.COMMAND, HandlerDecorator.noOp, List.of(), MethodInvocationValidator.noOp(),
                ignored -> null, null, false, null)
                .createHandler(targetClass, (type, executable) -> true, List.of()).orElseThrow();
    }

    private static DeserializingMessage malformedMessage(Class<?> payloadClass,
                                                          AtomicInteger deserializationAttempts) {
        SerializedMessage serializedMessage = new SerializedMessage(
                new Data<>(new byte[0], payloadClass.getName(), 0), Metadata.empty(), "message-id", 0L);
        return new DeserializingMessage(serializedMessage, ignored -> {
            deserializationAttempts.incrementAndGet();
            throw new IllegalStateException("malformed payload");
        }, MessageType.COMMAND, null, null);
    }

    @TrackSelf
    interface TrackedCommand {
        @HandleCommand
        default void handle() {
        }
    }

    record ConcreteTrackedCommand() implements TrackedCommand {
    }

    record OtherCommand() {
    }
}
