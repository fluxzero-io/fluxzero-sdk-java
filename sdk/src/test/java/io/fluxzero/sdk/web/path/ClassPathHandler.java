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

package io.fluxzero.sdk.web.path;

import io.fluxzero.sdk.web.HandleWeb;
import io.fluxzero.sdk.web.Path;

import static io.fluxzero.sdk.web.HttpRequestMethod.GET;

@Path("/class/")
public class ClassPathHandler {
    @HandleWeb(value = "/get", method = GET)
    String get() {
        return "get";
    }

    @Path("/method")
    @HandleWeb(value = "get", method = GET)
    String override() {
        return "get";
    }
}
