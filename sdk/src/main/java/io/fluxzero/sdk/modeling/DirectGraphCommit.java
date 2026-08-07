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

import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;

/** Internal bridge that submits explicitly committed graph changes to the regular model commit engine. */
final class DirectGraphCommit {
    private final Graph<?> staged;
    private boolean evaluated;

    DirectGraphCommit(Graph<?> staged) {
        this.staged = staged;
    }

    @InterceptApply
    synchronized Object intercept() {
        if (!evaluated) {
            evaluated = true;
            return staged;
        }
        var refreshed = Graphs.refreshStaged(staged);
        return refreshed.size() == 1 ? refreshed.getFirst() : refreshed;
    }
}
