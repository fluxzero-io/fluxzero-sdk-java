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

package io.fluxzero.sdk.modeling;

import java.util.List;

/** One property-qualified selection, retaining the distinction between matching and missing selectors. */
record ModelAliasSelection(MutationPlan.AliasTarget target, String owner, boolean requireResultMatch,
                           List<MutationPlan.AliasValue> foundSelectors) {
    boolean writable() { return target.access().writes(); }

    boolean accepts(Class<?> type) { return target.modelType().isAssignableFrom(type); }

    void validate(Object value) {
        if (value == null || !accepts(value.getClass())
            || target.values().stream().anyMatch(selector -> !selector.matches(value))) {
            throw new IllegalStateException("Apply result for " + target.modelType().getName()
                    + " must satisfy alias selectors " + target.values().stream()
                    .map(selector -> selector.property().property().name()).distinct().toList());
        }
    }
}
