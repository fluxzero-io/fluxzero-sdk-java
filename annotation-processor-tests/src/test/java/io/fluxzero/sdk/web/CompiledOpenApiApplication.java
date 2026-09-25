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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/** Gives the compiled test application's handlers their own document without changing the surrounding classpath. */
final class CompiledOpenApiApplication extends URLClassLoader {
    static final String DOCUMENT = "META-INF/fluxzero/processor-test-openapi.json";
    private static final String HANDLER_PACKAGE = "io.fluxzero.sdk.web.openapiauto.";

    CompiledOpenApiApplication() {
        super(new URL[]{CompiledOpenApiApplication.class.getProtectionDomain().getCodeSource().getLocation()},
              CompiledOpenApiApplication.class.getClassLoader());
    }

    Object handler(Class<?> type) throws ReflectiveOperationException {
        return loadClass(type.getName()).getConstructor().newInstance();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(HANDLER_PACKAGE)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                type = findClass(name);
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (OpenApiProcessor.DEFAULT_OUTPUT.equals(name)) {
            return Collections.enumeration(List.of(Objects.requireNonNull(findResource(DOCUMENT),
                                                                         "Compiled OpenAPI document is missing")));
        }
        return super.getResources(name);
    }
}
