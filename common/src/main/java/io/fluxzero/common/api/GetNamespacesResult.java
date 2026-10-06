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

package io.fluxzero.common.api;

import lombok.Value;

import java.util.List;

/** A sorted, duplicate-free snapshot of existing namespaces, including inactive namespaces. */
@Value
public class GetNamespacesResult extends AbstractRequestResult {
    /** Identifier of the originating request. */
    long requestId;
    /** Namespace names; discovering namespaces does not create them. */
    List<String> namespaces;
    /** Time this snapshot was produced. */
    long timestamp = System.currentTimeMillis();

    @Override
    public Object toMetric() {
        return new Metric(namespaces.size());
    }

    /** Result metric that omits namespace names. */
    @Value
    public static class Metric {
        /** Number of namespaces in the snapshot. */
        int namespaceCount;
    }
}
