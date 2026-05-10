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

package io.fluxzero.sdk.web;

import java.util.List;

/**
 * Generic API documentation model extracted from Fluxzero web handlers.
 * <p>
 * This model is intentionally independent of OpenAPI. Renderers can transform it into OpenAPI or another
 * documentation format.
 * </p>
 */
public record ApiDocCatalog(List<ApiDocEndpoint> endpoints) {
    public ApiDocCatalog {
        endpoints = List.copyOf(endpoints);
    }
}
