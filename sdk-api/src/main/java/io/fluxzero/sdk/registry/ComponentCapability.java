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

package io.fluxzero.sdk.registry;

/**
 * Capability exposed by a component discovered in the Fluxzero application model.
 */
public enum ComponentCapability {
    /**
     * Component metadata was discovered from source without compiling or loading the type.
     */
    SOURCE_COMPONENT,

    /**
     * Component metadata was discovered from already compiled classes on the application classpath.
     */
    CLASSPATH_COMPONENT,

    /**
     * Component declares at least one Fluxzero handler route.
     */
    HANDLER,

    /**
     * Component or package metadata marks at least one route for local dispatch.
     */
    LOCAL_HANDLER,

    /**
     * Component declares at least one route that should be tracked through Fluxzero.
     */
    TRACKING_HANDLER,

    /**
     * Component declares at least one web request route.
     */
    WEB_REQUEST_HANDLER,

    /**
     * Package metadata contributes local handler defaults.
     */
    PACKAGE_LOCAL_HANDLER,

    /**
     * Component or package declares type registration metadata.
     */
    REGISTERED_TYPE,

    /**
     * Component or package declares consumer metadata.
     */
    CONSUMER,

    /**
     * Component implements dispatch interception infrastructure.
     */
    DISPATCH_INTERCEPTOR,

    /**
     * Component implements handler decoration infrastructure.
     */
    HANDLER_DECORATOR,

    /**
     * Component implements handler interception infrastructure.
     */
    HANDLER_INTERCEPTOR,

    /**
     * Component implements batch interception infrastructure.
     */
    BATCH_INTERCEPTOR,

    /**
     * Component implements the default response mapper contract.
     */
    RESPONSE_MAPPER,

    /**
     * Component implements the web response mapper contract.
     */
    WEB_RESPONSE_MAPPER,

    /**
     * Component implements validation infrastructure.
     */
    VALIDATOR,

    /**
     * Component implements handler parameter resolution infrastructure.
     */
    PARAMETER_RESOLVER,

    /**
     * Component implements the main serializer contract.
     */
    SERIALIZER,

    /**
     * Component implements the document serializer contract.
     */
    DOCUMENT_SERIALIZER,

    /**
     * Component implements correlation data provider infrastructure.
     */
    CORRELATION_DATA_PROVIDER,

    /**
     * Component implements identity provider infrastructure.
     */
    IDENTITY_PROVIDER,

    /**
     * Component implements user provider infrastructure.
     */
    USER_PROVIDER,

    /**
     * Component implements cache infrastructure.
     */
    CACHE,

    /**
     * Component implements task scheduling infrastructure.
     */
    TASK_SCHEDULER,

    /**
     * Component implements application property source infrastructure.
     */
    PROPERTY_SOURCE
}
