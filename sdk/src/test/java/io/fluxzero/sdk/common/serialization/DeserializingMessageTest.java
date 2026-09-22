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

package io.fluxzero.sdk.common.serialization;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeserializingMessageTest {

    @Test
    void restoredPayloadViewKeepsEnvelopeContextAndAliasesUntilARealReplacement() {
        Serializer serializer = new JacksonSerializer();
        SerializedMessage raw = new SerializedMessage(serializer.serialize("redacted").withType("custom-alias")
                .withRevision(3), Metadata.of("original", "value"), "id", 42L);
        raw.setIndex(123L);
        DeserializingMessage source = new DeserializingMessage(raw, ignored -> "redacted", MessageType.EVENT,
                                                               "topic", serializer);
        source.putContext(AtomicInteger.class, new AtomicInteger(7));
        DeserializingMessage restored = source.withRestoredPayload("restored");
        DeserializingMessage enriched = restored.withMetadata(restored.getMetadata().with("extra", "metadata"));
        assertEquals("restored", enriched.getPayload());
        assertEquals("restored", enriched.getPayloadAs(String.class));
        assertEquals(String.class, enriched.getPayloadClass());
        assertEquals("custom-alias", enriched.getType());
        assertEquals(123L, enriched.getIndex());
        assertEquals("topic", enriched.getTopic());
        assertSame(raw.getData(), enriched.getSerializedObject().getData());
        assertEquals(3, enriched.getSerializedObject().getRevision());
        assertEquals(42L, enriched.getSerializedObject().getTimestamp());
        assertSame(source.getContext(AtomicInteger.class).orElseThrow(),
                   enriched.getContext(AtomicInteger.class).orElseThrow());
        assertEquals(String.class, new DeserializingMessage(enriched).getPayloadClass());
        assertEquals("changed", serializer.deserialize(enriched.withPayload("changed").getSerializedObject().getData()));
        assertEquals("redacted", source.getPayload());
    }

    @Test
    void returnsVoidPayloadClassWhenDelegatePayloadClassIsUnknown() {
        Serializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                new Data<>("Boom!".getBytes(), null, 0, "unknown"),
                null,
                "message-id",
                0L);

        DeserializingMessage message = new DeserializingMessage(
                new DeserializingObject<>(serializedMessage, type -> "Boom!"),
                MessageType.WEBREQUEST,
                null,
                serializer);

        message.getPayload();

        assertEquals(Void.class, message.getPayloadClass());
        assertDoesNotThrow(message::toString);
    }

    @Test
    void applySetsCurrentMessageAndCompletesBatch() {
        DeserializingMessage message = message("payload");
        List<Throwable> completions = new ArrayList<>();

        String result = message.apply(current -> {
            assertSame(message, DeserializingMessage.getCurrent());
            DeserializingMessage.whenBatchCompletes(completions::add);
            return current.getPayloadAs(String.class);
        });

        assertEquals("payload", result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(1, completions.size());
        assertNull(completions.getFirst());
    }

    @Test
    void nestedApplyCompletesWithOuterBatch() {
        DeserializingMessage outer = message("outer");
        DeserializingMessage inner = message("inner");
        List<Throwable> completions = new ArrayList<>();

        String result = outer.apply(current -> {
            DeserializingMessage.whenBatchCompletes(completions::add);
            String innerResult = inner.apply(nested -> {
                assertSame(inner, DeserializingMessage.getCurrent());
                DeserializingMessage.whenBatchCompletes(completions::add);
                return nested.getPayloadAs(String.class);
            });
            assertEquals("inner", innerResult);
            assertSame(outer, DeserializingMessage.getCurrent());
            assertEquals(List.of(), completions);
            return current.getPayloadAs(String.class);
        });

        assertEquals("outer", result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(2, completions.size());
        assertNull(completions.get(0));
        assertNull(completions.get(1));
    }

    @Test
    void applyCompletesBatchWithErrorWhenActionFails() {
        DeserializingMessage message = message("payload");
        List<Throwable> completions = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException result = assertThrows(IllegalStateException.class, () -> message.apply(current -> {
            DeserializingMessage.whenBatchCompletes(completions::add);
            throw failure;
        }));

        assertSame(failure, result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(List.of(failure), completions);
    }

    @Test
    void forEachInBatchSetsCurrentMessageAndCompletesBatchOnce() {
        DeserializingMessage first = message("first");
        DeserializingMessage second = message("second");
        List<String> payloads = new ArrayList<>();
        List<Throwable> completions = new ArrayList<>();

        DeserializingMessage.forEachInBatch(List.of(first, second), message -> {
            assertSame(message, DeserializingMessage.getCurrent());
            DeserializingMessage.whenBatchCompletes(completions::add);
            payloads.add(message.getPayloadAs(String.class));
            assertEquals(List.of(), completions);
        });

        assertEquals(List.of("first", "second"), payloads);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(2, completions.size());
        assertNull(completions.getFirst());
        assertNull(completions.get(1));
    }

    @Test
    void forEachInBatchCompletesBatchWithErrorWhenActionFails() {
        DeserializingMessage message = message("payload");
        List<Throwable> completions = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException result = assertThrows(IllegalStateException.class, () ->
                DeserializingMessage.forEachInBatch(List.of(message), current -> {
                    DeserializingMessage.whenBatchCompletes(completions::add);
                    throw failure;
                }));

        assertSame(failure, result);
        assertNull(DeserializingMessage.getCurrent());
        assertEquals(List.of(failure), completions);
    }

    @Test
    void forEachInBatchRestoresCurrentBetweenCustomIterableItems() {
        DeserializingMessage first = message("first");
        DeserializingMessage second = message("second");
        Iterable<DeserializingMessage> iterable = () -> new java.util.Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                assertNull(DeserializingMessage.getCurrent());
                return index < 2;
            }

            @Override
            public DeserializingMessage next() {
                assertNull(DeserializingMessage.getCurrent());
                return index++ == 0 ? first : second;
            }
        };

        DeserializingMessage.forEachInBatch(iterable, message ->
                assertSame(message, DeserializingMessage.getCurrent()));

        assertNull(DeserializingMessage.getCurrent());
    }

    @ParameterizedTest
    @MethodSource("scopesAndFailures")
    void caughtNestedFailureKeepsOuterBatchOpen(BatchScope scope, Throwable failure) {
        List<Throwable> completions = new ArrayList<>();
        Object key = new Object();
        Object resource = new Object();
        DeserializingMessage outer = message("outer");

        DeserializingMessage.forEachInBatch(List.of(outer, message("next")), current -> {
            if (current == outer) {
                DeserializingMessage.computeForBatchIfAbsent(key, ignored -> resource);
                DeserializingMessage.whenBatchCompletes(completions::add);
                assertSame(failure, assertThrows(failure.getClass(), () -> scope.run(message("inner"), inner -> {
                    DeserializingMessage.whenBatchCompletes(completions::add);
                    throwFailure(failure);
                })));
            }
            assertSame(current, DeserializingMessage.getCurrent());
            assertSame(resource, DeserializingMessage.getBatchResource(key));
            assertTrue(completions.isEmpty());
        });

        assertEquals(2, completions.size());
        assertTrue(completions.stream().allMatch(java.util.Objects::isNull));
        assertNull(DeserializingMessage.getCurrent());
        assertResourceCleared(key);
    }

    @ParameterizedTest
    @MethodSource("scopesAndFailures")
    void escapingNestedFailureCompletesOnlyAfterOuterScopeUnwinds(BatchScope scope, Throwable failure) {
        List<Throwable> completions = new ArrayList<>();
        List<String> order = new ArrayList<>();
        Object key = new Object();
        DeserializingMessage outer = message("outer");

        assertSame(failure, assertThrows(failure.getClass(), () -> outer.run(current -> {
            DeserializingMessage.computeForBatchIfAbsent(key, ignored -> new Object());
            DeserializingMessage.whenBatchCompletes(error -> {
                order.add("completed");
                completions.add(error);
            });
            try {
                scope.run(message("inner"), inner -> throwFailure(failure));
            } finally {
                assertSame(outer, DeserializingMessage.getCurrent());
                order.add("unwound");
            }
        })));

        assertEquals(List.of("unwound", "completed"), order);
        assertEquals(List.of(failure), completions);
        assertNull(DeserializingMessage.getCurrent());
        assertResourceCleared(key);
    }

    @ParameterizedTest
    @MethodSource("scopesAndFailures")
    void outermostFailureCompletesAndClearsBatch(BatchScope scope, Throwable failure) {
        List<Throwable> completions = new ArrayList<>();
        Object key = new Object();
        assertSame(failure, assertThrows(failure.getClass(), () -> scope.run(message("outer"), current -> {
            DeserializingMessage.computeForBatchIfAbsent(key, ignored -> new Object());
            DeserializingMessage.whenBatchCompletes(completions::add);
            throwFailure(failure);
        })));
        assertEquals(List.of(failure), completions);
        assertNull(DeserializingMessage.getCurrent());
        assertResourceCleared(key);
    }

    @ParameterizedTest
    @EnumSource(BatchScope.class)
    void completionFailurePropagatesAndClearsBatch(BatchScope scope) {
        RuntimeException failure = new IllegalStateException("completion failed");
        AtomicInteger calls = new AtomicInteger();
        Object key = new Object();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> scope.run(message("outer"), current -> {
            DeserializingMessage.computeForBatchIfAbsent(key, ignored -> new Object());
            DeserializingMessage.whenBatchCompletes(error -> {
                assertNull(error);
                calls.incrementAndGet();
                throw failure;
            });
        })));
        assertEquals(1, calls.get());
        assertNull(DeserializingMessage.getCurrent());
        assertResourceCleared(key);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fixtureKeepsCaughtNestedFailureInsideHandler(boolean asynchronous) {
        AtomicInteger completions = new AtomicInteger();
        TestFixture fixture = TestFixture.create(new Object() {
            @HandleCommand
            String handle(String command) {
                DeserializingMessage.whenBatchCompletes(error -> {
                    assertNull(error);
                    completions.incrementAndGet();
                    Fluxzero.publishEvent("batch completed");
                });
                RuntimeException rejection = new IllegalStateException("rejected");
                assertSame(rejection, assertThrows(IllegalStateException.class,
                        () -> message("nested").run(inner -> {
                            throw rejection;
                        })));
                assertEquals(0, completions.get());
                return "handled";
            }
        });
        if (asynchronous) {
            fixture = fixture.async();
        }
        fixture.whenCommand("outer").expectResult("handled").expectEvents("batch completed").expectNoErrors();
        assertEquals(1, completions.get());
    }

    private static Stream<Arguments> scopesAndFailures() {
        return Stream.of(BatchScope.values()).flatMap(scope -> Stream.of(
                Arguments.of(scope, new IllegalStateException("nested failure")),
                Arguments.of(scope, new AssertionError("nested error"))));
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    private static void assertResourceCleared(Object key) {
        message("fresh").run(current -> assertNull(DeserializingMessage.getBatchResource(key)));
    }

    private enum BatchScope {
        APPLY, LIST, ITERABLE, STREAM;

        void run(DeserializingMessage message, Consumer<DeserializingMessage> action) {
            switch (this) {
                case APPLY -> message.run(action);
                case LIST -> DeserializingMessage.forEachInBatch(List.of(message), action);
                case ITERABLE -> DeserializingMessage.forEachInBatch(() -> List.of(message).iterator(), action);
                case STREAM -> DeserializingMessage.handleBatch(Stream.of(message)).forEach(action);
            }
        }
    }

    @Test
    void messageBatchResourcesAreSharedWithAsyncWorkersAndIsolatedBetweenBatches() {
        Object key = new Object();
        Object firstResource = new Object();
        List<Integer> positions = new ArrayList<>();
        DeserializingMessage first = message("first");
        DeserializingMessage second = message("second");
        first.getSerializedObject().setSegment(11);
        second.getSerializedObject().setSegment(12);

        DeserializingMessage.forEachInBatch(
                List.of(first, second), current -> {
                    Object resource = DeserializingMessage.computeForMessageBatchIfAbsent(
                            key, ignored -> firstResource);
                    assertSame(firstResource, resource);
                    int expectedPosition = positions.size();
                    assertEquals(expectedPosition, DeserializingMessage.getMessageBatchIndex());
                    var context = current.captureContext();
                    positions.add(CompletableFuture.supplyAsync(context.wrap(() -> {
                        assertSame(firstResource,
                                   DeserializingMessage.getMessageBatchResource(key));
                        assertEquals(
                                11 + expectedPosition,
                                DeserializingMessage.getMessageBatchSegment());
                        return DeserializingMessage.getMessageBatchIndex();
                    })).join());
                });

        assertEquals(List.of(0, 1), positions);
        assertEquals(-1, DeserializingMessage.getMessageBatchIndex());
        assertEquals(-1, DeserializingMessage.getMessageBatchSegment());
        assertNull(DeserializingMessage.getMessageBatchResource(key));

        Object secondResource = new Object();
        DeserializingMessage.forEachInBatch(
                List.of(message("third")), ignored -> {
                    Object resource = DeserializingMessage.computeForMessageBatchIfAbsent(
                            key, unused -> secondResource);
                    assertSame(secondResource, resource);
                    assertNotSame(firstResource, resource);
                    assertEquals(0, DeserializingMessage.getMessageBatchIndex());
                });
    }

    @Test
    void nestedMessageHandlingRetainsTheOuterBatchResourceAndPosition() {
        Object key = new Object();
        DeserializingMessage outer = message("outer");
        outer.getSerializedObject().setSegment(27);

        DeserializingMessage.forEachInBatch(List.of(outer), ignored -> {
            Object resource = DeserializingMessage.computeForMessageBatchIfAbsent(
                    key, unused -> new Object());
            message("inner").apply(inner -> {
                assertSame(resource, DeserializingMessage.getMessageBatchResource(key));
                assertEquals(0, DeserializingMessage.getMessageBatchIndex());
                assertEquals(27, DeserializingMessage.getMessageBatchSegment());
                return null;
            });
        });

        assertNull(DeserializingMessage.getMessageBatchResource(key));
    }

    @Test
    void withMetadataSharesMemoizedPayload() {
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                serializer.serialize("serialized"), Metadata.empty(), "message-id", 0L);
        AtomicInteger calls = new AtomicInteger();
        DeserializingMessage message = new DeserializingMessage(
                new DeserializingObject<>(serializedMessage, type -> {
                    calls.incrementAndGet();
                    return "payload";
                }),
                MessageType.EVENT,
                null,
                serializer);

        DeserializingMessage withMetadata = message.withMetadata(Metadata.of("key", "value"));

        assertFalse(message.isDeserialized());
        assertFalse(withMetadata.isDeserialized());
        assertEquals("payload", message.getPayload());
        assertEquals("payload", withMetadata.getPayload());
        assertEquals(1, calls.get());
        assertTrue(message.isDeserialized());
        assertTrue(withMetadata.isDeserialized());
    }

    @Test
    void deserializingObjectCachesDefaultAndTypedPayloads() {
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                serializer.serialize("serialized"), Metadata.empty(), "message-id", 0L);
        AtomicInteger calls = new AtomicInteger();
        DeserializingObject<byte[], SerializedMessage> object = new DeserializingObject<>(
                serializedMessage, type -> "%s-%d".formatted(type, calls.incrementAndGet()));

        assertFalse(object.isDeserialized());
        assertEquals("class java.lang.Object-1", object.getPayload());
        assertEquals("class java.lang.Object-1", object.getPayload());
        assertTrue(object.isDeserialized());
        assertEquals("class java.lang.String-2", object.getPayloadAs(String.class));
        assertEquals("class java.lang.String-2", object.getPayloadAs(String.class));
        assertEquals(2, calls.get());
    }

    private static DeserializingMessage message(String payload) {
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedMessage = new SerializedMessage(
                serializer.serialize(payload), Metadata.empty(), "message-id", 0L);
        return new DeserializingMessage(serializedMessage, m -> payload, MessageType.EVENT, null, serializer);
    }
}
