/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.With;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.MessageType.RESULT;
import static io.fluxzero.common.reflection.ReflectionUtils.ifClass;

/**
 * Default implementation of the {@link ResultGateway} interface for sending response messages.
 * <p>
 * This class is responsible for handling responses to commands, queries, dispatching the result message to the
 * specified target using a {@link GatewayClient}.
 * <p>
 * The dispatch process utilizes the {@link DispatchInterceptor} and {@link ResponseMapper} to modify or monitor
 * messages before they are sent.
 *
 * @see ResultGateway
 */
@AllArgsConstructor
public class DefaultResultGateway extends AbstractNamespaced<ResultGateway> implements ResultGateway {

    @With
    private final Client client;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final ResponseMapper responseMapper;

    @Getter(lazy = true)
    private final GatewayClient gatewayClient = client.getGatewayClient(RESULT);

    @Override
    protected ResultGateway createForNamespace(String namespace) {
        return withClient(client.forNamespace(namespace));
    }

    @Override
    public CompletableFuture<Void> respond(Object payload, Metadata metadata, String target, Integer requestId,
                                           Guarantee guarantee) {
        try {
            SerializedMessage serializedMessage = interceptDispatch(payload, metadata);
            if (serializedMessage == null) {
                return CompletableFuture.completedFuture(null);
            }
            serializedMessage.setTarget(target);
            serializedMessage.setRequestId(requestId);
            return getGatewayClient().append(guarantee, serializedMessage);
        } catch (Exception e) {
            throw new GatewayException(String.format("Failed to send response %s",
                                                     payload != null && ifClass(payload) == null
                                                             ? payload.getClass() : Objects.toString(payload)), e);
        }
    }

    protected SerializedMessage interceptDispatch(Object payload, Metadata metadata) {
        Message message = dispatchInterceptor.interceptDispatch(responseMapper.map(payload, metadata), RESULT, null);
        SerializedMessage serializedMessage = message == null ? null
                : dispatchInterceptor.modifySerializedMessage(message.serialize(serializer), message, RESULT, null);
        if (serializedMessage != null) {
            dispatchInterceptor.monitorDispatch(message, RESULT, null, client.namespace());
        }
        return serializedMessage;
    }
}
