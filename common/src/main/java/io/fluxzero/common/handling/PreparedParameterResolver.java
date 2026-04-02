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

package io.fluxzero.common.handling;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.function.Function;

/**
 * Optional extension for {@link ParameterResolver parameter resolvers} that can prepare a resolver function for a
 * specific message in one step.
 *
 * <p>This is intended for resolvers that can avoid duplicate work between matching and resolving while preserving the
 * default {@link ParameterResolver} contract for all other implementations.
 */
public interface PreparedParameterResolver<M> extends ParameterResolver<M> {

    /**
     * Returns a prepared resolver for the given message, or {@code null} when this resolver cannot handle the
     * parameter.
     */
    Function<M, Object> resolveIfPossible(Parameter parameter, Annotation methodAnnotation, M value);
}
