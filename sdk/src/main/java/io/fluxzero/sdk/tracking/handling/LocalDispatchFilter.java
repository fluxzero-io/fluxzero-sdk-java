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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import lombok.NonNull;

import java.lang.reflect.Executable;
import java.util.function.Supplier;

/**
 * Separates simulated tracking from actual local eligibility without creating a second handler or target instance.
 * Synchronous fixtures use this filter; ordinary explicit registry filters keep their local-registration contract.
 *
 * @param ordinary structural filter for ordinary dispatch, including simulated tracking
 * @param localOnly message filter when external publication is forbidden
 */
public record LocalDispatchFilter(@NonNull HandlerFilter ordinary,
                                  @NonNull MessageFilter<DeserializingMessage> localOnly) implements HandlerFilter {
    private enum Restricted { INSTANCE }

    @Override
    public boolean test(Class<?> ownerType, Executable executable) {
        return ordinary.test(ownerType, executable);
    }

    MessageFilter<DeserializingMessage> restrict(MessageFilter<? super DeserializingMessage> filter) {
        MessageFilter<DeserializingMessage> restriction = (message, method, annotation, target) ->
                message.getContext(Restricted.class).isEmpty() || localOnly.test(message, method, annotation, target);
        return restriction.and(filter);
    }

    static <T> T inDispatch(DeserializingMessage message, boolean allowExternalPublication, Supplier<T> action) {
        if (allowExternalPublication || message.getContext(Restricted.class).isPresent()) {
            return action.get();
        }
        message.putContext(Restricted.class, Restricted.INSTANCE);
        try {
            return action.get();
        } finally {
            message.withoutContext(Restricted.class);
        }
    }
}
