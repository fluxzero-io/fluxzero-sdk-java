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

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.api.Data;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.TypeInspection;
import io.fluxzero.sdk.common.serialization.UnknownSerializedTypeException;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import lombok.Getter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Actionable Model read failure. Context retains identifiers and type metadata, never payloads or message metadata.
 * The original failure remains the cause. A classification describes the observed failing phase, not a proof that
 * all other contracts or application logic are correct. Existing EventSourcingException catch blocks remain valid.
 */
@Getter
public class ModelReadException extends EventSourcingException {
    /** Stable documentation entry for the recovery choices represented by this exception. */
    public static final String DOCUMENTATION = "https://github.com/fluxzero-io/fluxzero-sdk-java/blob/main/"
            + "docs/developer/guides/Modeling%20%26%20persistence/208-model-contract-diagnostics.mdx";

    /** The observed cause category. */
    public enum Kind {
        /** The logical Model contract is absent from the application's catalog. */ MISSING_MODEL_CONTRACT,
        /** The serialized identifier is still unknown after upcasting. */ MISSING_SERIALIZED_TYPE,
        /** A known event has no local replay handler for the selected Model. */ MISSING_REPLAY_HANDLER,
        /** Malformed serialized syntax or invalid structural storage metadata. */ INVALID_DATA,
        /** Local catalog construction or configuration failed. */ CATALOG_FAILURE,
        /** Application replay/upcaster code failed. */ APPLICATION_FAILURE,
        /** Decoding failed, without enough evidence to attribute it to corrupt data or application code. */ DECODING_FAILURE
    }

    /** The operation being attempted when the failure was observed. */
    public enum Operation { RESOLVE_MODEL_TYPE, READ_EVENT, APPLY_EVENT, READ_DOCUMENT, READ_SNAPSHOT, INSPECT_CONTRACTS }

    /**
     * Safe structural context; null means unavailable, not absent data.
     * @param operation failing phase
     * @param modelId requested or resolved Model identity
     * @param rootId Graph read root when known
     * @param modelType stored logical Model type when known
     * @param javaModelType known local Model class name when available
     * @param serializedType original serialized type identifier
     * @param serializedRevision original serialized revision
     * @param registration local identifier lookup; never a claim about an upcaster chain
     * @param stateIndex failing event membership boundary, when known
     * @param unresolvedType identifier that remained unknown after upcasting or within a nested state envelope
     * @param unresolvedRevision revision of that unresolved representation
     */
    public record Context(Operation operation, String modelId, String rootId, String modelType,
                          String javaModelType, String serializedType, Integer serializedRevision,
                          TypeInspection registration, Long stateIndex, String unresolvedType, Integer unresolvedRevision) { }

    private final Kind kind;
    private final Context context;

    /** Creates a contextual failure with the original cause and without copying payload contents. */
    public ModelReadException(Kind kind, Context context, Throwable cause) {
        super("Model read failed [" + kind + "] during " + context.operation()
                      + "; model=" + safe(context.modelId()) + "; root=" + safe(context.rootId())
                      + "; modelType=" + safe(context.modelType()) + "; localModel=" + safe(context.javaModelType())
                      + "; serializedType=" + safe(context.serializedType()) + "; revision=" + context.serializedRevision()
                      + "; unresolvedType=" + safe(context.unresolvedType()) + "; unresolvedRevision=" + context.unresolvedRevision()
                      + "; stateIndex=" + context.stateIndex() + "; registration=" + (context.registration() == null ? TypeInspection.Status.UNAVAILABLE : context.registration().status()) + ". " + recovery(kind)
                      + " See " + DOCUMENTATION, cause);
        this.kind = kind;
        this.context = context;
    }

    ModelReadException withRoot(String rootId) {
        return rootId == null || context.rootId() != null ? this : new ModelReadException(kind,
                new Context(context.operation(), context.modelId(), rootId, context.modelType(), context.javaModelType(),
                            context.serializedType(), context.serializedRevision(), context.registration(), context.stateIndex(), context.unresolvedType(), context.unresolvedRevision()), this);
    }

    ModelReadException atState(long stateIndex) {
        return context.stateIndex() != null ? this : new ModelReadException(kind,
                new Context(context.operation(), context.modelId(), context.rootId(), context.modelType(),
                            context.javaModelType(), context.serializedType(), context.serializedRevision(),
                            context.registration(), stateIndex, context.unresolvedType(), context.unresolvedRevision()), this);
    }

    private ModelReadException withStoredContext(String id, String logicalType, Data<?> data, Serializer serializer,
                                                 Long stateIndex) {
        if (!Objects.equals(id, context.modelId())
            || context.stateIndex() != null && stateIndex != null && !context.stateIndex().equals(stateIndex)) {
            return this;
        }
        Long effectiveStateIndex = context.stateIndex() == null ? stateIndex : context.stateIndex();
        String logical = context.modelType() == null ? logicalType : context.modelType();
        String type = data == null ? context.serializedType() : data.getType();
        Integer revision = data == null ? context.serializedRevision() : data.getRevision();
        if (Objects.equals(logical, context.modelType())
            && Objects.equals(type, context.serializedType())
            && Objects.equals(revision, context.serializedRevision())
            && Objects.equals(effectiveStateIndex, context.stateIndex())) { return this; }
        return new ModelReadException(kind, new Context(context.operation(), id, context.rootId(), logical,
                context.javaModelType(), type, revision, data == null ? context.registration() : inspect(data, serializer),
                effectiveStateIndex, context.unresolvedType(), context.unresolvedRevision()), this);
    }

