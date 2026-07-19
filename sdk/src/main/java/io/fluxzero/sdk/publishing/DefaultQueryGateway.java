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

import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.Namespaced;
import lombok.AllArgsConstructor;
import lombok.With;
import lombok.experimental.Delegate;

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
    protected QueryGateway createForNamespace(String namespace) {
        GenericGateway namespacedDelegate = delegate.forNamespace(namespace);
        return namespacedDelegate == delegate ? this : new DefaultQueryGateway(namespacedDelegate);
    }

    @Override
    public DefaultQueryGateway forNamespace(String namespace) {
        return (DefaultQueryGateway) super.forNamespace(namespace);
    }
}
