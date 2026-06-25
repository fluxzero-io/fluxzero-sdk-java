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

package io.fluxzero.sdk.execution;

import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import lombok.NonNull;

import java.nio.file.Path;

/**
 * Defines how a local Fluxzero Java application materializes executable application logic.
 * <p>
 * The default SDK behavior uses already compiled classes on the application classpath. An execution mode can add
 * another materialization strategy, such as compiling and loading Java source units only when they are first invoked.
 */
public interface ExecutionMode {

    /**
     * Creates an on-demand execution mode for the supplied Java source root.
     */
    static OnDemandExecution onDemand(@NonNull Path sourceRoot) {
        return onDemand().sourceRoot(sourceRoot).build();
    }

    /**
     * Starts building an on-demand execution mode.
     */
    static OnDemandExecution.Builder onDemand() {
        return OnDemandExecution.builder();
    }

    /**
     * Attaches this execution mode to a built Fluxzero instance.
     */
    Registration registerWith(Fluxzero fluxzero);
}
