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

import java.util.Iterator;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Owns the shared default inherited by {@link IdentityProvider}.
 *
 * <p>Keep this interface free of default methods: the JVM must not initialize provider discovery merely because
 * an implementation is being initialized. Otherwise concurrent first use of the default and an implementation
 * can wait on each other's class-initialization monitors. Discovery remains eager on access to the field and
 * uses that thread's context class loader. Constructing an implementation alone does not trigger discovery.</p>
 *
 * @see IdentityProvider
 */
public interface IdentityProviderDefaults {

    /**
     * The default identity provider, resolved using {@link ServiceLoader}, or falling back to {@link UuidFactory}.
     */
    IdentityProvider defaultIdentityProvider = Optional.of(ServiceLoader.load(IdentityProvider.class))
            .map(ServiceLoader::iterator)
            .filter(Iterator::hasNext)
            .map(Iterator::next)
            .orElseGet(UuidFactory::new);

}
