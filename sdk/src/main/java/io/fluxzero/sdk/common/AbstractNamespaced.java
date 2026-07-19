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

package io.fluxzero.sdk.common;

import io.fluxzero.common.MemoizingFunction;
import io.fluxzero.common.ObjectUtils;

import static io.fluxzero.sdk.common.ClientUtils.memoize;

public abstract class AbstractNamespaced<T> implements Namespaced<T> {

    private volatile AbstractNamespaced<T> root = this;
    private volatile MemoizingFunction<String, T> namespaceSwitcher = memoize(this::createAndAttach);

    protected abstract T createForNamespace(String namespace);

    @Override
    public T forNamespace(String namespace) {
        return root.namespaceSwitcher.apply(namespace);
    }

    @SuppressWarnings("unchecked")
    private T createAndAttach(String namespace) {
        T resource = createForNamespace(namespace);
        if (resource instanceof AbstractNamespaced<?> namespaced) {
            ((AbstractNamespaced<T>) namespaced).attachTo(root);
        }
        return resource;
    }

    private void attachTo(AbstractNamespaced<T> root) {
        this.root = root;
        this.namespaceSwitcher = root.namespaceSwitcher;
    }

    protected void close() {
        if (root != this) {
            return;
        }
        namespaceSwitcher.forEach(resource -> {
            if (resource != this && resource instanceof AutoCloseable c) {
                ObjectUtils.tryRun(ObjectUtils.asRunnable(c::close));
            }
        });
    }

}
