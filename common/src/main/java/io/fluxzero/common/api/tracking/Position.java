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

package io.fluxzero.common.api.tracking;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.fluxzero.common.api.SerializedMessage;
import lombok.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;

import static io.fluxzero.common.api.tracking.SegmentRange.fullSegment;

/**
 * Represents the tracking state of a consumer, i.e. the last known indexes of consumed messages per segment.
 * <p>
 * Fluxzero segments the message log to support parallel consumption. A {@code Position} stores
 * the most recent message index processed for each segment. This allows precise resumption of message
 * consumption on restarts or rebalances.
 *
 * <p>A {@code Position} is an immutable structure and may be merged or queried to support multi-segment tracking.
 */
@JsonSerialize(using = Position.PositionSerializer.class)
@JsonDeserialize(using = Position.PositionDeserializer.class)
@Value
public class Position {
    private static final Position NEW_POSITION = new Position(List.of());

    List<SegmentRange> segmentRanges;

    /**
     * Creates a position from a list of segment ranges.
     * @param segmentRanges a list of segment ranges, this list is expected to be sorted by segment start, segments are
     *                      expected to not overlap.
     */
    public Position(List<SegmentRange> segmentRanges) {
        this.segmentRanges = segmentRanges;
    }

    /**
     * Creates a position that applies the same index to all segments.
     */
    public Position(long index) {
        this(fullSegment(index));
    }

    /**
     * Creates a position for a single segment range.
     */
    public Position(int[] segment, long lastIndex) {
        this(new SegmentRange(segment[0], segment[1], lastIndex));
    }

    /**
     * Creates a position for a single segment range.
     */
    public Position(SegmentRange segmentRange) {
        this(List.of(segmentRange));
    }

    /**
     * Creates an empty position.
     */
    public static Position newPosition() {
        return NEW_POSITION;
    }

    /**
     * Returns the last known index for a specific segment.
     */
    public Optional<Long> getIndex(int segment) {
        for (SegmentRange range : segmentRanges) {
            if (range.segmentStart() > segment) {
                break;
            }
            if (segment < range.segmentEnd()) {
                return Optional.of(range.index());
            }
        }
        return Optional.empty();
    }

