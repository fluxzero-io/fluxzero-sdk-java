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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.caching.NoOpCache;
import io.fluxzero.sdk.modeling.Entity;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Slf4j
final class RelationshipsCache {
    private static final RelationshipsCache NO_OP = new RelationshipsCache(NoOpCache.INSTANCE);

    private final Cache delegate;

    private RelationshipsCache(Cache delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static RelationshipsCache of(Cache delegate) {
        return new RelationshipsCache(delegate);
    }

    static RelationshipsCache noOp() {
        return NO_OP;
    }

    Map<String, Class<?>> getAggregatesFor(Object entityId,
                                           Function<String, Map<String, Class<?>>> mappingFunction) {
        String id = entityId.toString();
        return delegate.computeIfAbsent(id, ignored -> mappingFunction.apply(id));
    }

    void updateLinks(Entity<?> before, Entity<?> after) {
        Set<Relationship> associations = after.associations(before), dissociations = after.dissociations(before);
        updateLinks(associations, dissociations, after.type());
    }

    void updateLinks(Set<Relationship> associations, Set<Relationship> dissociations, Class<?> aggregateType) {
        dissociations.forEach(this::removeAggregateFromLookup);
        associations.forEach(relationship -> addAggregateToLookup(relationship, aggregateType));
    }

    void removeAggregate(String aggregateId) {
        delegate.<Object>modifyEach((entityId, value) -> {
            if (value instanceof Map<?, ?> map) {
                map.remove(aggregateId);
                return map.isEmpty() ? null : value;
            }
            return value;
        });
    }

    void invalidateLookupsFor(Entity<?>... aggregates) {
        Set<String> aggregateIds = new LinkedHashSet<>();
        Set<String> entityIds = new LinkedHashSet<>();
        for (Entity<?> aggregate : aggregates) {
            if (aggregate == null) {
                continue;
            }
            try {
                Object aggregateId = aggregate.id();
                if (aggregateId != null) {
                    String id = aggregateId.toString();
                    aggregateIds.add(id);
                    entityIds.add(id);
                }
                aggregate.relationships().stream()
                        .map(Relationship::getEntityId)
                        .filter(Objects::nonNull)
                        .forEach(entityIds::add);
            } catch (Throwable e) {
                log.debug("Failed to collect relationship lookup keys for aggregate", e);
            }
        }
        entityIds.forEach(this::removeLookup);
        aggregateIds.forEach(this::removeLookupsContainingAggregate);
    }

    private void removeAggregateFromLookup(Relationship relationship) {
        delegate.<Object>computeIfPresent(relationship.getEntityId(), (id, value) -> {
            if (value instanceof Map<?, ?> map) {
                map.remove(relationship.getAggregateId());
            }
            return value;
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addAggregateToLookup(Relationship relationship, Class<?> aggregateType) {
        delegate.<Object>computeIfPresent(relationship.getEntityId(), (id, value) -> {
            if (value instanceof Map map) {
                map.put(relationship.getAggregateId(), aggregateType);
            }
            return value;
        });
    }

    private void removeLookup(String entityId) {
        try {
            delegate.remove(entityId);
        } catch (Throwable e) {
            log.debug("Failed to invalidate relationship lookup for entity {}", entityId, e);
        }
    }

    private void removeLookupsContainingAggregate(String aggregateId) {
        try {
            delegate.<Object>modifyEach((entityId, value) ->
                    value instanceof Map<?, ?> map && map.containsKey(aggregateId) ? null : value);
        } catch (Throwable e) {
            log.debug("Failed to invalidate relationship lookups containing aggregate {}", aggregateId, e);
        }
    }
}
