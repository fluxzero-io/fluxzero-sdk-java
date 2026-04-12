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

package io.fluxzero.sdk.configuration.spring;

import io.fluxzero.sdk.tracking.TrackSelf;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring {@link org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor} that detects classes
 * annotated with {@link TrackSelf}
 * and registers them as {@link FluxzeroPrototype} beans for use by Fluxzero.
 * <p>
 * The scan follows Spring's {@link ComponentScan} boundaries so that applications control which self-tracked payload
 * types become active without exposing those payload types as regular Spring beans.
 * <p>
 * All detected {@code @TrackSelf} types are wrapped as {@link FluxzeroPrototype} and registered as Spring bean
 * definitions, allowing Fluxzero to discover and register them as self-tracking projections at runtime.
 *
 * <h2>Usage</h2>
 * To enable self-tracking on a class use e.g.:
 * <pre>{@code
 * @TrackSelf
 * public interface UserUpdate {
 *     UserId getUserId();
 *
 *     @HandleCommand
 *     default void handle() {
 *         Fluxzero.loadAggregate(getUserId()).assertAndApply(this);
 *     }
 * }
 * }</pre>
 * <p>
 * Make sure Spring picks up this processor, for example by including
 * {@link FluxzeroSpringConfig} in your configuration:
 * <pre>{@code
 * @SpringBootApplication
 * @Import(FluxzeroSpringConfig.class)
 * public class MyApp { ... }
 * }</pre>
 *
 * @see TrackSelf
 * @see StatefulPostProcessor
 * @see FluxzeroPrototype
 * @see FluxzeroSpringConfig
 */
@Slf4j
public class TrackSelfPostProcessor extends ComponentScanPrototypePostProcessor {
    @Override
    protected Class<TrackSelf> getTargetAnnotation() {
        return TrackSelf.class;
    }

    @Override
    protected String getBeanNameSuffix() {
        return "$$SelfTracked";
    }
}
