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

package io.fluxzero.common.tracking;

import io.fluxzero.common.api.SerializedMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Result of scanning a message store for a tracking batch.
 *
 * @param messages         messages accepted by the scan filter
 * @param lastScannedIndex index of the last scanned source message, regardless of whether it matched the filter
 * @param scannedSize      number of source messages scanned
 * @param byteLimited      whether scanning stopped because the next accepted message would exceed the byte budget
 */
public record MessageStoreBatch(
        List<SerializedMessage> messages,
        Long lastScannedIndex,
        int scannedSize,
        boolean byteLimited) {

    /**
     * Scans source messages until the source count or accepted-message byte budget is exhausted.
     * <p>
     * The first accepted message is returned even when it exceeds {@code maxBytes}, so consumers can keep making
     * progress.
     *
     * @param source   source messages in index order
     * @param maxSize  maximum number of source messages to scan
     * @param maxBytes maximum accepted-message payload bytes, or {@code 0} for no byte limit
     * @param filter   predicate deciding which messages enter the returned batch
     * @return a batch with both accepted messages and source scan metadata
     */
    public static MessageStoreBatch scan(Iterable<SerializedMessage> source, int maxSize, long maxBytes,
                                         Predicate<? super SerializedMessage> filter) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be non-negative");
        }
        if (maxSize == 0) {
            return empty();
        }
        ArrayList<SerializedMessage> messages = new ArrayList<>(Math.min(maxSize, 1024));
        Long lastScannedIndex = null;
        int scannedSize = 0;
        long bytes = 0L;

        for (SerializedMessage message : source) {
            scannedSize++;
            lastScannedIndex = message.getIndex();
            if (filter.test(message)) {
                if (maxBytes > 0L && !messages.isEmpty() && bytes + message.getBytes() > maxBytes) {
                    return new MessageStoreBatch(messages, lastScannedIndex, scannedSize, true);
                }
                messages.add(message);
                bytes += message.getBytes();
            }
            if (scannedSize == maxSize) {
                break;
            }
        }
        return new MessageStoreBatch(messages, lastScannedIndex, scannedSize, false);
    }

    private static MessageStoreBatch empty() {
        return new MessageStoreBatch(List.of(), null, 0, false);
    }
}