    static ModelReadException failure(Kind fallback, Operation operation, String id, String logicalType,
                                       Class<?> javaType, Data<?> data, Serializer serializer, Throwable cause) {
        return failure(fallback, operation, id, logicalType, javaType, data, serializer, cause, null);
    }

    static ModelReadException failure(Kind fallback, Operation operation, String id, String logicalType,
                                      Class<?> javaType, Data<?> data, Serializer serializer, Throwable cause,
                                      Long stateIndex) {
        Kind kind = fallback;
        UnknownSerializedTypeException unknown = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = cause; current != null && seen.add(current) && seen.size() <= 32;
             current = current.getCause()) {
            if (current instanceof ModelReadException contextual) {
                // Keep an application wrapper and its suppressed/context information in the original cause chain.
                ModelReadException result = current == cause ? contextual
                        : new ModelReadException(contextual.kind, contextual.context, cause);
                return result.withStoredContext(id, logicalType, operation == Operation.READ_EVENT ? data : null, serializer, stateIndex);
            }
            if (operation == Operation.APPLY_EVENT) { continue; }
            if (current instanceof UnknownSerializedTypeException missing) {
                kind = Kind.MISSING_SERIALIZED_TYPE;
                unknown = missing;
                break;
            }
            if (current instanceof StreamReadException) {
                kind = malformedStoredJson(data, serializer) ? Kind.INVALID_DATA : Kind.DECODING_FAILURE;
                break;
            }
            if (current instanceof DeserializationException && fallback == Kind.APPLICATION_FAILURE) {
                kind = Kind.DECODING_FAILURE;
            }
        }
        return new ModelReadException(kind, new Context(operation, id, null, logicalType,
                javaType == null ? null : javaType.getName(), data == null ? null : data.getType(),
                data == null ? null : data.getRevision(), inspect(data, serializer), stateIndex,
                unknown == null ? null : unknown.getSerializedType(), unknown == null ? null : unknown.getRevision()), cause);
    }

    private static TypeInspection inspect(Data<?> data, Serializer serializer) {
        if (data != null && serializer != null) {
            try {
                TypeInspection reported = serializer.inspectType(data.getType());
                if (reported != null) { return reported; }
            } catch (RuntimeException | LinkageError ignored) { /* Retain the original failure. */ }
        }
        return new TypeInspection(TypeInspection.Status.UNAVAILABLE, data == null ? null : data.getType(), null);
    }

    private static boolean malformedStoredJson(Data<?> data, Serializer serializer) {
        // A parser exception may originate inside application upcasters/deserializers. Only attribute it to
        // stored syntax after a bounded, payload-only parse; never run application code or allocate a JSON tree.
        if (data == null || !"application/json".equals(data.getFormat())
            || serializer == null || serializer.getClass() != JacksonSerializer.class) { return false; }
        var mapper = ((JacksonSerializer) serializer).getObjectMapper();
        if (mapper.getClass() != JsonMapper.class) { return false; }
        try {
            if (!(data.getValue() instanceof byte[] bytes) || bytes.length > 1024 * 1024) { return false; }
            try (var parser = mapper.createParser(bytes)) {
                while (parser.nextToken() != null) { }
            } catch (StreamReadException malformed) {
                return true;
            }
        } catch (Exception ignored) { /* Missing evidence must not mask the original failure. */ }
        return false;
    }

    private static String recovery(Kind kind) {
        return switch (kind) {
            case MISSING_MODEL_CONTRACT -> "Include the shared @Model contract in the local catalog, or scope the read.";
            case MISSING_SERIALIZED_TYPE -> "Provide the historical event/state contract or its upcaster; current-state reads require a maintained document.";
            case MISSING_REPLAY_HANDLER -> "Provide the Model replay handler for this event and qualify historical replay; do not skip it implicitly.";
            case INVALID_DATA -> "Inspect the stored envelope and repair or restore it before retrying; do not silently skip it.";
            case CATALOG_FAILURE -> "Check local Model discovery, duplicate names and configuration; preserve the original cause.";
            case APPLICATION_FAILURE -> "Fix the failing replay/upcaster code and qualify a cold replay against representative history.";
            case DECODING_FAILURE -> "Check the original decoding cause, format, revision and upcasters; type registration alone is insufficient.";
        };
    }

    private static String safe(String value) {
        if (value == null) { return "<unavailable>"; }
        String result = value.substring(0, Math.min(value.length(), 256)).replaceAll("[\\p{Cntrl}]", "?");
        return value.length() > 256 ? result + "..." : result;
    }
}
