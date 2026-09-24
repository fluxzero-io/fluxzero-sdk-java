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

package io.fluxzero.common.api.modeling;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fluxzero.common.api.search.ModelGraphComposition;
import lombok.Value;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable definition of one asynchronous materialized model-graph document.
 * <p>
 * The target collection is the projection identity within a namespace. It is intentionally distinct from the root's
 * current-document source so asynchronous graph writes cannot weaken synchronous current-state consistency.
 */
@Value
public class ModelGraphProjectionConfiguration {

    /**
     * Stable application-scoped logical Model name of projection roots.
     */
    String rootModelType;

    /**
     * Collection containing the synchronously maintained root document. This can be either a public direct-model
     * collection or a private type-isolated graph-component collection.
     */
    String rootCollection;

    /**
     * Distinct collection receiving asynchronously composed graph documents.
     */
    String collection;

    /**
     * Explicit traversal and output bounds.
     */
    ModelGraphComposition composition;

    /**
     * Logical Model names that can occur in this projection and the revision of their direct document schema.
     * <p>
     * The ordered list is part of the durable projection definition. Changing any participating model revision
     * therefore advances the Runtime's existing configuration fence and triggers a complete rebuild.
     */
    List<ModelRevision> modelRevisions;

    /**
     * Optional projection-local canonical path replacements.
     */
    List<ModelGraphPathOverride> pathOverrides;

    @JsonCreator
    public ModelGraphProjectionConfiguration(
            @JsonProperty("rootModelType")
            String rootModelType,
            @JsonProperty("rootCollection")
            String rootCollection,
            @JsonProperty("collection")
            String collection,
            @JsonProperty("composition")
            ModelGraphComposition composition,
            @JsonProperty("modelRevisions")
            List<ModelRevision> modelRevisions,
            @JsonProperty("pathOverrides")
            List<ModelGraphPathOverride> pathOverrides) {
        this.rootModelType = requireText(
                rootModelType, "Root model type");
        this.rootCollection = requireText(
                rootCollection, "Root model collection");
        this.collection = requireText(
                collection, "Graph projection collection");
        if (this.rootCollection.equals(this.collection)) {
            throw new IllegalArgumentException(
                    "Graph projection collection must differ from the current root collection");
        }
        this.composition = Objects.requireNonNull(
                composition, "Model graph composition");
        this.modelRevisions = Objects.requireNonNull(
                        modelRevisions, "Model graph revisions")
                .stream()
                .sorted(Comparator.comparing(ModelRevision::modelType))
                .toList();
        if (this.modelRevisions.isEmpty()
            || this.modelRevisions.stream()
                       .noneMatch(revision -> this.rootModelType.equals(
                               revision.modelType()))) {
            throw new IllegalArgumentException(
                    "Model graph revisions must include the root model type "
                    + this.rootModelType);
        }
        if (this.modelRevisions.stream()
                    .map(ModelRevision::modelType)
                    .distinct().count()
            != this.modelRevisions.size()) {
            throw new IllegalArgumentException(
                    "Model graph revisions must contain unique model types");
        }
        this.pathOverrides = pathOverrides == null
                ? List.of() : List.copyOf(pathOverrides);
        Map<String, String> unique = new LinkedHashMap<>();
        Map<String, String> uniqueTargets =
                new LinkedHashMap<>();
        for (ModelGraphPathOverride override : this.pathOverrides) {
            String previous = unique.putIfAbsent(
                    override.getPath(),
                    override.getProjectionPath());
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate graph path override for "
                        + override.getPath());
            }
            String previousSource =
                    uniqueTargets.putIfAbsent(
                            override.getProjectionPath(),
                            override.getPath());
            if (previousSource != null) {
                throw new IllegalArgumentException(
                        "Graph paths '%s' and '%s' both project to '%s'"
                                .formatted(
                                        previousSource,
                                        override.getPath(),
                                        override.getProjectionPath()));
            }
        }
    }

    private static String requireText(String value, String description) {
        String result = Objects.requireNonNull(value, description).trim();
        if (result.isEmpty()
            || !result.equals(value)) {
            throw new IllegalArgumentException(
                    description
                    + " must not be blank or have surrounding whitespace");
        }
        return result;
    }

    /** One independently evolvable direct-document schema participating in a materialized Graph. */
    public record ModelRevision(
            @JsonProperty("modelType") String modelType,
            @JsonProperty("revision") int revision) {
        public ModelRevision {
            modelType = requireText(modelType, "Graph model type");
        }
    }
}
