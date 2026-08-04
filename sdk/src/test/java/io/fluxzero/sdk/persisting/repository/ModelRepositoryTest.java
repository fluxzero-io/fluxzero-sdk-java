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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Id;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRepositoryTest {

    @AfterEach
    void clearFluxzero() {
        Fluxzero.instance.remove();
    }

    @Test
    void typedLoadUsesExactIdStringAndType() {
        AtomicReference<String> actualId = new AtomicReference<>();
        AtomicReference<Class<?>> actualType = new AtomicReference<>();
        Entity<TestModel> expected = entity();
        ModelRepository repository = new ModelRepository() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Entity<T> load(String modelId, Class<T> modelType) {
                actualId.set(modelId);
                actualType.set(modelType);
                return (Entity<T>) expected;
            }
        };

        Entity<TestModel> result = repository.load(new TestModelId("123"));

        assertSame(expected, result);
        assertEquals("model-123", actualId.get());
        assertSame(TestModel.class, actualType.get());
    }

    @Test
    void untypedLoadUsesExactIdStringAndLetsStorageResolveType() {
        AtomicReference<String> actualId = new AtomicReference<>();
        AtomicReference<Class<?>> actualType = new AtomicReference<>();
        Entity<Object> expected = entity();
        ModelRepository repository = new ModelRepository() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Entity<T> load(String modelId, Class<T> modelType) {
                actualId.set(modelId);
                actualType.set(modelType);
                return (Entity<T>) expected;
            }
        };
        Object externalId = new Object() {
            @Override
            public String toString() {
                return "external-123";
            }
        };

        Entity<Object> result = repository.load(externalId);

        assertSame(expected, result);
        assertEquals("external-123", actualId.get());
        assertSame(Object.class, actualType.get());
    }

    @Test
    void fluxzeroLoadModelDelegatesWithoutDecoratingId() {
        Entity<TestModel> expected = entity();
        ModelRepository repository = mock(ModelRepository.class);
        Fluxzero fluxzero = mock(Fluxzero.class);
        when(fluxzero.modelRepository()).thenReturn(repository);
        when(repository.load((Object) "external-id", TestModel.class)).thenReturn(expected);
        Fluxzero.instance.set(fluxzero);

        Entity<TestModel> result = Fluxzero.loadModel("external-id", TestModel.class);

        assertSame(expected, result);
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> entity() {
        return (Entity<T>) mock(Entity.class);
    }

    private record TestModel(String id) {
    }

    private static class TestModelId extends Id<TestModel> {
        TestModelId(String id) {
            super(id, "model-");
        }
    }
}
