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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.tracking.handling.LocalExecution;

import java.util.ArrayList;
import java.util.List;

/** Ordered, flat dispatch chain that can compile the same order into a local policy. */
final class CompositeDispatchInterceptor implements DispatchInterceptor {
    private final List<DispatchInterceptor> interceptors;
    private final List<DispatchInterceptor> localStateOwners;

    private CompositeDispatchInterceptor(List<DispatchInterceptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
        this.localStateOwners = interceptors.stream().filter(DispatchInterceptor::storesLocalDispatchState).toList();
    }

    static DispatchInterceptor combine(DispatchInterceptor first, DispatchInterceptor second) {
        List<DispatchInterceptor> result = new ArrayList<>();
        add(result, first);
        add(result, second);
        return new CompositeDispatchInterceptor(result);
    }

    private static void add(List<DispatchInterceptor> target, DispatchInterceptor interceptor) {
        if (interceptor instanceof CompositeDispatchInterceptor composite) {
            target.addAll(composite.interceptors);
        } else {
            target.add(interceptor);
        }
    }

    @Override
    public Message interceptDispatch(Message message, MessageType messageType, String topic) {
        return interceptDispatch(message, messageType, topic, null);
    }

    @Override
    public Message interceptDispatch(Message message, MessageType messageType, String topic, String namespace) {
        for (DispatchInterceptor interceptor : interceptors) {
            if (message == null) {
                return null;
            }
            message = interceptor.interceptDispatch(message, messageType, topic, namespace);
        }
        return message;
    }

    @Override
    public Message interceptLocalDispatch(Message message, MessageType messageType, String topic, String namespace) {
        for (DispatchInterceptor interceptor : interceptors) {
            if (message == null) {
                return null;
            }
            Message previous = message;
            try {
                message = interceptor.interceptLocalDispatch(message, messageType, topic, namespace);
            } catch (RuntimeException | Error e) {
                completeLocalDispatch(previous);
                throw e;
            }
            if (message != previous) {
                for (DispatchInterceptor stateOwner : localStateOwners) {
                    stateOwner.preserveLocalDispatchState(previous, message);
                }
            }
        }
        return message;
    }

    @Override
    public void beforeExternalDispatch(Message message, MessageType messageType, String topic) {
        for (DispatchInterceptor interceptor : interceptors) {
            interceptor.beforeExternalDispatch(message, messageType, topic);
        }
    }

    @Override
    public void completeLocalDispatch(Message message) {
        for (DispatchInterceptor stateOwner : localStateOwners) {
            stateOwner.completeLocalDispatch(message);
        }
    }

    @Override
    public boolean storesLocalDispatchState() {
        return !localStateOwners.isEmpty();
    }

    @Override
    public void preserveLocalDispatchState(Message previousMessage, Message replacement) {
        for (DispatchInterceptor stateOwner : localStateOwners) {
            stateOwner.preserveLocalDispatchState(previousMessage, replacement);
        }
    }

    @Override
    public void monitorDispatch(Message message, MessageType messageType, String topic, String namespace,
                                boolean request) {
        for (DispatchInterceptor interceptor : interceptors) {
            interceptor.monitorDispatch(message, messageType, topic, namespace, request);
        }
    }

    @Override
    public SerializedMessage modifySerializedMessage(SerializedMessage serialized, Message message,
                                                     MessageType messageType, String topic) {
        for (DispatchInterceptor interceptor : interceptors) {
            if (serialized == null) {
                return null;
            }
            serialized = interceptor.modifySerializedMessage(serialized, message, messageType, topic);
        }
        return serialized;
    }

    @Override
    public PreparedLocalDispatch prepareLocalDispatch(LocalDispatchDescriptor descriptor) {
        List<PreparedLocalDispatch> prepared = new ArrayList<>(interceptors.size());
        for (DispatchInterceptor interceptor : interceptors) {
            PreparedLocalDispatch policy = interceptor.prepareLocalDispatch(descriptor);
            if (policy == null) {
                return null;
            }
            if (policy != PreparedLocalDispatch.noOp) {
                prepared.add(policy);
            }
        }
        if (prepared.isEmpty()) {
            return PreparedLocalDispatch.noOp;
        }
        PreparedLocalDispatch[] preparePolicies = prepared.stream()
                .filter(PreparedLocalDispatch::requiresPreparation).toArray(PreparedLocalDispatch[]::new);
        PreparedLocalDispatch[] metadataPolicies = prepared.toArray(PreparedLocalDispatch[]::new);
        return new PreparedLocalDispatch() {
            @Override
            public boolean prepare(LocalExecution execution) {
                return switch (preparePolicies.length) {
                    case 0 -> true;
                    case 1 -> preparePolicies[0].prepare(execution);
                    case 2 -> preparePolicies[0].prepare(execution)
                              && preparePolicies[1].prepare(execution);
                    case 3 -> preparePolicies[0].prepare(execution)
                              && preparePolicies[1].prepare(execution)
                              && preparePolicies[2].prepare(execution);
                    case 4 -> preparePolicies[0].prepare(execution)
                              && preparePolicies[1].prepare(execution)
                              && preparePolicies[2].prepare(execution)
                              && preparePolicies[3].prepare(execution);
                    default -> prepareAll(preparePolicies, execution);
                };
            }

            @Override
            public Metadata interceptMetadata(Metadata metadata, LocalExecution execution) {
                for (PreparedLocalDispatch policy : metadataPolicies) {
                    metadata = policy.interceptMetadata(metadata, execution);
                }
                return metadata;
            }
        };
    }

    private static boolean prepareAll(PreparedLocalDispatch[] policies, LocalExecution execution) {
        for (PreparedLocalDispatch policy : policies) {
            if (!policy.prepare(execution)) {
                return false;
            }
        }
        return true;
    }
}
