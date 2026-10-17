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

package io.fluxzero.common.api.modeling;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An alias mapping observed while selecting a mutation target. A null owner explicitly records absence.
 * The owner, when present, must also participate in the commit's canonical Model-head dependencies.
 * This observation is independent of any primary identity equal to {@link #alias()}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelAliasObservation(String alias, String expectedOwner) {}
