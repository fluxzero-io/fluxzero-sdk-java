/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;

import java.util.Objects;

/** One current, state, commit or event boundary shared by model replay, graphs and ancestor lookup. */
public record ModelReadBoundary(
        Long stateIndex, String commitId, Integer substep, Long eventIndex,
        boolean before, boolean includeMessageBatch) {

    public static final ModelReadBoundary CURRENT =
            new ModelReadBoundary(null, null, null, null, false, true);

    public ModelReadBoundary {
        int specified = (commitId == null ? 0 : 1) + (eventIndex == null ? 0 : 1);
        if (specified > 1) {
            throw new IllegalArgumentException("Specify one model state, commit, or event boundary");
        }
        if ((commitId == null) != (substep == null)
            || commitId != null && (commitId.isBlank() || substep < 0)) {
            throw new IllegalArgumentException(
                    "Model commit boundary requires a non-blank commitId and non-negative substep");
        }
        if (stateIndex != null && stateIndex < -1L) {
            throw new IllegalArgumentException("Model stateIndex must be at least -1");
        }
        if (eventIndex != null && eventIndex < 0L) {
            throw new IllegalArgumentException("Model eventIndex must be non-negative");
        }
        if (includeMessageBatch && (before || commitId != null || eventIndex != null)) {
            throw new IllegalArgumentException(
                    "Before, commit and event boundaries cannot include pending message-batch state");
        }
    }

    public static ModelReadBoundary current() {
        return CURRENT;
    }

    public static ModelReadBoundary at(Long stateIndex) {
        return stateIndex == null ? CURRENT : state(stateIndex, false);
    }

    public static ModelReadBoundary state(long stateIndex, boolean includeMessageBatch) {
        return new ModelReadBoundary(stateIndex, null, null, null, false, includeMessageBatch);
    }

    public static ModelReadBoundary commit(String commitId, int substep) {
        return new ModelReadBoundary(null, commitId, substep, null, false, false);
    }

    public static ModelReadBoundary event(long eventIndex) {
        return new ModelReadBoundary(null, null, null, eventIndex, false, false);
    }

    public ModelReadBoundary asBefore() {
        return before ? this : new ModelReadBoundary(
                stateIndex, commitId, substep, eventIndex, true, false);
    }

    public boolean historical() {
        return stateIndex != null || commitId != null || eventIndex != null;
    }

    /** State selector sent to storage; opaque commit/event selectors keep their resolved state only as a fallback. */
    public Long requestStateIndex() {
        return commitId == null && eventIndex == null ? stateIndex : null;
    }

    /** Captures an exact handler/graph boundary without retaining message context in a graph object. */
    public static ModelReadBoundary forGraph(long stateIndex, boolean exact, boolean historical) {
        if (!exact) {
            return current();
        }
        DeserializingMessage message = DeserializingMessage.getCurrent();
        if (message != null && (message.getMessageType() == MessageType.EVENT
                                || message.getMessageType() == MessageType.NOTIFICATION)) {
            Object commit = message.getMetadata().get(ModelEventMetadata.COMMIT_ID);
            Object step = message.getMetadata().get(ModelEventMetadata.SUBSTEP);
            if (commit instanceof String id && !id.isBlank() && step != null) {
                return new ModelReadBoundary(
                        stateIndex, id, parseSubstep(step), null, false, false);
            }
        }
        return state(stateIndex, !historical);
    }

    public Graph<?> loadGraph(
            ModelRepository repository, String rootId, Class<?> rootType, boolean historical) {
        if (commitId != null) {
            return before
                    ? repository.loadGraphBeforeCommit(
                            rootId, rootType, Objects.requireNonNullElse(stateIndex, -1L),
                            commitId, substep, Graph.Options.DEFAULT)
                    : repository.loadGraphAtCommit(
                            rootId, rootType, Objects.requireNonNullElse(stateIndex, -1L),
                            commitId, substep, Graph.Options.DEFAULT);
        }
        if (eventIndex != null) {
            return before
                    ? repository.loadGraphBeforeEvent(
                            rootId, rootType, Objects.requireNonNullElse(stateIndex, -1L),
                            eventIndex, Graph.Options.DEFAULT)
                    : repository.loadGraphAtEvent(
                            rootId, rootType, Objects.requireNonNullElse(stateIndex, -1L),
                            eventIndex, Graph.Options.DEFAULT);
        }
        if (before) {
            return repository.loadGraphBefore(rootId, rootType, stateIndex, Graph.Options.DEFAULT);
        }
        return historical || stateIndex != null
                ? repository.loadGraphAt(rootId, rootType, stateIndex, Graph.Options.DEFAULT)
                : repository.loadGraph(rootId, rootType, Graph.Options.DEFAULT);
    }

    private static int parseSubstep(Object value) {
        int result = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        if (result < 0) {
            throw new IllegalArgumentException("Model event commit substep must be non-negative");
        }
        return result;
    }

    /** Pins an opaque commit/event selector to the state returned by its first repository request. */
    public static final class Pinned {
        private final ModelReadBoundary source;
        private Long resolvedStateIndex;

        public Pinned(ModelReadBoundary source) {
            if (source.commitId == null && source.eventIndex == null) {
                throw new IllegalArgumentException("Only commit or event boundaries require pinning");
            }
            this.source = source;
        }

        public synchronized ModelReadBoundary request() {
            return resolvedStateIndex == null ? source : state(resolvedStateIndex, false);
        }

        public synchronized void pin(long value) {
            if (resolvedStateIndex != null && resolvedStateIndex != value) {
                throw new EventSourcingException(
                        "Published model boundary %s resolved to both state %d and %d"
                                .formatted(source.description(), resolvedStateIndex, value));
            }
            resolvedStateIndex = value;
        }
    }

    private String description() {
        return commitId == null ? "event %d".formatted(eventIndex)
                : "commit %s substep %d".formatted(commitId, substep);
    }
}
