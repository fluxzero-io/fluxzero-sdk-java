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
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
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
    void affixedModelLoadsByTypedIdAndFunctionalString() {
        AtomicReference<String> actualId = new AtomicReference<>();
        Entity<AffixedModel> expected = entity();
        when(expected.isPresent()).thenReturn(true);
        ModelRepository repository = new ModelRepository() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Entity<T> load(String modelId, Class<T> modelType) {
                actualId.set(modelId);
                return (Entity<T>) expected;
            }
        };

        repository.load(new AffixedId("123"));
        assertEquals("move-model-123-state", actualId.get());

        repository.load((Object) "123", AffixedModel.class);
        assertEquals("move-model-123-state", actualId.get());
    }

    @Test
    void affixedModelStillFallsBackToAnUndecoratedAlias() {
        java.util.ArrayList<String> actualIds = new java.util.ArrayList<>();
        Entity<AffixedModel> missing = entity();
        Entity<AffixedModel> expected = entity();
        when(missing.isPresent()).thenReturn(false);
        when(expected.isPresent()).thenReturn(true);
        ModelRepository repository = new ModelRepository() {
            @Override
            public <T> Entity<T> load(String modelId, Class<T> modelType) {
                actualIds.add(modelId);
                @SuppressWarnings("unchecked")
                Entity<T> result = (Entity<T>) ("legacy-code".equals(modelId) ? expected : missing);
                return result;
            }
        };

        Entity<AffixedModel> result = repository.load((Object) "legacy-code", AffixedModel.class);

        assertSame(expected, result);
        assertEquals(java.util.List.of("move-model-legacy-code-state", "legacy-code"), actualIds);
    }

    @Test
    void missingAliasRetainsTheAffixedPrimaryIdentity() {
        java.util.ArrayList<String> actualIds = new java.util.ArrayList<>();
        Entity<AffixedModel> primary = entity();
        Entity<AffixedModel> missingAlias = entity();
        when(primary.isPresent()).thenReturn(false);
        when(missingAlias.isPresent()).thenReturn(false);
        ModelRepository repository = new ModelRepository() {
            @Override
            public <T> Entity<T> load(String modelId, Class<T> modelType) {
                actualIds.add(modelId);
                @SuppressWarnings("unchecked")
                Entity<T> result = (Entity<T>) (modelId.startsWith("move-") ? primary : missingAlias);
                return result;
            }
        };

        Entity<AffixedModel> result = repository.load((Object) "unknown-code", AffixedModel.class);

        assertSame(primary, result);
        assertEquals(java.util.List.of("move-model-unknown-code-state", "unknown-code"), actualIds);
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

    @Model
    private record AffixedModel(
            @EntityId(prefix = "move-", postfix = "-state") AffixedId id,
            @Alias String code) {
    }

    private static class AffixedId extends Id<AffixedModel> {
        AffixedId(String id) {
            super(id, "model-");
        }
    }
}
