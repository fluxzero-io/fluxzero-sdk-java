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

import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.Stateful;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

import java.util.Arrays;
import java.util.Objects;

import static org.springframework.beans.factory.support.BeanDefinitionBuilder.genericBeanDefinition;

/**
 * Spring {@link BeanDefinitionRegistryPostProcessor} that automatically detects beans annotated with {@link Stateful}
 * and registers them as {@link FluxzeroPrototype} definitions for use in Fluxzero.
 * <p>
 * This enables Fluxzero to treat prototype-scoped, stateful beans as handler instances that are discovered and
 * managed at runtime by the handler registry (via the {@link HandlerFactory}).
 *
 * <h2>Usage</h2>
 * To use this mechanism, annotate a Spring bean with {@code @Stateful} and ensure that Spring picks up this processor,
 * for example via {@link FluxzeroSpringConfig}.
 *
 * @see Stateful
 * @see FluxzeroPrototype
 * @see FluxzeroSpringConfig
 */
@Slf4j
public class StatefulPostProcessor implements BeanDefinitionRegistryPostProcessor {

    /**
     * Scans for beans annotated with {@link Stateful}, wraps each of them in a {@link FluxzeroPrototype}, and registers
     * them programmatically as Spring bean definitions.
     * <p>
     * If the bean factory does not support dynamic registration (i.e., is not a {@link BeanDefinitionRegistry}),
     * a warning is logged and the operation is skipped.
     *
     * @param beanFactory the current {@link ConfigurableListableBeanFactory}
     */
    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
            log.warn("Cannot register Spring beans dynamically! @Stateful annotations will be ignored.");
            return;
        }
        Arrays.stream(beanFactory.getBeanNamesForAnnotation(Stateful.class))
                .map(beanFactory::getType).filter(Objects::nonNull)
                .map(FluxzeroPrototype::new)
                .forEach(prototype -> registry.registerBeanDefinition(
                         prototype.getType().getName()+ "$$Stateful",
                         genericBeanDefinition(FluxzeroPrototype.class, () -> prototype).getBeanDefinition()));
    }

    /**
     * No-op. This implementation does not modify the registry at this phase.
     *
     * @param registry the {@link BeanDefinitionRegistry}
     */
    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        // no-op
    }
}