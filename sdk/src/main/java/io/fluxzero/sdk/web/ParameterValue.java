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

package io.fluxzero.sdk.web;

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.serialization.JsonUtils;
import lombok.Value;

import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wrapper around a resolved parameter value in a web request.
 * <p>
 * This class is used internally by Fluxzero to abstract over raw parameter values retrieved
 * from different parts of a {@link WebRequestContext} (such as query strings, form data, path variables,
 * headers, or cookies).
 *
 * <p>The value may be backed by Jooby’s {@link io.jooby.value.Value} type or by a JSON value extracted from the
 * request body.
 *
 * <h2>Usage</h2>
 * {@code ParameterValue} instances are returned by methods such as:
 * <ul>
 *   <li>{@link WebRequestContext#getParameter(String, WebParameterSource...)}</li>
 *   <li>{@link WebRequestContext#getQueryParameter(String)}</li>
 *   <li>{@link WebRequestContext#getFormParameter(String)}</li>
 * </ul>
 * These are used during web handler method resolution to supply method argument values.
 *
 * @see WebRequestContext
 * @see io.jooby.value.Value
 */
@Value
public class ParameterValue {

    /**
     * The underlying parameter value.
     */
    Object value;

    public boolean hasValue() {
        return switch (value) {
            case null -> false;
            case io.jooby.value.Value joobyValue -> joobyValue.valueOrNull() != null;
            default -> true;
        };
    }

    /**
     * Converts the underlying value to the specified target type.
     * <p>
     * If conversion fails or the value is not present, {@code null} is returned.
     *
     * @param type the desired target type
     * @param <V>  the generic type to cast to
     * @return the converted value or {@code null} if unavailable
     */
    public <V> V as(Class<V> type) {
        return as((Type) type);
    }

    /**
     * Converts the underlying value to the specified target type, including parameterized collection types.
     */
    @SuppressWarnings("unchecked")
    public <V> V as(Type type) {
        Object rawValue = value;
        if (rawValue == null) {
            return null;
        }
        if (isSingleValueTarget(type) && rawValue instanceof List<?> list && list.size() == 1) {
            rawValue = list.getFirst();
        }
        if (type instanceof Class<?> c && c.isInstance(rawValue)) {
            return (V) c.cast(rawValue);
        }
        if (type instanceof ParameterizedType pt && rawValue instanceof List<?> list
                && pt.getRawType() instanceof Class<?> rawClass && Collection.class.isAssignableFrom(rawClass)) {
            Type elementType = ReflectionUtils.getFirstTypeArgument(type);
            List<Object> converted = new ArrayList<>(list.size());
            for (Object element : list) {
                converted.add(new ParameterValue(element).as(elementType));
            }
            return (V) converted;
        }
        if (rawValue instanceof MultipartFormPart part) {
            if (type == byte[].class) {
                return (V) part.getBytes();
            }
            if (type instanceof Class<?> c && InputStream.class.isAssignableFrom(c)) {
                return (V) c.cast(part.asInputStream());
            }
        }
        if (rawValue instanceof io.jooby.value.Value joobyValue) {
            if (type instanceof Class<?> c) {
                Object converted = joobyValue.toNullable(c);
                if (converted != null) {
                    return (V) converted;
                }
            }
            rawValue = joobyValue.valueOrNull();
            if (rawValue == null) {
                return null;
            }
            if (type instanceof Class<?> c && c.isInstance(rawValue)) {
                return (V) c.cast(rawValue);
            }
        }
        return JsonUtils.convertValue(JsonUtils.valueToTree(rawValue), type);
    }

    boolean isSingleValueTarget(Type type) {
        if (type instanceof Class<?> c) {
            return !Collection.class.isAssignableFrom(c);
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return !Collection.class.isAssignableFrom(c);
        }
        return true;
    }
}
