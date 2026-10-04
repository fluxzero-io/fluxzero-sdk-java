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

package io.fluxzero.sdk.persisting.search;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentMessageReaderTest {
    record Value(String text) { }

    @Test
    void bufferedSerializerRetainsBatchAndSourceAcrossReorderedSplitOutputsAndInterceptorReplacement() throws Exception {
        var serializer = new JacksonSerializer() {
            @Override
            public Stream<DeserializingMessage> deserializeMessages(Stream<SerializedMessage> messages,
                                                                    MessageType type, String topic) {
                var batch = messages.toList();
                assertEquals(2, batch.size());
                return super.deserializeMessages(batch.reversed().stream().flatMap(source -> {
                    var transformed = source.withData(serialize(new Value("upcast")))
                            .withMetadata(Metadata.of("transport", "changed")).withSegment(42);
                    transformed.setMessageId("changed-id");
                    return Stream.of(transformed, transformed);
                }), type, topic);
            }
        };
        var first = source(serializer, "first");
        var second = source(serializer, "second");
        var result = new DocumentMessageReader().read(List.of(first, second), "docs", serializer)
                .map(message -> message.withPayload(new Value("intercepted")))
                .map(DocumentMessageReader::sourceMetadata).toList();
        assertEquals(List.of(Metadata.of("source", "second"), Metadata.of("source", "second"),
                             Metadata.of("source", "first"), Metadata.of("source", "first")), result);
        assertEquals("same-id", first.getMessageId());
        assertEquals("same-id", second.getMessageId());
    }

    @Test
    void customSerializerCanAttachOriginalSourcesToEntirelyNewSplitOutputs() throws Exception {
        var serializer = new JacksonSerializer() {
            @Override
            public Stream<DeserializingMessage> deserializeMessages(Stream<SerializedMessage> messages,
                                                                    MessageType type, String topic) {
                var batch = messages.toList();
                assertEquals(2, batch.size());
                return batch.reversed().stream().flatMap(original -> Stream.of("one", "two").map(value -> {
                    var output = new DeserializingMessage(
                            new Message(new Value(value), Metadata.of("transport", "replacement")), type, topic, this);
                    return DocumentMessageReader.retainSource(output, original);
                }));
            }
        };
        var result = new DocumentMessageReader().read(
                        List.of(source(serializer, "first"), source(serializer, "second")), "docs", serializer)
                .map(message -> message.withPayload(new Value("intercepted")))
                .map(DocumentMessageReader::sourceMetadata).toList();
        assertEquals(List.of(Metadata.of("source", "second"), Metadata.of("source", "second"),
                             Metadata.of("source", "first"), Metadata.of("source", "first")), result);
    }

    private static SerializedMessage source(JacksonSerializer serializer, String metadata) throws Exception {
        var document = serializer.toDocument(new Value("stored"), "same-id", "docs", null, null,
                                             Metadata.of("source", metadata));
        var source = new SerializedMessage(document.getDocument(), Metadata.empty(), "same-id", 0L);
        return source;
    }
}
