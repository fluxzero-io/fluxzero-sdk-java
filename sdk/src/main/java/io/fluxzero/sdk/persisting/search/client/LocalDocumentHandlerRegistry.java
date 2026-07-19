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

package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import lombok.AllArgsConstructor;
import lombok.experimental.Delegate;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.sdk.common.ClientUtils.isApplicationNamespace;
import static io.fluxzero.sdk.common.ClientUtils.setConsumerNamespace;

/**
 * A handler registry implementation intended for local testing and development that registers handlers for document
 * updates in a specific collection.
 *
 * @see InMemorySearchStore
 * @see HandlerRegistry
 */
@AllArgsConstructor
public class LocalDocumentHandlerRegistry extends AbstractNamespaced<HasLocalHandlers> implements HasLocalHandlers {
    private final Client client;
    @Delegate
    private final HandlerRegistry handlerRegistry;
    private final DispatchInterceptor dispatchInterceptor;
    private final Serializer serializer;
    private final AtomicBoolean initialized = new AtomicBoolean();

    @Override
    protected HasLocalHandlers createForNamespace(String namespace) {
        Client namespacedClient = client.forNamespace(namespace);
        if (namespacedClient == client) {
            return this;
        }
        LocalDocumentHandlerRegistry result = new LocalDocumentHandlerRegistry(
                namespacedClient, handlerRegistry, dispatchInterceptor, serializer);
        result.initializeMonitor();
        return result;
    }

    @Override
    public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
        initializeMonitor();
        return handlerRegistry.registerHandler(target, handlerFilter);
    }

    private void initializeMonitor() {
        if (initialized.compareAndSet(false, true)) {
            ((InMemorySearchStore) client.getSearchClient()).registerMonitor(
                    (collection, messages) -> serializer.deserializeMessages(
                            messages.stream(), MessageType.DOCUMENT, collection).forEach(message -> {
                        message = setConsumerNamespace(
                                message, isApplicationNamespace(client) ? null : client.namespace());
                        dispatchInterceptor.monitorDispatch(
                                message.toMessage(), MessageType.DOCUMENT, collection, client.namespace(), false);
                        handle(message);
                    }));
        }
    }
}
