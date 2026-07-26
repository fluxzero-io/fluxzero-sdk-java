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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.ModelGraphEdge;

import java.util.List;
import java.util.Map;

/**
 * Independently reconstructed model nodes composed through explicit relationship paths.
 * <p>
 * A node can occur below several parents because the relationship graph is a DAG. The underlying model remains one
 * independently loaded {@link Entity}; graph composition does not merge its stream into a root stream.
 *
 * @param stateIndex pinned state boundary shared by every node and edge
 * @param root composed root node
 * @param models all independently reconstructed models keyed by exact model ID
 * @param edges temporal edges selected at {@code stateIndex}
 */
public record ModelGraph<T>(
        long stateIndex,
        Node<T> root,
        Map<String, Entity<?>> models,
        List<ModelGraphEdge> edges) {

    public ModelGraph {
        models = Map.copyOf(models);
        edges = List.copyOf(edges);
    }

    /**
     * One independently reconstructed model with children grouped by their explicit graph path.
     */
    public record Node<T>(
            Entity<T> model,
            Map<String, List<Node<?>>> children) {

        public Node {
            children = Map.copyOf(children);
        }

        /**
         * Returns children placed at the requested explicit path.
         */
        public List<Node<?>> children(String path) {
            return children.getOrDefault(path, List.of());
        }
    }

    /**
     * Safety limits for graph reconstruction.
     *
     * @param maxDepth maximum number of child levels below the root
     * @param maxModels maximum number of distinct independently stored models
     */
    public record Options(int maxDepth, int maxModels) {
        public static final Options DEFAULT = new Options(64, 10_000);

        public Options {
            if (maxDepth < 0 || maxDepth > 1_024) {
                throw new IllegalArgumentException(
                        "Model graph maxDepth must be between 0 and 1024");
            }
            if (maxModels < 1 || maxModels > 100_000) {
                throw new IllegalArgumentException(
                        "Model graph maxModels must be between 1 and 100000");
            }
        }
    }
}
