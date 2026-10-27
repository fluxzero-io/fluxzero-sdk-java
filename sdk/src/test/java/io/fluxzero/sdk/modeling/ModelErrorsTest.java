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

import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelErrorsTest {
    @Test
    void preservesEntityConstantsAndFormatsReadableSimpleNames() {
        assertSame(Entity.ALREADY_EXISTS_EXCEPTION, ModelErrors.ALREADY_EXISTS_EXCEPTION);
        assertSame(Entity.NOT_FOUND_EXCEPTION, ModelErrors.NOT_FOUND_EXCEPTION);
        assertEquals("An order is required.", ModelErrors.parentRequired(Order.class).getMessage());
        assertEquals("The order does not exist.", ModelErrors.parentNotFound(Order.class).getMessage());
        assertEquals("Cannot add another line item to this order.",
                     ModelErrors.maxChildrenExceeded(Order.class, LineItem.class).getMessage());
        assertEquals("Cannot delete this order while a dependent line item exists.",
                     ModelErrors.parentDeletionBlocked(Order.class, LineItem.class).getMessage());
        assertEquals("Cannot add another API key to this order.",
                     ModelErrors.maxChildrenExceeded(Order.class, APIKey.class).getMessage());
        assertEquals("A user is required.", ModelErrors.parentRequired(User.class).getMessage());
    }

    @Test
    void safelyHandlesUnknownTypesAndComparesAfterSerialization() {
        assertEquals("The maximum number of related items has been reached.",
                     ModelErrors.maxChildrenExceeded(null, LineItem.class).getMessage());
        var error = ModelErrors.parentDeletionBlocked(Order.class, LineItem.class);
        var serializer = new JacksonSerializer();
        assertEquals(error, serializer.deserialize(serializer.serialize(error)));
        assertFalse(error.getMessage().contains(getClass().getName()));
    }

    private record Order() {}
    private record LineItem() {}
    private record APIKey() {}
    private record User() {}
}
