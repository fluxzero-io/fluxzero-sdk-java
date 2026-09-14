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

package io.fluxzero.sdk.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.function.UnaryOperator;

/** Internal ownership extraction; structural metadata is owned by ReflectionUtils. */
final class ScheduleParents {
    static final String METADATA_KEY = "$scheduleParents";
    static final String BINDINGS_KEY = "$scheduleParentBindings";
    static final String NAMESPACE_KEY = "$scheduleParentNamespace";

    static boolean hasOwningDeclarations(Message message) {
        return EntityMetadata.scheduleParentReferences(message.getPayloadClass()).stream()
                .anyMatch(EntityMetadata.ParentReference::deleteOnParentDeletion);
    }

    static Metadata bind(Metadata metadata, String namespace, Map<String, Long> parents) {
        if (Objects.equals(namespace, metadata.get(NAMESPACE_KEY)) && metadata.containsKey(BINDINGS_KEY)) {
            Map<String, Long> previous = metadata.get(BINDINGS_KEY, new TypeReference<>() {});
            for (var parent : parents.entrySet()) {
                if (previous.containsKey(parent.getKey()) && !Objects.equals(previous.get(parent.getKey()), parent.getValue())) {
                    throw new IllegalStateException("Schedule parent lifetime has changed; the continuation was not accepted");
                }
            }
        }
        return metadata.with(BINDINGS_KEY, parents).with(NAMESPACE_KEY, namespace);
    }

    static Schedule inherit(Schedule next, Metadata previous) {
        if (next.getMetadata().containsKey(METADATA_KEY)) {
            return next; // withParents deliberately selects a new lifetime (or opts out).
        }
        Metadata result = next.getMetadata();
        for (String key : List.of(METADATA_KEY, BINDINGS_KEY, NAMESPACE_KEY)) {
            if (!result.containsKey(key) && previous.containsKey(key)) {
                result = result.with(key, previous.get(key));
            }
        }
        return next.withMetadata(result);
    }

    static List<String> resolve(Message message, MessageType messageType, Serializer serializer,
                                UnaryOperator<DeserializingMessage> parentDataRestoration) {
        if (message.getMetadata().containsKey(METADATA_KEY)) {
            return List.copyOf(Arrays.asList(message.getMetadata().get(METADATA_KEY, String[].class)));
        }
        var references = EntityMetadata.scheduleParentReferences(message.getPayloadClass());
        if (references.isEmpty()) {
            return List.of();
        }
        if (message.getMetadata().containsKey(DataProtectionInterceptor.METADATA_KEY)) {
            Map<String, String> protectedFields = message.getMetadata().get(
                    DataProtectionInterceptor.METADATA_KEY, new TypeReference<>() {});
            Map<String, String> parentFields = new LinkedHashMap<>();
            protectedFields.forEach((path, key) -> {
                if (references.stream().anyMatch(reference -> reference.deleteOnParentDeletion()
                        && (path.equals(reference.property().name()) || path.startsWith(reference.property().name() + "/")))) {
                    parentFields.put(path, key);
                }
            });
            if (!parentFields.isEmpty()) {
                // Read only selected protected parents, through the same record/namespace/vault restoration as replay.
                // This temporary value is never serialized into the schedule.
                message = parentDataRestoration.apply(new DeserializingMessage(message.withMetadata(
                        message.getMetadata().with(DataProtectionInterceptor.METADATA_KEY, parentFields)),
                        messageType, serializer)).toMessage();
            }
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (var reference : references) {
            if (reference.deleteOnParentDeletion()) {
                Object id = reference.read(message.getPayload());
                if (id != null) {
                    result.add(reference.repositoryId(id));
                }
            }
        }
        return List.copyOf(result);
    }

    static List<String> explicit(Object[] ids) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object id : ids) {
            if (id != null) {
                result.add(id instanceof Id<?> typed
                        ? EntityMetadata.of(typed.getType()).repositoryId(typed) : id.toString());
            }
        }
        if (result.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Schedule parent IDs must not be blank");
        }
        return List.copyOf(result);
    }
}
