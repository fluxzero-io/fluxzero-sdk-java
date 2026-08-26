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

package io.fluxzero.sdk.test;

import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.publishing.WebRequestGateway;
import lombok.experimental.Delegate;

final class FixtureFluxzero implements Fluxzero {

    @Delegate
    private final Fluxzero delegate;
    private final WebRequestGateway webRequestGateway;

    FixtureFluxzero(Fluxzero delegate) {
        this.delegate = delegate;
        this.webRequestGateway = new FixtureWebRequestGateway(delegate.webRequestGateway());
    }

    @Override
    public WebRequestGateway webRequestGateway() {
        return webRequestGateway;
    }

    Fluxzero delegate() {
        return delegate;
    }

    @Override
    public <R> R apply(ThrowingFunction<Fluxzero, R> function) {
        return Fluxzero.super.apply(function);
    }

    @Override
    public void execute(ThrowingConsumer<Fluxzero> task) {
        Fluxzero.super.execute(task);
    }
}
