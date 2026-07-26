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
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Reuses the model action evaluator for one stored event during model reconstruction.
 * <p>
 * Assertions and interceptors are deliberately not supplied by the repository during replay. Only the exact
 * {@code @Apply} transition that originally targeted the reconstructed model is selected.
 */
public final class ModelEventReplayer {
    private final ModelActionEngine engine;

    public ModelEventReplayer(
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        engine = new ModelActionEngine(parameterResolvers);
    }

    /**
     * Replays one already-intercepted stored event and returns its target value.
     */
    public ReplayResult replay(
            DeserializingMessage event,
            ModelActionContext context,
            Collection<ModelMetadata.HandlerMethod> handlers,
            String targetModelId) {
        Objects.requireNonNull(targetModelId, "targetModelId");
        ModelActionEngine.Evaluation evaluation =
                engine.evaluate(event, context, handlers);
        ModelActionEngine.Transition selected = null;
        for (ModelActionEngine.Transition transition : evaluation.transitions()) {
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
     * Result of replaying one stored event for one target model.
     */
    public record ReplayResult(boolean applied, Object value) {
    }
}
