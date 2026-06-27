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

package io.fluxzero.sdk.tracking.handling.authentication;

import java.util.Objects;

/**
 * Result of evaluating an authorization policy.
 *
 * @param allowed whether the action may continue
 * @param failure rejection reason when the failure should be surfaced
 * @param message diagnostic failure message
 */
public record AuthorizationDecision(
        boolean allowed,
        AuthorizationFailure failure,
        String message) {

    public AuthorizationDecision {
        Objects.requireNonNull(failure, "failure");
    }

    /**
     * Returns a decision that permits the action.
     */
    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, AuthorizationFailure.NONE, null);
    }

    /**
     * Returns a decision that silently skips the action.
     */
    public static AuthorizationDecision skipped() {
        return new AuthorizationDecision(false, AuthorizationFailure.NONE, null);
    }

    /**
     * Returns a surfaced rejection.
     */
    public static AuthorizationDecision rejected(AuthorizationFailure failure, String message) {
        if (failure == AuthorizationFailure.NONE) {
            throw new IllegalArgumentException("A rejected decision needs a concrete failure reason");
        }
        return new AuthorizationDecision(false, failure, Objects.requireNonNull(message, "message"));
    }

    /**
     * Returns whether this decision represents a surfaced rejection rather than a silent skip.
     */
    public boolean rejected() {
        return failure != AuthorizationFailure.NONE;
    }
}
