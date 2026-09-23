/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.JsonValueSerializer;

/** Automatically discovered Jackson support for otherwise ambiguous ID properties. */
public final class IdJacksonModule extends SimpleModule {
    public IdJacksonModule() {
        super("FluxzeroIds");
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription description,
                                                     JsonSerializer<?> serializer) {
                return wrap(description, serializer);
            }

            private JsonSerializer<?> wrap(BeanDescription description, JsonSerializer<?> serializer) {
                if (!Id.class.isAssignableFrom(description.getBeanClass())) {
                    return serializer;
                }
                JsonSerializer<?> delegatee = serializer.getDelegatee();
                if (delegatee != null) {
                    JsonSerializer<?> wrapped = wrap(description, delegatee);
                    return wrapped == delegatee ? serializer : serializer.replaceDelegatee(wrapped);
                }
                if (Id.class.isAssignableFrom(description.getBeanClass())
                    && serializer.getClass() == JsonValueSerializer.class
                    && description.findJsonValueAccessor() != null
                    && description.findJsonValueAccessor().getDeclaringClass() == Id.class) {
                    @SuppressWarnings("unchecked")
                    JsonSerializer<Object> delegate = (JsonSerializer<Object>) serializer;
                    return new Id.IdSerializer(delegate, null);
                }
                return serializer;
            }
        });
    }
}
