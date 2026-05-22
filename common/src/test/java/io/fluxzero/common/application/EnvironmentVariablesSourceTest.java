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

package io.fluxzero.common.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvironmentVariablesSourceTest {

    @Test
    void propertyNamesAreConvertedToEnvironmentVariableNames() {
        assertEquals("FLUXZERO_API_TOKEN", EnvironmentVariablesSource.toEnvironmentVariableName(
                "fluxzero.api.token"));
        assertEquals("FLUXZERO_TRACKING_UNCONFIGURED_HANDLER_CONSUMER_MODE",
                     EnvironmentVariablesSource.toEnvironmentVariableName(
                             "fluxzero.tracking.unconfiguredHandlerConsumerMode"));
        assertEquals("FLUXZERO_DEFAULTS_VERSION", EnvironmentVariablesSource.toEnvironmentVariableName(
                "fluxzero.defaults.version"));
        assertEquals("FLUXZERO_ASSERT_APPLY_COMPATIBILITY", EnvironmentVariablesSource.toEnvironmentVariableName(
                "fluxzero.assert.apply-compatibility"));
    }

    @Test
    void environmentVariableNamesRemainStable() {
        assertEquals("FLUXZERO_APPLICATION_NAME", EnvironmentVariablesSource.toEnvironmentVariableName(
                "FLUXZERO_APPLICATION_NAME"));
    }

    @Test
    void oddPropertyNamesAreConvertedSafely() {
        assertEquals("", EnvironmentVariablesSource.toEnvironmentVariableName(""));
        assertEquals("", EnvironmentVariablesSource.toEnvironmentVariableName("."));
        assertEquals("FLUXZERO", EnvironmentVariablesSource.toEnvironmentVariableName(".fluxzero."));
        assertEquals("FLUXZERO_API_TOKEN", EnvironmentVariablesSource.toEnvironmentVariableName(
                "fluxzero..api--token"));
    }
}
