/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.persisting.search;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.search.BulkUpdate;
import io.fluxzero.common.api.search.bulkupdate.DeleteDocument;
import io.fluxzero.sdk.modeling.Entity;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
class DefaultBulkUpdateBuilder implements BulkUpdateBuilder {

    private final DocumentStore documentStore;
    @NonNull
    private final Object collection;
    private final List<BulkUpdate> updates = new ArrayList<>();

    @Override
    public BulkUpdateBuilder index(@NonNull Object object) {
        return index(object, false);
    }

    @Override
    public BulkUpdateBuilder index(@NonNull Object object, @NonNull Object id, Instant begin, Instant end) {
        return index(object, id, begin, end, false);
    }

    @Override
    public BulkUpdateBuilder indexIfNotExists(@NonNull Object object) {
        return index(object, true);
    }

    @Override
    public BulkUpdateBuilder indexIfNotExists(@NonNull Object object, @NonNull Object id, Instant begin,
                                              Instant end) {
        return index(object, id, begin, end, true);
    }

    @Override
    public BulkUpdateBuilder delete(@NonNull Object id) {
        updates.add(new DeleteDocument(id, collection));
        return this;
    }

    @Override
    public List<BulkUpdate> toBulkUpdates() {
        return List.copyOf(updates);
    }

    @Override
    public CompletableFuture<Void> execute(Guarantee guarantee) {
        return documentStore.bulkUpdate(toBulkUpdates(), guarantee);
    }

    private BulkUpdateBuilder index(Object object, boolean ifNotExists) {
        object = arrayToCollection(object);
        if (object instanceof Collection<?> values) {
            values.forEach(value -> index(value, ifNotExists));
            return this;
        }
        if (object instanceof Entity<?> entity && entity.isEmpty()) {
            return delete(Objects.requireNonNull(entity.id(), "Entity ID must not be null"));
        }
        updates.add(documentStore.prepareIndex(object)
                            .collection(collection)
                            .ifNotExists(ifNotExists)
                            .toBulkUpdate());
        return this;
    }

    private BulkUpdateBuilder index(Object object, Object id, Instant begin, Instant end, boolean ifNotExists) {
        if (object instanceof Entity<?> entity && entity.isEmpty()) {
            return delete(id);
        }
        updates.add(documentStore.prepareIndex(object)
                            .collection(collection)
                            .id(id)
                            .period(begin, end)
                            .ifNotExists(ifNotExists)
                            .toBulkUpdate());
        return this;
    }

    private Object arrayToCollection(Object object) {
        return object.getClass().isArray() && !object.getClass().getComponentType().isPrimitive()
                ? Arrays.asList((Object[]) object) : object;
    }
}
