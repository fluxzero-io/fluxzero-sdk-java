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

package io.fluxzero.common.api.scheduling;

/**
 * Best-effort metric emitted after an owned schedule was removed following parent deletion.
 * It contains no command payload. A schedule already delivered to a consumer cannot be recalled.
 * This metric is operational telemetry, not an exactly-once audit record.
 *
 * @param scheduleId removed schedule identity
 * @param messageId removed scheduled message identity, not the replacement's identity
 * @param deadline scheduled delivery time in epoch milliseconds
 */
public record ScheduleAutoCancelled(String scheduleId, String messageId, long deadline) {}
