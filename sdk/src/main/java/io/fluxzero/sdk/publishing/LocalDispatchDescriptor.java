/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;

/**
 * Describes the message characteristics that remain constant for a prepared local-dispatch policy.
 *
 * @param payloadClass the runtime class of the dispatched payload
 * @param messageType the type of message being dispatched
 * @param topic the dispatch topic, or {@code null} when no topic was supplied
 * @see DispatchInterceptor#prepareLocalDispatch(LocalDispatchDescriptor)
 */
public record LocalDispatchDescriptor(Class<?> payloadClass, MessageType messageType, String topic) {
}
