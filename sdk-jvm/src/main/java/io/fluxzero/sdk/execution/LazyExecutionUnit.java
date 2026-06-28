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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.JvmGeneratedExecutionInstaller;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

class LazyExecutionUnit implements AutoCloseable {
    private final ComponentDescriptor component;
    private final OnDemandCompiler compiler;
    private final Duration cacheTtl;
    private final boolean checkSourceChangesOnInvocation;

    private ActiveComponent activeComponent;

    LazyExecutionUnit(ComponentDescriptor component, OnDemandCompiler compiler, Duration cacheTtl,
                      boolean checkSourceChangesOnInvocation) {
        this.component = component;
        this.compiler = compiler;
        this.cacheTtl = cacheTtl;
        this.checkSourceChangesOnInvocation = checkSourceChangesOnInvocation;
    }

    synchronized Handler<DeserializingMessage> handler(MessageType messageType, HandlerFactory handlerFactory) {
        return handler(messageType, handlerFactory, null);
    }

    synchronized Handler<DeserializingMessage> handler(
            MessageType messageType, HandlerFactory handlerFactory, String sourceHash) {
        ActiveComponent active = active(sourceHash);
        active.compiled().touch();
        return active.handler(messageType, handlerFactory, component);
    }

    synchronized Class<?> type() {
        return active().handlerType();
    }

    synchronized OnDemandCompiler.CompilationRequest compilationRequestIfNeeded() {
        if (activeComponent != null && !expired(activeComponent.compiled())) {
            if (!checkSourceChangesOnInvocation) {
                return null;
            }
            String currentSourceHash = sourceHash();
            if (activeComponent.sourceHash().equals(currentSourceHash)) {
                return null;
            }
            return new OnDemandCompiler.CompilationRequest(component, currentSourceHash);
        }
        return new OnDemandCompiler.CompilationRequest(component, sourceHash());
    }

    OnDemandCompiler compiler() {
        return compiler;
    }

    private ActiveComponent active() {
        return active(null);
    }

    private ActiveComponent active(String knownSourceHash) {
        if (activeComponent != null && !expired(activeComponent.compiled())) {
            if (!checkSourceChangesOnInvocation) {
                return activeComponent;
            }
            String currentSourceHash = knownSourceHash == null ? sourceHash() : knownSourceHash;
            if (activeComponent.sourceHash().equals(currentSourceHash)) {
                return activeComponent;
            }
        }
        closeActive();
        String sourceHash = knownSourceHash == null ? sourceHash() : knownSourceHash;
        CompiledExecutionUnit compiled = compiler.compile(component, sourceHash);
        Class<?> handlerType = compiled.load(component.fullClassName());
        var generatedRegistration = JvmGeneratedExecutionInstaller.install(
                new ComponentRegistry(null, List.of(), List.of(component)), compiled.classLoader());
        activeComponent = new ActiveComponent(sourceHash, compiled, handlerType, generatedRegistration::cancel);
        return activeComponent;
    }

    private String sourceHash() {
        return compiler.sourceHash(component);
    }

    private boolean expired(CompiledExecutionUnit compiled) {
        return !cacheTtl.isNegative() && compiled.lastUsed().plus(cacheTtl).isBefore(Instant.now());
    }

    @Override
    public synchronized void close() {
        closeActive();
    }

    private void closeActive() {
        if (activeComponent != null) {
            activeComponent.close();
            activeComponent = null;
        }
    }

    ComponentDescriptor component() {
        return component;
    }

    private static final class ActiveComponent {
        private final String sourceHash;
        private final CompiledExecutionUnit compiled;
        private final Class<?> handlerType;
        private final AutoCloseable generatedRegistration;
        private final Map<MessageType, Handler<DeserializingMessage>> handlers = new EnumMap<>(MessageType.class);

        private ActiveComponent(
                String sourceHash, CompiledExecutionUnit compiled, Class<?> handlerType,
                AutoCloseable generatedRegistration) {
            this.sourceHash = sourceHash;
            this.compiled = compiled;
            this.handlerType = handlerType;
            this.generatedRegistration = generatedRegistration;
        }

        private Handler<DeserializingMessage> handler(
                MessageType messageType, HandlerFactory handlerFactory, ComponentDescriptor component) {
            return handlers.computeIfAbsent(messageType, ignored -> handlerFactory.createHandler(
                    handlerType, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow(
                    () -> new OnDemandExecutionException("Compiled on-demand execution source does not contain a "
                                                         + messageType + " handler: " + component.sourceFile())));
        }

        private String sourceHash() {
            return sourceHash;
        }

        private Class<?> handlerType() {
            return handlerType;
        }

        private CompiledExecutionUnit compiled() {
            return compiled;
        }

        private void close() {
            try {
                generatedRegistration.close();
            } catch (Exception ignored) {
                // Generated invocation registrations are tied to the lazy classloader lifecycle.
            }
            compiled.close();
        }
    }
}
