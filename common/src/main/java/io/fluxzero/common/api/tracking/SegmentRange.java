package io.fluxzero.common.api.tracking;

/**
 * Represents an index value for a contiguous range of segments.
 */
public record SegmentRange(int segmentStart, int segmentEnd, long index) {
    public static final int MAX_SEGMENT = 128;

    public static SegmentRange fullSegment(long lastIndex) {
        return new SegmentRange(0, MAX_SEGMENT, lastIndex);
    }
}
