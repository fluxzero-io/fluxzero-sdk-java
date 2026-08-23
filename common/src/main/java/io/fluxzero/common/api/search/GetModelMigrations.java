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

package io.fluxzero.common.api.search;

import io.fluxzero.common.api.Request;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.beans.ConstructorProperties;

/** Retrieves a bounded batch of staged direct Model migrations for application-level adoption. */
@EqualsAndHashCode(callSuper = true)
@Value
public class GetModelMigrations extends Request {
    int maxSize;

    @ConstructorProperties("maxSize")
    public GetModelMigrations(int maxSize) {
        if (maxSize <= 0 || maxSize > 1_000) {
            throw new IllegalArgumentException(
                    "Model migration batch size must be between 1 and 1000");
        }
        this.maxSize = maxSize;
    }
}
