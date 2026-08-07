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

package io.fluxzero.sdk.web;

/**
 * Determines how {@link WebRequest#getCookie(String, CookieValueConflictPolicy)} handles multiple cookies with the
 * same case-sensitive name.
 */
public enum CookieValueConflictPolicy {

    /** Uses the SDK default, which is {@link #ALLOW_CONFLICTING_VALUES} in this release. */
    DEFAULT,

    /** Returns the first matching cookie, including when later values differ. */
    ALLOW_CONFLICTING_VALUES,

    /** Rejects the lookup when matching cookies contain different values. */
    REJECT_CONFLICTING_VALUES
}
