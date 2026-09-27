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
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.Namespaced;
import io.fluxzero.sdk.tracking.handling.Request;
import lombok.AllArgsConstructor;
import lombok.With;
import lombok.experimental.Delegate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of the {@link QueryGateway} interface.
 * <p>
 * This class delegates all operations defined in the {@link QueryGateway} interface to an underlying
 * {@link GenericGateway} instance.
 *
 * @see QueryGateway
 * @see GenericGateway
 */
@AllArgsConstructor
public class DefaultQueryGateway extends AbstractNamespaced<QueryGateway> implements QueryGateway {
    @Delegate(excludes = Namespaced.class)
    @With
    private final GenericGateway delegate;

    @Override
    public <R> CompletableFuture<R> send(Object payload, Metadata metadata, Guarantee guarantee) {
        return guarantee == Guarantee.DEFAULT ? send(payload, metadata)
                : delegate.send(payload, metadata, guarantee);
    }

    @Override
    public <R> CompletableFuture<R> send(Request<R> payload, Metadata metadata, Guarantee guarantee) {
        return guarantee == Guarantee.DEFAULT ? send(payload, metadata)
                : delegate.send(payload, metadata, guarantee);
    }

    @Override
    public <R> R sendAndWait(Object payload, Metadata metadata, Guarantee guarantee) {
        return guarantee == Guarantee.DEFAULT ? sendAndWait(payload, metadata)
                : delegate.sendAndWait(payload, metadata, guarantee);
    }

    @Override
    public <R> R sendAndWait(Request<R> payload, Metadata metadata, Guarantee guarantee) {
        return guarantee == Guarantee.DEFAULT ? sendAndWait(payload, metadata)
                : delegate.sendAndWait(payload, metadata, guarantee);
    }

    @Override
    public List<CompletableFuture<Message>> sendForMessages(Guarantee guarantee, Message... messages) {
        return guarantee == Guarantee.DEFAULT ? sendForMessages(messages) : delegate.sendForMessages(guarantee, messages);
    }

    @Override
    protected QueryGateway createForNamespace(String namespace) {
        GenericGateway namespacedDelegate = delegate.forNamespace(namespace);
        return namespacedDelegate == delegate ? this : new DefaultQueryGateway(namespacedDelegate);
    }

    @Override
    public DefaultQueryGateway forNamespace(String namespace) {
        return (DefaultQueryGateway) super.forNamespace(namespace);
    }
}
