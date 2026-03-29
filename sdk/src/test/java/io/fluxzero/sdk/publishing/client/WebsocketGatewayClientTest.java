package io.fluxzero.sdk.publishing.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebsocketGatewayClientTest {

    @Test
    void partitionByRoutingKeyKeepsMessagesWithSameSegmentTogether() {
        SerializedMessage first = message("a", 3);
        SerializedMessage second = message("b", 7);
        SerializedMessage third = message("c", 3);

        List<List<SerializedMessage>> partitions = WebsocketGatewayClient.partitionByRoutingKey(
                List.of(first, second, third));

        assertEquals(List.of(List.of(first, third), List.of(second)), partitions);
    }

    @Test
    void partitionByRoutingKeyFallsBackToMessageId() {
        SerializedMessage first = message("a", null);
        SerializedMessage second = message("b", null);

        List<List<SerializedMessage>> partitions = WebsocketGatewayClient.partitionByRoutingKey(List.of(first, second));

        assertEquals(List.of(List.of(first), List.of(second)), partitions);
    }

    private static SerializedMessage message(String messageId, Integer segment) {
        SerializedMessage message = new SerializedMessage(new Data<>(new byte[]{1}, byte[].class.getName(), 0,
                                                                    Data.JSON_FORMAT), Metadata.empty(), messageId, 1L);
        message.setSegment(segment);
        return message;
    }
}
