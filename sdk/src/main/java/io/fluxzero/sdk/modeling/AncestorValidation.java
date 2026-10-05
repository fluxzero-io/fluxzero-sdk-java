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

/** Per-Apply control of inherited cascading assertions; local and payload assertions remain active. */
public enum AncestorValidation {
    /** Inherit the earlier Apply setting, or enable inherited validation when no setting exists. */
    DEFAULT,
    /** Enable inherited validation without reopening a disabled Parent route. */
    ENABLED,
    /** Skip inherited cascading assertions for this mutation only. */
    DISABLED
}
