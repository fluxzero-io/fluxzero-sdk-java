/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.search.DefaultDocumentSerializer;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;

import java.util.List;
import java.util.stream.Stream;

/** Retains the exact stored document source through serialization and handler interceptors. */
public final class DocumentMessageReader {
    /** Reads one complete serializer batch, preserving per-document source metadata through upcasting. */
    public Stream<DeserializingMessage> read(List<SerializedMessage> messages, String topic, Serializer serializer) {
        return serializer.deserializeMessages(messages.stream().map(SourceDocumentMessage::new),
                                              MessageType.DOCUMENT, topic)
                .map(message -> message.getSerializedObject() instanceof SourceDocumentMessage sourceMessage
                        ? message.putContext(DocumentSource.class, sourceMessage.original) : message);
    }

    /**
     * Retains the original stored document bytes for metadata preservation after custom deserialization.
     * Use this when a custom {@link Serializer#deserializeMessages(Stream, MessageType, String)} implementation
     * constructs entirely new envelopes or {@link DeserializingMessage} instances. Normal input-envelope
     * {@code withData}, {@code withMetadata} and {@code withSegment} transformations already retain the source
     * when invoked through this reader.
     * <p>
     * Keep the original input before any transformation and attach it to each corresponding decoded output:
     * <pre>{@code
     * DocumentMessageReader.retainSource(decodedOutput, originalInput);
     * }</pre>
     * Keep this association explicitly when buffering, reordering or splitting a batch; message IDs alone are
     * insufficient because multiple versions can share an ID. This method does not require changing the batch
     * boundary. Later {@link DeserializingMessage#withPayload(Object)} replacements retain the attached context.
     * <p>
     * The source must contain the exact stored version's original {@link Data#DOCUMENT_FORMAT} data. Tracking
     * envelope metadata and the upcast/replacement payload are not substitutes for those bytes. Metadata is read
     * lazily only when needed; there is no storage fetch or lookup of a newer version. Arbitrary custom formats
     * are not decoded by this helper.
     * <p>
     * This attaches metadata provenance only: it does not copy the source message's ID, timestamps, revision or
     * transport metadata into the output. A custom serializer remains responsible for preserving those ordinary
     * message-envelope contracts.
     *
     * @param message the corresponding custom-decoded output
     * @param source the unchanged original input document envelope
     * @return the supplied output with its source attached to the handling context
     * @see #sourceMetadata(DeserializingMessage)
     */
    public static DeserializingMessage retainSource(DeserializingMessage message, SerializedMessage source) {
        return message.putContext(DocumentSource.class, new DocumentSource(source.getData()));
    }

    /**
     * Returns metadata embedded in the handled document version, excluding the tracking envelope's time fields.
     * Decoding is deferred until a replacement is actually needed. Non-document inputs have no stored metadata.
     */
    public static Metadata sourceMetadata(DeserializingMessage message) {
        return storedMetadata(message);
    }

    private static Metadata storedMetadata(DeserializingMessage message) {
        Data<byte[]> data = message.getContext(DocumentSource.class).map(DocumentSource::data)
                .orElseGet(() -> message.getSerializedObject().getData());
        return DefaultDocumentSerializer.INSTANCE.canDeserialize(data)
                ? DefaultDocumentSerializer.INSTANCE.deserializeMetadata(data) : Metadata.empty();
    }

    private record DocumentSource(Data<byte[]> data) { }

    // Carry source attribution through serializer buffering, reordering, split/drop and metadata upcasts without
    // changing the serializer's batch boundary. The handling context then retains it across interceptor replacements.
    private static final class SourceDocumentMessage extends SerializedMessage {
        private final transient DocumentSource original;

        private SourceDocumentMessage(SerializedMessage source) {
            this(source, source.getData(), source.getMetadata(), source.getSegment(),
                 new DocumentSource(source.getData()));
        }

        private SourceDocumentMessage(SerializedMessage source, Data<byte[]> data, Metadata metadata, Integer segment,
                                      DocumentSource original) {
            super(data, metadata, segment, source.getIndex(), source.getSource(),
                  source.getTarget(), source.getRequestId(), source.getTimestamp(), source.getMessageId(),
                  source.getOriginalRevision());
            this.original = original;
        }

        @Override
        public SerializedMessage withData(Data<byte[]> data) {
            return data == getData() ? this : new SourceDocumentMessage(this, data, getMetadata(), getSegment(), original);
        }

        @Override
        public SerializedMessage withMetadata(Metadata metadata) {
            return metadata == getMetadata() ? this
                    : new SourceDocumentMessage(this, getData(), metadata, getSegment(), original);
        }

        @Override
        public SerializedMessage withSegment(Integer segment) {
            return java.util.Objects.equals(segment, getSegment()) ? this
                    : new SourceDocumentMessage(this, getData(), getMetadata(), segment, original);
        }
    }

}
