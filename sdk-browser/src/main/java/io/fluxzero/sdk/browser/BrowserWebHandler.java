/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.fluxzero.sdk.browser;

/**
 * Generated browser web route invocation target.
 */
@FunctionalInterface
public interface BrowserWebHandler {

    BrowserWebExchange handle(BrowserWebExchange exchange);
}
