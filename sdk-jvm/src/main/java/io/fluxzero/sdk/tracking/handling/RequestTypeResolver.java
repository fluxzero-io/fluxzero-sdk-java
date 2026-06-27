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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.sdk.registry.JvmComponentIntrospector;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * JVM runtime helper for resolving the response type declared by {@link Request}.
 */
public final class RequestTypeResolver {

    private RequestTypeResolver() {
    }

    /**
     * Resolves the declared response type for a request instance.
     *
     * @param request request instance
     * @return declared response type, or {@link Object} when it cannot be resolved
     */
    public static Type responseType(Request<?> request) {
        Type genericType = JvmComponentIntrospector.getInstance().getGenericType(request.getClass(), Request.class);
        if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
            return pt.getActualTypeArguments()[0];
        }
        return Object.class;
    }
}
