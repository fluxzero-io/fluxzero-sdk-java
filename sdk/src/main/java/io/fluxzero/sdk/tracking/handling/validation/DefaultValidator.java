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
 *
 */

package io.fluxzero.sdk.tracking.handling.validation;

import io.fluxzero.sdk.tracking.handling.validation.jakarta.DefaultJakartaValidator;
import jakarta.validation.ClockProvider;

import java.time.Clock;

/**
 * Canonical SDK entry point for Fluxzero's Validation implementation.
 * <p>
 * This validator supports the Jakarta Validation annotations and features used by the SDK, including built-in
 * constraints, custom constraints, groups, cascaded validation, type-use/container validation, executable parameter
 * and return-value validation, metadata lookup, value extractors, and Fluxzero-clock based temporal checks.
 * <p>
 * It is intentionally not a full standalone Jakarta Validation provider replacement for every TCK edge case:
 * XML mappings, {@code validation.xml}, CDI lifecycle integration, TraversableResolver reachability rules, and full
 * Expression Language message evaluation are outside the supported SDK profile.
 */
public class DefaultValidator extends DefaultJakartaValidator {
    /**
     * Creates a validator that uses the current Fluxzero clock for temporal constraints.
     */
    public DefaultValidator() {
        super();
    }

    /**
     * Creates a validator that uses the supplied clock for temporal constraints such as {@code @Past} and
     * {@code @Future}.
     *
     * @param clock the clock to use while validating temporal constraints
     */
    public DefaultValidator(Clock clock) {
        super(clock);
    }

    /**
     * Creates a validator that obtains its clock from the supplied Jakarta {@link ClockProvider}.
     *
     * @param clockProvider provider used for temporal constraints
     */
    protected DefaultValidator(ClockProvider clockProvider) {
        super(clockProvider);
    }

    /**
     * Creates the default SDK validator instance.
     *
     * @return a validator backed by the current Fluxzero clock
     */
    public static DefaultValidator createDefault() {
        return new DefaultValidator();
    }
}
