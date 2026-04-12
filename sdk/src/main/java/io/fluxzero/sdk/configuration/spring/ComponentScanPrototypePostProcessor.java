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

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.context.annotation.TypeFilterUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.springframework.beans.factory.support.BeanDefinitionBuilder.genericBeanDefinition;

/**
 * Base post-processor that discovers Fluxzero handler types within the application's {@link ComponentScan} scope
 * without exposing those types themselves as injectable Spring beans.
 * Spring's classpath scanner still applies the same conditional matching that regular component scanning would use,
 * including type-level {@link ConditionalOnProperty} and {@link ConditionalOnMissingProperty} annotations.
 */
@Slf4j
abstract class ComponentScanPrototypePostProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {
    private Environment environment = new StandardEnvironment();

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
            log.warn("Cannot register Spring beans dynamically! @{} annotations will be ignored.",
                     getTargetAnnotation().getSimpleName());
            return;
        }

        ResourceLoader resourceLoader = new PathMatchingResourcePatternResolver(beanFactory.getBeanClassLoader());
        findCandidateTypes(beanFactory, registry, resourceLoader)
                .map(FluxzeroPrototype::new)
                .forEach(prototype -> {
                    String beanName = prototype.getType().getName() + getBeanNameSuffix();
                    if (!registry.containsBeanDefinition(beanName)) {
                        registry.registerBeanDefinition(
                                beanName,
                                genericBeanDefinition(FluxzeroPrototype.class, () -> prototype).getBeanDefinition());
                    }
                });
    }

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        // no-op
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    protected abstract Class<? extends Annotation> getTargetAnnotation();

    protected abstract String getBeanNameSuffix();

    private Stream<Class<?>> findCandidateTypes(ConfigurableListableBeanFactory beanFactory,
                                                BeanDefinitionRegistry registry,
                                                ResourceLoader resourceLoader) {
        return Arrays.stream(beanFactory.getBeanDefinitionNames())
                .map(beanFactory::getType)
                .filter(Objects::nonNull)
                .flatMap(type -> AnnotatedElementUtils.getMergedRepeatableAnnotations(type, ComponentScan.class)
                        .stream()
                        .flatMap(componentScan -> scan(type, componentScan, registry, resourceLoader)))
                .distinct();
    }

    private Stream<Class<?>> scan(Class<?> owner,
                                  ComponentScan componentScan,
                                  BeanDefinitionRegistry registry,
                                  ResourceLoader resourceLoader) {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false, environment) {
                    @Override
                    protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        provider.setResourceLoader(resourceLoader);
        provider.setResourcePattern(componentScan.resourcePattern());
        applyFilters(provider, componentScan, registry, resourceLoader);
        if (componentScan.useDefaultFilters()) {
            provider.addIncludeFilter(new AnnotationTypeFilter(getTargetAnnotation()));
        }

        return basePackages(owner, componentScan)
                .flatMap(basePackage -> provider.findCandidateComponents(basePackage).stream())
                .map(this::extractBeanClass)
                .filter(Objects::nonNull);
    }

    private Stream<String> basePackages(Class<?> owner, ComponentScan componentScan) {
        var basePackages = Stream.concat(
                Arrays.stream(componentScan.basePackages()),
                Arrays.stream(componentScan.basePackageClasses()).map(Class::getPackageName))
                .filter(packageName -> !packageName.isBlank())
                .distinct()
                .toList();
        return basePackages.isEmpty() ? Stream.of(owner.getPackageName()) : basePackages.stream();
    }

    private void applyFilters(ClassPathScanningCandidateComponentProvider provider,
                              ComponentScan componentScan,
                              BeanDefinitionRegistry registry,
                              ResourceLoader resourceLoader) {
        Arrays.stream(componentScan.excludeFilters())
                .forEach(filter -> addFilters(provider::addExcludeFilter, filter, registry, resourceLoader));
        Arrays.stream(componentScan.includeFilters())
                .forEach(filter -> addFilters(provider::addIncludeFilter, filter, registry, resourceLoader));
    }

    private void addFilters(Consumer<org.springframework.core.type.filter.TypeFilter> sink,
                            ComponentScan.Filter filter,
                            BeanDefinitionRegistry registry,
                            ResourceLoader resourceLoader) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(AnnotationUtils.getAnnotationAttributes(filter));
        TypeFilterUtils.createTypeFiltersFor(attributes, environment, resourceLoader, registry).forEach(sink);

        if (Arrays.stream(filter.classes()).anyMatch(Component.class::equals)
            || Arrays.stream(filter.value()).anyMatch(Component.class::equals)) {
            sink.accept(new AnnotationTypeFilter(getTargetAnnotation()));
        }
    }

    private Class<?> extractBeanClass(BeanDefinition beanDefinition) {
        try {
            return ((ScannedGenericBeanDefinition) beanDefinition)
                    .resolveBeanClass(Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
