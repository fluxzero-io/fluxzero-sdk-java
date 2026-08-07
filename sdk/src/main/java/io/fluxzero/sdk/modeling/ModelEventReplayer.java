/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.fluxzero.common.reflection.DefaultMemberInvoker.asInvoker;

/**
 * Reuses the model commit evaluator for one stored event during model reconstruction.
 * <p>
 * Assertions and interceptors are deliberately not supplied by the repository during replay. Only the exact
 * {@code @Apply} transition that originally targeted the reconstructed model is selected.
 */
public final class ModelEventReplayer {
    private final ModelCommitEngine engine;
    private final Map<Executable, MemberInvoker> directInvokers =
            new ConcurrentHashMap<>();

    public ModelEventReplayer(
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        engine = new ModelCommitEngine(parameterResolvers);
    }

    /**
     * Replays one already-intercepted stored event and returns its target value.
     */
    public ReplayResult replay(
            DeserializingMessage event,
            ModelCommitContext context,
            Collection<ModelMetadata.HandlerMethod> handlers,
            String targetModelId) {
        Objects.requireNonNull(targetModelId, "targetModelId");
        if (handlers.size() == 1) {
            ModelCommitEngine.SingleTargetEvaluation evaluation =
                    engine.evaluateSingleTarget(
                            event,
                            context,
                            handlers.iterator().next(),
                            targetModelId);
            return new ReplayResult(
                    evaluation.applied(),
                    evaluation.value());
        }
        ModelCommitEngine.Evaluation evaluation =
                engine.evaluate(event, context, handlers);
        ModelCommitEngine.Transition selected = null;
        for (ModelCommitEngine.Transition transition : evaluation.transitions()) {
            if (!targetModelId.equals(transition.modelId())) {
                continue;
            }
            if (selected != null) {
                throw new IllegalStateException(
                        "Stored model event produced more than one transition for " + targetModelId);
            }
            selected = transition;
        }
        return selected == null
                ? new ReplayResult(false, context.entry(targetModelId).entity().get())
                : new ReplayResult(true, selected.after());
    }

    /**
     * Whether a stored event can invoke this apply without generic parameter or model-target resolution.
     *
     * <p>The persisted stream membership is already the authoritative target. This fast path is deliberately limited
     * to a single payload parameter and no injected models; all richer handler shapes retain the regular evaluator.</p>
     */
    public boolean supportsDirectReplay(
            ModelMetadata.HandlerMethod handler,
            Class<?> payloadType,
            Class<?> modelType) {
        if (handler.kind() != ModelMetadata.HandlerKind.APPLY
            || handler.targetModelTypes().size() != 1
            || handler.modelParameters().size() != 0
            || !compatible(
                    handler.targetModelTypes().getFirst(),
                    modelType)) {
            return false;
        }
        Executable executable = handler.executable();
        if (executable.getParameterCount() != 1
            || executable.getParameters()[0]
                       .getAnnotations().length != 0
            || !executable.getParameterTypes()[0]
                    .isAssignableFrom(payloadType)) {
            return false;
        }
        if (executable instanceof Constructor<?>
            || Modifier.isStatic(
                    executable.getModifiers())) {
            return true;
        }
        return handler.receiverModelType() != null
               && compatible(
                       handler.receiverModelType(),
                       modelType);
    }

    /**
     * Replays a handler previously accepted by {@link #supportsDirectReplay} against one exact stream target.
     */
    public ReplayResult replayDirect(
            DeserializingMessage event,
            Entity<?> target,
            ModelMetadata.HandlerMethod handler,
            String targetModelId) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetModelId, "targetModelId");
        Executable executable = handler.executable();
        Object receiver =
                executable instanceof Constructor<?>
                || Modifier.isStatic(executable.getModifiers())
                        ? null : target.get();
        if (receiver == null
            && !(executable instanceof Constructor<?>)
            && !Modifier.isStatic(
                    executable.getModifiers())) {
            return new ReplayResult(false, target.get());
        }
        Object result = event.apply(
                ignored ->
                        directInvokers.computeIfAbsent(
                                                executable,
                                                ModelEventReplayer::directInvoker)
                                .invoke(
                                        receiver,
                                        1,
                                        ignoredParameter ->
                                                event.getPayload()));
        Class<?> targetType =
                handler.targetModelTypes().getFirst();
        if (result != null) {
            if (!targetType.isInstance(result)) {
                throw new IllegalStateException(
                        "Apply %s returned %s instead of %s"
                                .formatted(
                                        executable.toGenericString(),
                                        result.getClass().getName(),
                                        targetType.getName()));
            }
            ModelMetadata resultMetadata = ModelMetadata.of(result.getClass());
            Object id = resultMetadata.entityId().orElseThrow().read(result);
            if (id == null
                || !targetModelId.equals(
                        resultMetadata.parentScopedEntityId()
                                ? resultMetadata.repositoryId(id, result)
                                : resultMetadata.repositoryId(id))) {
                throw new IllegalStateException(
                        "Apply %s returned model '%s', which is not replay target '%s'"
                                .formatted(
                                        executable.toGenericString(),
                                        id,
                                        targetModelId));
            }
        }
        return new ReplayResult(true, result);
    }

    private static MemberInvoker directInvoker(Executable executable) {
        return asInvoker(executable);
    }

    private static boolean compatible(
            Class<?> left,
            Class<?> right) {
        return left.isAssignableFrom(right)
               || right.isAssignableFrom(left);
    }

    /**
     * Result of replaying one stored event for one target model.
     */
    public record ReplayResult(boolean applied, Object value) {
    }
}
