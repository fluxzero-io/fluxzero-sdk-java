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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import static java.lang.String.format;

/**
 * Prevents runaway recursive publication loops by carrying a publication depth in message metadata.
 */
public class RecursivePublicationGuard implements DispatchInterceptor {
    public static final String PUBLICATION_DEPTH_METADATA_KEY = "$publicationDepth";
    public static final int DEFAULT_MAX_DEPTH = 100;

    private final int maxPublicationDepth;

    public RecursivePublicationGuard() {
        this(DEFAULT_MAX_DEPTH);
    }

    public RecursivePublicationGuard(int maxPublicationDepth) {
        if (maxPublicationDepth < 0) {
            throw new IllegalArgumentException("Max publication depth should be >= 0");
        }
        this.maxPublicationDepth = maxPublicationDepth;
    }

    @Override
    public Message interceptDispatch(Message message, MessageType messageType, String topic) {
        int depth = nextDepth(messageType);
        if (depth > maxPublicationDepth) {
            throw new RecursivePublicationException(format(
                    "Publication depth %s exceeds the maximum of %s for %s %s",
                    depth, maxPublicationDepth, messageType, message.getPayloadClass().getName()));
        }
        return message.addMetadata(PUBLICATION_DEPTH_METADATA_KEY, Integer.toString(depth));
    }

    protected int nextDepth(MessageType messageType) {
        if (messageType == MessageType.SCHEDULE) {
            return 0;
        }
        DeserializingMessage currentMessage = DeserializingMessage.getCurrent();
        if (currentMessage == null) {
            return 0;
        }
        int currentDepth = readDepth(currentMessage.getMetadata());
        return currentDepth == Integer.MAX_VALUE ? Integer.MAX_VALUE : currentDepth + 1;
    }

    protected int readDepth(Metadata metadata) {
        if (metadata == null) {
            return 0;
        }
        return parseDepth(metadata.get(PUBLICATION_DEPTH_METADATA_KEY));
    }

    protected int parseDepth(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return 0;
            }
            if (result > (Integer.MAX_VALUE - digit) / 10) {
                return Integer.MAX_VALUE;
            }
            result = result * 10 + digit;
        }
        return result;
    }
}