    /**
     * Indicates whether no index has been tracked yet for any segment in the given range.
     */
    public boolean isNew(int[] segment) {
        int start = segment[0], end = segment[1];
        for (SegmentRange range : segmentRanges) {
            if (range.segmentStart() >= end) {
                break;
            }
            if (range.segmentEnd() > start) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the lowest tracked index within the given segment range.
     */
    public Optional<Long> lowestIndexForSegment(int[] segment) {
        int start = segment[0], end = segment[1];
        Long lowest = null;
        for (SegmentRange range : segmentRanges) {
            if (range.segmentStart() >= end) {
                break;
            }
            if (range.segmentEnd() <= start) {
                continue;
            }
            if (lowest == null || range.index() < lowest) {
                lowest = range.index();
            }
        }
        return Optional.ofNullable(lowest);
    }

    /**
     * Indicates whether the message index is newer than what is currently tracked for its segment.
     */
    public boolean isNewMessage(SerializedMessage message) {
        return isNewIndex(message.getSegment(), message.getIndex());
    }

    /**
     * Indicates whether the provided message index is newer than what is currently tracked for a segment.
     */
    public boolean isNewIndex(int segment, Long messageIndex) {
        Optional<Long> lastIndex = getIndex(segment);
        return lastIndex.isEmpty() || messageIndex == null || lastIndex.get() < messageIndex;
    }

    /**
     * Merges two {@code Position} objects by taking the highest known index per segment.
     */
    public Position merge(Position other) {
        List<SegmentRange> result = new ArrayList<>(segmentRanges.size() + other.segmentRanges.size());

        NullableIterator<SegmentRange> iterA = new NullableIterator<>(segmentRanges.iterator());
        NullableIterator<SegmentRange> iterB = new NullableIterator<>(other.segmentRanges.iterator());

        SegmentRange a = iterA.nextOrNull();
        SegmentRange b = iterB.nextOrNull();

        while (a != null || b != null) {
            if (a == null) {
                appendRange(result, b);
                b = iterB.nextOrNull();
                continue;
            }
            if (b == null) {
                appendRange(result, a);
                a = iterA.nextOrNull();
                continue;
            }

            if (a.segmentEnd() <= b.segmentStart()) {
                appendRange(result, a);
                a = iterA.nextOrNull();
                continue;
            }
            if (b.segmentEnd() <= a.segmentStart()) {
                appendRange(result, b);
                b = iterB.nextOrNull();
                continue;
            }

            if (a.index() >= b.index()) {
                if (b.segmentStart() < a.segmentStart()) {
                    appendRange(result, new SegmentRange(b.segmentStart(), a.segmentStart(), b.index()));
                }
                if (b.segmentEnd() <= a.segmentEnd()) {
                    b = iterB.nextOrNull();
                } else {
                    b = new SegmentRange(a.segmentEnd(), b.segmentEnd(), b.index());
                    appendRange(result, a);
                    a = iterA.nextOrNull();
                }
                continue;
            }

            if (a.segmentStart() < b.segmentStart()) {
                appendRange(result, new SegmentRange(a.segmentStart(), b.segmentStart(), a.index()));
            }
            if (a.segmentEnd() <= b.segmentEnd()) {
                a = iterA.nextOrNull();
            } else {
                a = new SegmentRange(b.segmentEnd(), a.segmentEnd(), a.index());
                appendRange(result, b);
                b = iterB.nextOrNull();
            }
        }
        return new Position(List.copyOf(result));
    }

    private static void appendRange(List<SegmentRange> result, SegmentRange range) {
        if (range.segmentStart() >= range.segmentEnd()) {
            return;
        }
        int size = result.size();
        if (size > 0) {
            SegmentRange last = result.get(size - 1);
            if (last.segmentEnd() == range.segmentStart() && last.index() == range.index()) {
                result.set(size - 1, new SegmentRange(last.segmentStart(), range.segmentEnd(), last.index()));
                return;
            }
        }
        result.add(range);
    }

    private record NullableIterator<T> (Iterator<T> iterator) {
        T nextOrNull() {
            return iterator.hasNext() ? iterator.next() : null;
        }
    }

    static class PositionSerializer extends JsonSerializer<Position> {
        @Override
        public void serialize(Position value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartArray();
            for (SegmentRange range : value.segmentRanges) {
                gen.writeStartArray();
                gen.writeNumber(range.segmentStart());
                gen.writeNumber(range.segmentEnd());
                gen.writeEndArray();
                gen.writeNumber(range.index());
            }
            gen.writeEndArray();
        }
    }

    static class PositionDeserializer extends JsonDeserializer<Position> {
        private final TypeReference<SortedMap<Integer, Long>> mapTypeReference = new TypeReference<>() {};

        @Override
        public Position deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return switch (p.getCurrentToken()) {
                case START_OBJECT -> {
                    p.nextToken();
                    p.nextToken();
                    yield fromIndexBySegment(p.readValueAs(mapTypeReference));
                }
                case START_ARRAY -> {
                    List<SegmentRange> ranges = new ArrayList<>();
                    while (p.nextToken() == JsonToken.START_ARRAY) {
                        int[] range = new int[2];
                        p.nextToken();
                        range[0] = p.getIntValue();
                        p.nextToken();
                        range[1] = p.getIntValue();
                        p.nextToken();
                        p.nextToken();
                        long index = p.getLongValue();
                        ranges.add(new SegmentRange(range[0], range[1], index));
                    }
                    yield new Position(ranges);
                }
                default -> throw new UnsupportedOperationException();
            };
        }

        private static Position fromIndexBySegment(SortedMap<Integer, Long> indexBySegment) {
            List<SegmentRange> ranges = new ArrayList<>();
            Integer start = null;
            Integer previousSegment = null;
            Long previousIndex = null;

            for (Map.Entry<Integer, Long> entry : indexBySegment.entrySet()) {
                int segment = entry.getKey();
                Long index = entry.getValue();
                if (start == null) {
                    start = segment;
                    previousSegment = segment;
                    previousIndex = index;
                    continue;
                }
                if (segment == previousSegment + 1 && Objects.equals(previousIndex, index)) {
                    previousSegment = segment;
                    continue;
                }
                ranges.add(new SegmentRange(start, previousSegment + 1, previousIndex));
                start = segment;
                previousSegment = segment;
                previousIndex = index;
            }

            if (start != null) {
                ranges.add(new SegmentRange(start, previousSegment + 1, previousIndex));
            }

            return new Position(List.copyOf(ranges));
        }
    }
}
