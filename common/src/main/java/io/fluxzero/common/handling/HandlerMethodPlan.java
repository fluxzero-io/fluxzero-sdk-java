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

package io.fluxzero.common.handling;

/**
 * A reusable plan for invoking one already selected handler method.
 *
 * <p>A plan contains the method, its argument-resolution strategy, and either a fixed handler target or a strategy for
 * obtaining that target from the current {@link HandlerInput}. For example, a self-handling payload plan obtains its
 * target from {@link HandlerInput#getPayload()} on every invocation. Implementations must be immutable and safe for
 * concurrent use.</p>
 *
 * @param <M> the message type used by the handling pipeline
 */
public interface HandlerMethodPlan<M> extends HandlerDescriptor {

    /**
     * Invokes the selected handler method using the supplied input.
     *
     * @param input the current input; it must satisfy the cache conditions under which this plan was prepared
     * @return the handler method's result
     */
    Object invoke(HandlerInput<M> input);
}
