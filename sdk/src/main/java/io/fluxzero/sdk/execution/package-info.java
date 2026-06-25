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

/**
 * Incubating Fluxzero execution modes.
 * <p>
 * Types in this package let applications select how Fluxzero components are loaded and invoked. The on-demand mode is
 * intended for trusted local Java source execution, prefers generated component registry artifacts when present, and
 * keeps normal Fluxzero handling semantics after lazy compilation.
 */
package io.fluxzero.sdk.execution;
