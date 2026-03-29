package io.fluxzero.common.api.publishing;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppendTest {

    @Test
    void routingKeyUsesSegmentWhenAvailable() {
        SerializedMessage first = message("a", 4);
        SerializedMessage second = message("b", 4);

        assertEquals("4", new Append(MessageType.WEBREQUEST, List.of(first, second), Guarantee.SENT).routingKey());
    }

    @Test
    void routingKeyFallsBackToMessageIdWhenSegmentIsMissing() {
        assertEquals("a",
                     new Append(MessageType.WEBREQUEST, List.of(message("a", null)), Guarantee.SENT).routingKey());
    }

    @Test
    void routingKeyUsesFirstMessageAffinityForMixedBatch() {
        Append append = new Append(MessageType.WEBREQUEST, List.of(message("a", 4), message("b", 5)),
                                   Guarantee.SENT);

        assertEquals("4", append.routingKey());
    }

    private static SerializedMessage message(String messageId, Integer segment) {
        SerializedMessage message = new SerializedMessage(new Data<>(new byte[]{1}, byte[].class.getName(), 0,
                                                                    Data.JSON_FORMAT), Metadata.empty(), messageId, 1L);
        message.setSegment(segment);
        return message;
    }
}
