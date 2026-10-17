/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModifiableEntityUpdateTest {
    @Test
    @SuppressWarnings("unchecked")
    void preservesCustomEntityUpdate() {
        Entity<String> delegate = mock(Entity.class);
        when(delegate.id()).thenReturn("child");
        when(delegate.update(any())).thenAnswer(invocation -> {
            UnaryOperator<String> update = invocation.getArgument(0);
            assertEquals("changed", update.apply("child"));
            return rootValue();
        });
        verifyUpdate(delegate);
        verify(delegate).update(any());
    }

    @Test
    void preservesImmutableSubclassOverride() {
        AtomicInteger calls = new AtomicInteger();
        Entity<String> delegate = new ImmutableEntity<String>(ImmutableEntity.<String>builder()
                .id("child").type(String.class).value("child").idProperty("id")) {
            @Override
            public Entity<String> update(UnaryOperator<String> function) {
                calls.incrementAndGet();
                assertEquals("changed", function.apply(get()));
                return rootValue();
            }
        };
        verifyUpdate(delegate);
        assertEquals(1, calls.get());
    }

    private static Entity<String> rootValue() {
        return ImmutableEntity.<String>builder().id("root").type(String.class).value("updated root").build();
    }

    @SuppressWarnings("unchecked")
    private void verifyUpdate(Entity<String> delegate) {
        ModifiableAggregateRoot<String> root = mock(ModifiableAggregateRoot.class);
        AtomicReference<String> updated = new AtomicReference<>();
        when(root.update(any())).thenAnswer(invocation -> {
            UnaryOperator<String> update = invocation.getArgument(0);
            updated.set(update.apply("old root"));
            return root;
        });
        when(root.<String>getEntity("child")).thenReturn(Optional.of(delegate));
        assertSame(delegate, new ModifiableEntity<>(delegate, root).update(value -> "changed"));
        assertEquals("updated root", updated.get());
    }
}
