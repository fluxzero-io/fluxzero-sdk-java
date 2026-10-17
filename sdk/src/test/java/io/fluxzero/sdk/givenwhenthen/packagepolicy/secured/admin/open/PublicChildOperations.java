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

package io.fluxzero.sdk.givenwhenthen.packagepolicy.secured.admin.open;

import io.fluxzero.sdk.givenwhenthen.packagepolicy.PublicOperations;
import io.fluxzero.sdk.tracking.handling.HandleQuery;

public class PublicChildOperations {
    public record Request() {}

    public static class Handler {
        @HandleQuery
        public String handle(PublicOperations.Request request) {
            return "handled";
        }
    }
}
