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

package io.fluxzero.common.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.serialization.JsonUtils;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Optional;

/**
 * Hidden structural description carried by a composed independent-model graph document.
 * <p>
 * The visible document remains ordinary nested JSON. This compact, dictionary-encoded manifest preserves exact node
 * identities, concrete types, revisions and placements so SDK clients can reconstruct and independently upcast a
 * typed lazy graph without guessing schema information from JSON paths. Parent nodes always precede their children.
 */
public record ModelGraphDocumentManifest(
        @JsonProperty("v") int version,
        @JsonProperty("s") long stateIndex,
        @JsonProperty("t") List<String> types,
        @JsonProperty("r") List<String> relationshipPaths,
        @JsonProperty("n") List<Node> nodes) {

    /** Current manifest format version. */
    public static final int CURRENT_VERSION = 2;

    /** Reserved document-metadata key containing the manifest. */
    public static final String METADATA_KEY = "$fluxzeroModelGraph";

    /** Internal facet marker that lets document trackers avoid inspecting ordinary document payloads. */
    public static final String FACET_NAME = "$fluxzeroModelGraph";

    /** Reserved metadata marker for a deleted materialized graph root. */
    public static final String TOMBSTONE_METADATA_KEY = "$fluxzeroModelGraphTombstone";

    /** Reserved tombstone metadata containing the last materialized state boundary before deletion. */
    public static final String PREVIOUS_STATE_INDEX_METADATA_KEY = "$fluxzeroModelGraphPreviousStateIndex";

    public ModelGraphDocumentManifest(
            long stateIndex,
            List<String> types,
            List<String> relationshipPaths,
            List<Node> nodes) {
        this(CURRENT_VERSION, stateIndex, types,
             relationshipPaths, nodes);
    }

    public ModelGraphDocumentManifest {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported model graph manifest version " + version);
        }
        types = List.copyOf(types);
        relationshipPaths = List.copyOf(relationshipPaths);
        nodes = List.copyOf(nodes);
        if (types.stream().anyMatch(type -> type == null || type.isBlank())
            || types.stream().distinct().count() != types.size()) {
            throw new IllegalArgumentException(
                    "Model graph manifest types must be unique and non-blank");
        }
        if (relationshipPaths.stream().anyMatch(
                path -> path == null || path.isBlank())
            || relationshipPaths.stream().distinct().count()
               != relationshipPaths.size()) {
            throw new IllegalArgumentException(
                    "Model graph manifest relationship paths must be unique and non-blank");
        }
        if (nodes.isEmpty() || nodes.getFirst().parent() != -1
            || nodes.getFirst().relationshipPath() != -1
            || nodes.getFirst().ordinal() != 0) {
            throw new IllegalArgumentException(
                    "A model graph manifest must start with a root node");
        }
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            if (node.type() < 0 || node.type() >= types.size()) {
                throw new IllegalArgumentException(
                        "Invalid model graph manifest type index " + node.type());
            }
            if (node.parent() >= index) {
                throw new IllegalArgumentException(
                        "A model graph manifest parent must precede its child");
            }
            if (index > 0 && node.parent() < 0) {
                throw new IllegalArgumentException(
                        "A model graph manifest may contain only one root node");
            }
            if (node.parent() >= 0
                && (node.relationshipPath() < 0
                    || node.relationshipPath() >= relationshipPaths.size())) {
                throw new IllegalArgumentException(
                        "Invalid model graph manifest relationship path index "
                        + node.relationshipPath());
            }
        }
    }

    /** Returns the concrete class name referenced by a node. */
    public String type(Node node) {
        return types.get(node.type());
    }

    /** Returns the relationship path referenced by a non-root node. */
    public String relationshipPath(Node node) {
        return node.relationshipPath() < 0
                ? null : relationshipPaths.get(node.relationshipPath());
    }

    /** Serializes this manifest for hidden search-document metadata. */
    @SneakyThrows
    public String serialize() {
        return JsonUtils.writer.writeValueAsString(this);
    }

    /** Reads the manifest from a composed graph document when present. */
    public static Optional<ModelGraphDocumentManifest> from(
            SerializedDocument document) {
        return from(document.getMetadata());
    }

    /** Returns whether a document advertises the hidden typed-graph manifest without decoding its metadata. */
    public static boolean isGraphDocument(
            SerializedDocument document) {
        for (FacetEntry facet : document.getFacets()) {
            if (FACET_NAME.equals(facet.getName())
                && "1".equals(facet.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Reads the manifest from document metadata when present. */
    @SneakyThrows
    public static Optional<ModelGraphDocumentManifest> from(
            Metadata metadata) {
        String value = metadata == null ? null : metadata.get(METADATA_KEY);
        return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(JsonUtils.writer.readValue(
                        value, ModelGraphDocumentManifest.class));
    }

    /**
     * One concrete placement in pre-order. Type and relationship path are dictionary indexes; {@code parent} is the
     * parent node index or {@code -1} for the root. Revision belongs to this node's direct document. Ordinal is the
     * child's position among siblings at the same path.
     */
    public record Node(
            @JsonProperty("i") String id,
            @JsonProperty("t") int type,
            @JsonProperty("v") int revision,
            @JsonProperty("p") int parent,
            @JsonProperty("r") int relationshipPath,
            @JsonProperty("o") int ordinal) {
        public Node {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                        "A model graph manifest node requires an ID");
            }
            if (parent < -1) {
                throw new IllegalArgumentException(
                        "A model graph manifest parent must be -1 or a node index");
            }
            if (relationshipPath < -1) {
                throw new IllegalArgumentException(
                        "A model graph manifest relationship path must be -1 or a dictionary index");
            }
            if (ordinal < 0) {
                throw new IllegalArgumentException(
                        "A model graph manifest ordinal must be non-negative");
            }
        }
    }
}
