/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.common.handling;

import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.silentTest;
import static io.fluxzero.common.reflection.ReflectionUtils.ensureAccessible;
import static io.fluxzero.common.reflection.ReflectionUtils.getAllMethods;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;

/**
 * Utility for inspecting and constructing handler components from annotated methods.
 * <p>
 * The {@code HandlerInspector} scans classes for methods that match handler criteria (e.g., {@code @HandleCommand}),
 * and constructs {@link Handler}, {@link HandlerMatcher}, and {@link HandlerInvoker} instances based on those methods.
 * It supports both stateless (singleton) and stateful handlers by accepting target suppliers and parameter resolvers.
 * </p>
 *
 * <p>
 * This class is the core of the handler discovery and resolution mechanism in Fluxzero, bridging the gap between
 * annotated user-defined classes and runtime message dispatch infrastructure.
 * </p>
 *
 * <h2>Capabilities</h2>
 * <ul>
 *     <li>Detect handler methods on a class based on annotations and signatures</li>
 *     <li>Create {@link Handler} instances that provide lifecycle-aware invocation logic</li>
 *     <li>Build {@link HandlerMatcher}s that analyze message applicability</li>
 *     <li>Support parameter resolution for dependency injection and message binding</li>
 * </ul>
 *
 * @see Handler
 * @see HandlerMatcher
 * @see HandlerInvoker
 */
public class HandlerInspector {

    /**
     * Returns whether the given class contains at least one method or constructor that matches the handler
     * configuration.
     *
     * @param targetClass          the class to inspect
     * @param handlerConfiguration the handler configuration to match against
     * @return {@code true} if at least one method or constructor is a valid handler
     */
    public static boolean hasHandlerMethods(Class<?> targetClass,
                                            HandlerConfiguration<?> handlerConfiguration) {
        return concat(getAllMethods(targetClass).stream(), stream(targetClass.getConstructors()))
                .anyMatch(m -> handlerConfiguration.methodMatches(targetClass, m));
    }

    /**
     * Creates a {@link Handler} for the given target object and annotation type. This variant uses a default no-op
     * parameter resolver.
     *
     * @param target           the handler instance
     * @param methodAnnotation the annotation to detect handler methods (e.g. {@code @HandleCommand})
     * @param <M>              the message type
     * @return a new {@code Handler} instance
     */
    public static <M> Handler<M> createHandler(Object target, Class<? extends Annotation> methodAnnotation) {
        return createHandler(target, methodAnnotation, List.of((p, a) -> o -> o));
    }

    /**
     * Creates a {@link Handler} backed by a target supplier and parameter resolvers. Suitable for stateful handlers
     * where the instance depends on the message content.
     *
     * @param target             the handler instance
     * @param parameterResolvers resolvers for handler method parameters
     * @param methodAnnotation   the annotation to detect handler methods (e.g. {@code @HandleCommand})
     * @param <M>                the message type
     * @return a handler capable of resolving and invoking methods on the target
     */
    public static <M> Handler<M> createHandler(Object target, Class<? extends Annotation> methodAnnotation,
                                               List<ParameterResolver<? super M>> parameterResolvers) {
        return createHandler(target, parameterResolvers,
                             HandlerConfiguration.builder().methodAnnotation(methodAnnotation).build());
    }

    /**
     * Creates a {@link Handler} backed by a target supplier and parameter resolvers. Suitable for stateful handlers
     * where the instance depends on the message content.
     *
     * @param target             the handler instance
     * @param parameterResolvers resolvers for handler method parameters
     * @param config             handler configuration settings
     * @param <M>                the message type
     * @return a handler capable of resolving and invoking methods on the target
     */
    public static <M> Handler<M> createHandler(Object target, List<ParameterResolver<? super M>> parameterResolvers,
                                               HandlerConfiguration<? super M> config) {
        return DefaultHandler.forTarget(
                target.getClass(), target, inspect(target.getClass(), parameterResolvers, config));
    }

    /**
     * Creates a {@link Handler} backed by a target supplier and parameter resolvers. Suitable for stateful handlers
     * where the instance depends on the message content.
     *
     * @param targetSupplier     provides the handler instance to use for a given message
     * @param targetClass        the class containing handler methods
     * @param parameterResolvers resolvers for handler method parameters
     * @param config             handler configuration settings
     * @param <M>                the message type
     * @return a handler capable of resolving and invoking methods on the target
     */
    public static <M> Handler<M> createHandler(Function<M, ?> targetSupplier, Class<?> targetClass,
                                               List<ParameterResolver<? super M>> parameterResolvers,
                                               HandlerConfiguration<? super M> config) {
        return new DefaultHandler<>(targetClass, targetSupplier, inspect(targetClass, parameterResolvers, config));
    }

    /**
     * Inspects the given class for methods matching the specified annotation and builds a {@link HandlerMatcher}.
     * <p>
     * This matcher can later be used to resolve a {@link HandlerInvoker} for a target object and message.
     * </p>
     *
     * @param targetClass        the class containing handler methods
     * @param parameterResolvers resolvers for handler method parameters
     * @param methodAnnotation   the annotation to detect handler methods (e.g. {@code @HandleCommand})
     */
    public static <M> HandlerMatcher<Object, M> inspect(Class<?> targetClass,
                                                        List<ParameterResolver<? super M>> parameterResolvers,
                                                        Class<? extends Annotation> methodAnnotation) {
        return inspect(targetClass, parameterResolvers,
                       HandlerConfiguration.builder().methodAnnotation(methodAnnotation).build());
    }

    /**
     * Inspects the given class for methods matching the specified annotation and builds a {@link HandlerMatcher}.
     * <p>
     * This matcher can later be used to resolve a {@link HandlerInvoker} for a target object and message.
     * </p>
     *
     * @param targetClass        the class containing handler methods
     * @param parameterResolvers resolvers for handler method parameters
     * @param config             handler configuration settings
     */
    public static <M> HandlerMatcher<Object, M> inspect(Class<?> targetClass,
                                                        List<ParameterResolver<? super M>> parameterResolvers,
                                                        HandlerConfiguration<? super M> config) {
        return new ObjectHandlerMatcher<>(
                concat(getAllMethods(targetClass).stream(), stream(targetClass.getDeclaredConstructors()))
                        .filter(m -> config.methodMatches(targetClass, m))
                        .flatMap(m -> Stream.of(
                                new MethodHandlerMatcher<>(m, targetClass, parameterResolvers, config)))
                        .sorted(MethodHandlerMatcher.comparator(config)).collect(toList()),
                config.invokeMultipleMethods());
    }

    /**
     * A matcher that encapsulates metadata and resolution logic for a single handler method or constructor.
     * <p>
     * Resolves parameter values using {@link ParameterResolver}s and builds a {@link HandlerInvoker}
     * for invoking the method on a given target instance.
     * </p>
     *
     * <p>
     * This matcher supports prioritization, specificity analysis, and filtering based on annotations and parameters.
     * </p>
     */
    @Getter
    public static class MethodHandlerMatcher<M> implements HandlerMatcher<Object, M> {
        protected static Comparator<MethodHandlerMatcher<?>> comparator(HandlerConfiguration<?> config) {
            return (left, right) -> compare(
                    left.getPriority(),
                    new Specificity(left.getClassForSpecificity(), Integer.MAX_VALUE),
                    left.getParameterCount(),
                    left.getMethodIndex(),
                    left.getExecutable(),
                    right.getPriority(),
                    new Specificity(right.getClassForSpecificity(), Integer.MAX_VALUE),
                    right.getParameterCount(),
                    right.getMethodIndex(),
                    right.getExecutable(),
                    config.samePriorityMethodComparator());
        }

        private final int methodIndex;
        private final Executable executable;
        private final Parameter[] parameters;
        private final int parameterCount;
        private final boolean staticMethod;
        private final MemberInvoker invoker;
        private final boolean hasReturnType;
        private final Class<?> classForSpecificity;
        private final int lowestSpecificityPriority;
        private final Annotation methodAnnotation;
        private final Class<? extends Annotation> methodAnnotationType;
        private final int priority;
        private final boolean passive;
        private final Class<?> targetClass;
        private final List<ParameterResolver<? super M>> parameterResolvers;
        private final HandlerConfiguration<? super M> config;
        private final MessageFilter<? super M> messageFilter;
        private final List<ParameterResolverPlan<M>>[] parameterResolverPlans;
        private final List<ParameterResolverPlan<M>>[] specificityResolverPlans;
        private final boolean onlyPreparedParameterResolvers;
        private final boolean validateMethodInvocation;
        private boolean cacheKeyedResolvers = true;
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private final Optional<Object> emptyResult = Optional.of(void.class);

        public MethodHandlerMatcher(Executable executable, Class<?> targetClass,
                                    List<ParameterResolver<? super M>> parameterResolvers,
                                    @NonNull HandlerConfiguration<? super M> config) {
            this.targetClass = targetClass;
            this.parameterResolvers = parameterResolvers.stream()
                    .filter(silentTest(r -> r.mayApply(executable, targetClass))).toList();
            this.config = config;
            this.methodIndex = executable instanceof Method ? methodIndex((Method) executable, targetClass) : 0;
            this.executable = ensureAccessible(executable);
            this.parameters = this.executable.getParameters();
            this.parameterCount = this.parameters.length;
            this.staticMethod = Modifier.isStatic(this.executable.getModifiers());
            this.hasReturnType = ReflectionUtils.hasReturnType(executable);
            this.methodAnnotation = config.getAnnotation(executable).orElse(null);
            this.methodAnnotationType = Optional.ofNullable(this.methodAnnotation).map(Annotation::annotationType)
                    .orElse(null);
            this.messageFilter = config.messageFilter().prepare(this.executable, this.methodAnnotationType, targetClass);
            this.parameterResolverPlans = prepareParameterResolvers();
            this.specificityResolverPlans = prepareSpecificityResolverPlans();
            this.onlyPreparedParameterResolvers = onlyPreparedParameterResolvers(this.parameterResolverPlans);
            this.validateMethodInvocation = config.methodInvocationValidator() != MethodInvocationValidator.noOp();
            this.classForSpecificity = computeClassForSpecificity();
            this.lowestSpecificityPriority = this.parameterResolvers.stream()
                    .filter(ParameterResolver::determinesSpecificity)
                    .mapToInt(ParameterResolver::specificityPriority).min().orElse(Integer.MAX_VALUE);
            this.priority = getPriority(methodAnnotation);
            this.passive = isPassive(methodAnnotation);
            this.invoker = DefaultMemberInvoker.asInvoker(this.executable);
        }

        @Override
        public boolean canHandle(M message) {
            return prepareInvokerFunction(message) != null;
        }

        @Override
        public Stream<Executable> matchingMethods(M message) {
            return canHandle(message) ? Stream.of(executable) : Stream.empty();
        }

        @SuppressWarnings("unchecked")
        protected Optional<Function<Object, HandlerInvoker>> prepareInvoker(M m) {
            return Optional.ofNullable(prepareInvokerOrNull(m));
        }

        @SuppressWarnings("unchecked")
        protected Function<Object, HandlerInvoker> prepareInvokerOrNull(M m) {
            if (!messageFilter.test(m, executable, methodAnnotationType, targetClass)) {
                return null;
            }

            if (parameterCount == 0) {
                return this::createNoParameterInvoker;
            }

            if (onlyPreparedParameterResolvers) {
                return target -> createPreparedParameterInvoker(target, m);
            }

            if (!validateMethodInvocation && parameterCount == 1) {
                Function<? super M, Object> matchingResolver = resolveDynamicParameterResolver(m, 0);
                return matchingResolver == null ? null : target -> createSingleDynamicParameterInvoker(
                        target, m, matchingResolver);
            }

            Function<? super M, Object>[] matchingResolvers = resolveDynamicParameterResolvers(m);
            return matchingResolvers == null ? null : target -> createDynamicParameterInvoker(target, m,
                                                                                              matchingResolvers);
        }

        private HandlerInvoker createInvokerOrNull(Object target, M m) {
            if (!messageFilter.test(m, executable, methodAnnotationType, targetClass)) {
                return null;
            }
            if (parameterCount == 0) {
                return createNoParameterInvoker(target);
            }
            if (onlyPreparedParameterResolvers) {
                return createPreparedParameterInvoker(target, m);
            }
            if (!validateMethodInvocation && parameterCount == 1) {
                Function<? super M, Object> matchingResolver = resolveDynamicParameterResolver(m, 0);
                return matchingResolver == null ? null
                        : createSingleDynamicParameterInvoker(target, m, matchingResolver);
            }
            Function<? super M, Object>[] matchingResolvers = resolveDynamicParameterResolvers(m);
            return matchingResolvers == null ? null : createDynamicParameterInvoker(target, m, matchingResolvers);
        }

        @SuppressWarnings("unchecked")
        private Function<? super M, Object>[] resolveDynamicParameterResolvers(M m) {
            Function<? super M, Object>[] matchingResolvers = new Function[parameterCount];
            for (int i = 0; i < parameterCount; i++) {
                matchingResolvers[i] = resolveDynamicParameterResolver(m, i);
                if (matchingResolvers[i] == null) {
                    return null;
                }
            }
            return matchingResolvers;
        }

        private Function<? super M, Object> resolveDynamicParameterResolver(M m, int parameterIndex) {
            return cacheKeyedResolvers ? resolveKeyedParameterResolver(m, parameterIndex)
                    : resolveLegacyParameterResolver(m, parameterIndex);
        }

        private Function<? super M, Object> resolveKeyedParameterResolver(M m, int parameterIndex) {
            Parameter p = parameters[parameterIndex];
            for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                if (plan.preparedResolver() != null) {
                    return plan.preparedResolver();
                }
                ParameterResolver<? super M> r = plan.resolver();
                KeyedParameterResolver.Resolution<M> keyedResolution = plan.resolveForKey(p, methodAnnotation, m);
                if (keyedResolution != null) {
                    if (!keyedResolution.matched()) {
                        continue;
                    }
                    return keyedResolution.resolver();
                }
                if (r instanceof PreparedParameterResolver<? super M> preparedParameterResolver) {
                    Function<? super M, Object> preparedResolver = preparedParameterResolver.resolveIfPossible(
                            p, methodAnnotation, m);
                    if (preparedResolver != null) {
                        return preparedResolver;
                    }
                    if (r.matches(p, methodAnnotation, m)) {
                        return null;
                    }
                } else if (r.matches(p, methodAnnotation, m)) {
                    if (!r.test(m, p)) {
                        return null;
                    }
                    return r.resolve(p, methodAnnotation);
                }
            }
            return null;
        }

        private Function<? super M, Object> resolveLegacyParameterResolver(M m, int parameterIndex) {
            Parameter p = parameters[parameterIndex];
            for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                if (plan.preparedResolver() != null) {
                    return plan.preparedResolver();
                }
                ParameterResolver<? super M> resolver = plan.resolver();
                if (resolver instanceof PreparedParameterResolver<? super M> preparedResolver) {
                    Function<? super M, Object> function = preparedResolver.resolveIfPossible(
                            p, methodAnnotation, m);
                    if (function != null) {
                        return function;
                    }
                    if (resolver.matches(p, methodAnnotation, m)) {
                        return null;
                    }
                } else if (resolver.matches(p, methodAnnotation, m)) {
                    if (!resolver.test(m, p)) {
                        return null;
                    }
                    return resolver.resolve(p, methodAnnotation);
                }
            }
            return null;
        }

        private HandlerInvoker createNoParameterInvoker(Object target) {
            return new MethodHandlerInvoker() {
                @Override
                public Object invoke(BiFunction<Object, Object, Object> combiner) {
                    return invoker.invoke(target);
                }
            };
        }

        private HandlerInvoker createPreparedParameterInvoker(Object target, M m) {
            if (!validateMethodInvocation) {
                return createUnvalidatedPreparedParameterInvoker(target, m);
            }
            return new MethodHandlerInvoker() {
                @Override
                public Object invoke(BiFunction<Object, Object, Object> combiner) {
                    Object[] args = new Object[parameterCount];
                    for (int i = 0; i < parameterCount; i++) {
                        args[i] = parameterResolverPlans[i].getFirst().preparedResolver().apply(m);
                    }
                    config.methodInvocationValidator().validate(m, target, executable, args);
                    return invoker.invoke(target, parameterCount, i -> args[i]);
                }
            };
        }

        private HandlerInvoker createUnvalidatedPreparedParameterInvoker(Object target, M m) {
            return new UnvalidatedPreparedParameterInvoker(target, m);
        }

        private HandlerInvoker createSingleDynamicParameterInvoker(
                Object target, M m, Function<? super M, Object> matchingResolver) {
            return new UnvalidatedSingleDynamicParameterInvoker(target, m, matchingResolver);
        }

        private HandlerInvoker createDynamicParameterInvoker(
                Object target, M m, Function<? super M, Object>[] matchingResolvers) {
            if (!validateMethodInvocation) {
                return createUnvalidatedDynamicParameterInvoker(target, m, matchingResolvers);
            }
            return new MethodHandlerInvoker() {
                @Override
                public Object invoke(BiFunction<Object, Object, Object> combiner) {
                    Object[] args = new Object[parameterCount];
                    for (int i = 0; i < parameterCount; i++) {
                        args[i] = matchingResolvers[i].apply(m);
                    }
                    config.methodInvocationValidator().validate(m, target, executable, args);
                    return invoker.invoke(target, parameterCount, i -> args[i]);
                }
            };
        }

        private HandlerInvoker createUnvalidatedDynamicParameterInvoker(
                Object target, M m, Function<? super M, Object>[] matchingResolvers) {
            return new UnvalidatedDynamicParameterInvoker(target, m, matchingResolvers);
        }

        @SuppressWarnings("unchecked")
        private List<ParameterResolverPlan<M>>[] prepareParameterResolvers() {
            List<ParameterResolverPlan<M>>[] result = new List[parameterCount];
            for (int i = 0; i < parameterCount; i++) {
                Parameter p = parameters[i];
                List<ParameterResolverPlan<M>> plans = new ArrayList<>();
                for (ParameterResolver<? super M> resolver : parameterResolvers) {
                    Function<? super M, Object> preparedResolver = resolver.prepare(p, methodAnnotation);
                    plans.add(new ParameterResolverPlan<>(resolver, preparedResolver, p, methodAnnotation));
                    if (preparedResolver != null) {
                        break;
                    }
                }
                result[i] = List.copyOf(plans);
            }
            return result;
        }

        private boolean onlyPreparedParameterResolvers(List<ParameterResolverPlan<M>>[] plans) {
            if (plans.length == 0) {
                return false;
            }
            for (List<ParameterResolverPlan<M>> plan : plans) {
                if (plan.size() != 1 || plan.getFirst().preparedResolver() == null) {
                    return false;
                }
            }
            return true;
        }

        @SuppressWarnings("unchecked")
        private List<ParameterResolverPlan<M>>[] prepareSpecificityResolverPlans() {
            List<ParameterResolverPlan<M>>[] result = new List[parameterCount];
            for (int i = 0; i < parameterCount; i++) {
                Map<ParameterResolver<? super M>, ParameterResolverPlan<M>> existingPlans = new IdentityHashMap<>();
                for (ParameterResolverPlan<M> plan : parameterResolverPlans[i]) {
                    existingPlans.put(plan.resolver(), plan);
                }
                List<ParameterResolverPlan<M>> plans = new ArrayList<>();
                for (ParameterResolver<? super M> resolver : parameterResolvers) {
                    if (resolver.determinesSpecificity()) {
                        ParameterResolverPlan<M> existing = existingPlans.get(resolver);
                        plans.add(existing == null
                                          ? new ParameterResolverPlan<>(resolver, null, parameters[i], methodAnnotation)
                                          : existing);
                    }
                }
                result[i] = List.copyOf(plans);
            }
            return result;
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(Object target, M m) {
            return Optional.ofNullable(getInvokerOrNull(target, m));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(Object target, M m) {
            if (target == null
                    ? !(executable instanceof Constructor) && !staticMethod
                    : !(executable instanceof Method) || staticMethod) {
                return null;
            }
            if (getClass() == MethodHandlerMatcher.class) {
                return createInvokerOrNull(target, m);
            }
            Function<Object, HandlerInvoker> preparedInvoker = prepareInvokerFunction(m);
            return preparedInvoker == null ? null : preparedInvoker.apply(target);
        }

        @Override
        public HandlerMethod<M> bindHandlerMethod(Object target) {
            if (getClass() != MethodHandlerMatcher.class
                || !targetCanBeInvoked(target)
                || validateMethodInvocation
                || parameterCount != 0 && !onlyPreparedParameterResolvers) {
                return null;
            }
            return new BoundHandlerMethod(target);
        }

        @Override
        public HandlerMethodPlan<M> prepareHandlerMethod(Object target, M message) {
            return prepareHandlerMethod(target, asHandlerInput(message));
        }

        private HandlerMethodPlan<M> prepareHandlerMethod(Object target, HandlerInput<M> input) {
            if (getClass() != MethodHandlerMatcher.class
                || !targetCanBeInvoked(target)
                || validateMethodInvocation
                || !messageFilter.isAlwaysTrue()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Function<? super HandlerInput<M>, Object>[] resolvers = new Function[parameterCount];
            for (int i = 0; i < parameterCount; i++) {
                Function<? super HandlerInput<M>, Object> resolver = prepareInputResolver(input, i);
                if (resolver == null) {
                    return null;
                }
                resolvers[i] = resolver;
            }
            return new BoundHandlerMethodPlan(target, resolvers);
        }

        private HandlerMethodPlan<M> preparePayloadHandlerMethod(HandlerInput<M> input) {
            if (getClass() != MethodHandlerMatcher.class
                || !payloadTargetCanBeInvoked(input)
                || validateMethodInvocation
                || !messageFilter.isAlwaysTrue()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Function<? super HandlerInput<M>, Object>[] resolvers = new Function[parameterCount];
            for (int i = 0; i < parameterCount; i++) {
                Function<? super HandlerInput<M>, Object> resolver = prepareInputResolver(input, i);
                if (resolver == null) {
                    return null;
                }
                resolvers[i] = resolver;
            }
            return new PayloadHandlerMethodPlan(resolvers);
        }

        @Override
        public HandlerMethodPlanner<M> bindHandlerMethodPlanner(Object target) {
            if (getClass() != MethodHandlerMatcher.class) {
                return null;
            }
            return new HandlerMethodPlanner<>() {
                @Override
                public HandlerMethodApplicability<M> prepareApplicability(HandlerInput<M> input) {
                    return prepareInputApplicability(target, false, input);
                }

                @Override
                public Object getCacheKey(M message) {
                    return inputPlanCacheKey(asHandlerInput(message));
                }

                @Override
                public Object getCacheKey(HandlerInput<M> input) {
                    return inputPlanCacheKey(input);
                }

                @Override
                public HandlerMethodPreparation<M> prepare(M message) {
                    HandlerMethodPlan<M> plan = prepareHandlerMethod(target, message);
                    return plan == null ? HandlerMethodPreparation.noMatch()
                            : HandlerMethodPreparation.prepared(plan);
                }

                @Override
                public HandlerMethodPreparation<M> prepare(HandlerInput<M> input) {
                    HandlerMethodPlan<M> plan = prepareHandlerMethod(target, input);
                    return plan == null ? HandlerMethodPreparation.noMatch()
                            : HandlerMethodPreparation.prepared(plan);
                }

                @Override
                public boolean isPayloadClassKey(HandlerInput<M> input) {
                    return inputPlanPayloadClassKey(input);
                }

                @Override
                public boolean isNoMatchPayloadClassKey(HandlerInput<M> input) {
                    return inputPlanNoMatchPayloadClassKey(input);
                }
            };
        }

        @Override
        public HandlerMethodPlanner<M> bindPayloadHandlerMethodPlanner() {
            if (getClass() != MethodHandlerMatcher.class) {
                return null;
            }
            return new HandlerMethodPlanner<>() {
                @Override
                public HandlerMethodApplicability<M> prepareApplicability(HandlerInput<M> input) {
                    return prepareInputApplicability(null, true, input);
                }

                @Override
                public Object getCacheKey(M message) {
                    return null;
                }

                @Override
                public Object getCacheKey(HandlerInput<M> input) {
                    return payloadTargetCanBeInvoked(input) ? inputPlanCacheKey(input) : null;
                }

                @Override
                public HandlerMethodPreparation<M> prepare(M message) {
                    return HandlerMethodPreparation.unsupported();
                }

                @Override
                public HandlerMethodPreparation<M> prepare(HandlerInput<M> input) {
                    HandlerMethodPlan<M> plan = preparePayloadHandlerMethod(input);
                    return plan == null ? HandlerMethodPreparation.unsupported()
                            : HandlerMethodPreparation.prepared(plan);
                }

                @Override
                public boolean isPayloadClassKey(HandlerInput<M> input) {
                    return payloadTargetCanBeInvoked(input) && inputPlanPayloadClassKey(input);
                }

                @Override
                public boolean isNoMatchPayloadClassKey(HandlerInput<M> input) {
                    return payloadTargetCanBeInvoked(input) && inputPlanNoMatchPayloadClassKey(input);
                }
            };
        }

        private HandlerMethodApplicability<M> prepareInputApplicability(
                Object target, boolean payloadTarget, HandlerInput<M> input) {
            boolean targetMatches = payloadTarget ? payloadTargetCanBeInvoked(input) : targetCanBeInvoked(target);
            if (!targetMatches || hasPayloadClassNoMatch(input)) {
                return HandlerMethodApplicability.cacheable(
                        new InputPlanCacheKey(this, inputPayloadClass(input)), true,
                        HandlerMethodPreparation.noMatch());
            }
            if (validateMethodInvocation || !messageFilter.isAlwaysTrue()) {
                return HandlerMethodApplicability.unsupported();
            }
            return prepareCompleteInputApplicability(target, payloadTarget, input);
        }

        private Class<?> inputPayloadClass(HandlerInput<M> input) {
            Object payload = input.getPayload();
            return payload == null ? Void.class : payload.getClass();
        }

        @SuppressWarnings("unchecked")
        private boolean hasPayloadClassNoMatch(HandlerInput<M> input) {
            for (int parameterIndex = 0; parameterIndex < parameterResolverPlans.length; parameterIndex++) {
                Parameter parameter = parameters[parameterIndex];
                boolean completelyResolved = true;
                boolean matched = false;
                for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                    if (plan.resolver() instanceof HandlerInputResolver<?> rawResolver) {
                        HandlerInputResolver<M> resolver = (HandlerInputResolver<M>) rawResolver;
                        HandlerInputResolver.Resolution<M> resolution = resolver.prepareInput(
                                parameter, methodAnnotation, input);
                        if (resolution == null) {
                            completelyResolved = false;
                            break;
                        }
                        if (resolution.matched()) {
                            if (resolution.resolver() == null
                                && resolver.isPayloadClassKey(parameter, methodAnnotation, input)) {
                                return true;
                            }
                            matched = true;
                            break;
                        }
                        if (!resolver.isNoMatchPayloadClassKey(parameter, methodAnnotation, input)) {
                            completelyResolved = false;
                            break;
                        }
                        continue;
                    }
                    if (plan.preparedResolver() != null) {
                        matched = true;
                        break;
                    }
                    if (plan.resolver() instanceof KeyedParameterResolver<?> && !plan.isCacheKeyRelevant()) {
                        continue;
                    }
                    completelyResolved = false;
                    break;
                }
                if (completelyResolved && !matched) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        private HandlerMethodApplicability<M> prepareCompleteInputApplicability(
                Object target, boolean payloadTarget, HandlerInput<M> input) {
            Object key = this;
            boolean payloadClassKey = true;
            Function<? super HandlerInput<M>, Object>[] resolvers = new Function[parameterCount];
            for (int parameterIndex = 0; parameterIndex < parameterResolverPlans.length; parameterIndex++) {
                Parameter parameter = parameters[parameterIndex];
                boolean matched = false;
                for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                    if (plan.resolver() instanceof HandlerInputResolver<?> rawResolver) {
                        HandlerInputResolver<M> resolver = (HandlerInputResolver<M>) rawResolver;
                        Object resolverKey = resolver.getInputCacheKey(parameter, methodAnnotation, input);
                        if (resolverKey == null) {
                            return HandlerMethodApplicability.unsupported();
                        }
                        key = new InputPlanCacheKey(key, resolverKey);
                        HandlerInputResolver.Resolution<M> resolution = resolver.prepareInput(
                                parameter, methodAnnotation, input);
                        if (resolution == null) {
                            return HandlerMethodApplicability.unsupported();
                        }
                        if (!resolution.matched()) {
                            payloadClassKey &= resolver.isNoMatchPayloadClassKey(
                                    parameter, methodAnnotation, input);
                            continue;
                        }
                        payloadClassKey &= resolver.isPayloadClassKey(parameter, methodAnnotation, input);
                        if (resolution.resolver() == null) {
                            return HandlerMethodApplicability.cacheable(
                                    key, payloadClassKey, HandlerMethodPreparation.noMatch());
                        }
                        resolvers[parameterIndex] = resolution.resolver();
                        matched = true;
                        break;
                    }
                    if (plan.preparedResolver() != null) {
                        Function<? super M, Object> resolver = plan.preparedResolver();
                        resolvers[parameterIndex] = current -> resolver.apply(current.getMessage());
                        matched = true;
                        break;
                    }
                    if (plan.resolver() instanceof KeyedParameterResolver<?> && !plan.isCacheKeyRelevant()) {
                        continue;
                    }
                    return HandlerMethodApplicability.unsupported();
                }
                if (!matched) {
                    return HandlerMethodApplicability.cacheable(
                            key, payloadClassKey, HandlerMethodPreparation.noMatch());
                }
            }
            HandlerMethodPlan<M> plan = payloadTarget
                    ? new PayloadHandlerMethodPlan(resolvers) : new BoundHandlerMethodPlan(target, resolvers);
            return HandlerMethodApplicability.cacheable(
                    key, payloadClassKey, HandlerMethodPreparation.prepared(plan));
        }

        @SuppressWarnings("unchecked")
        private boolean inputPlanPayloadClassKey(HandlerInput<M> input) {
            for (int parameterIndex = 0; parameterIndex < parameterResolverPlans.length; parameterIndex++) {
                Parameter parameter = parameters[parameterIndex];
                boolean matched = false;
                for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                    if (!(plan.resolver() instanceof HandlerInputResolver<?> rawResolver)) {
                        return false;
                    }
                    HandlerInputResolver<M> resolver = (HandlerInputResolver<M>) rawResolver;
                    if (!resolver.isPayloadClassKey(parameter, methodAnnotation, input)) {
                        return false;
                    }
                    HandlerInputResolver.Resolution<M> resolution = resolver.prepareInput(
                            parameter, methodAnnotation, input);
                    if (resolution == null) {
                        return false;
                    }
                    if (resolution.matched()) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        @SuppressWarnings("unchecked")
        private boolean inputPlanNoMatchPayloadClassKey(HandlerInput<M> input) {
            for (int parameterIndex = 0; parameterIndex < parameterResolverPlans.length; parameterIndex++) {
                Parameter parameter = parameters[parameterIndex];
                boolean matched = false;
                for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                    if (!(plan.resolver() instanceof HandlerInputResolver<?> rawResolver)) {
                        return false;
                    }
                    HandlerInputResolver<M> resolver = (HandlerInputResolver<M>) rawResolver;
                    HandlerInputResolver.Resolution<M> resolution = resolver.prepareInput(
                            parameter, methodAnnotation, input);
                    if (resolution == null) {
                        return false;
                    }
                    if (resolution.matched()) {
                        if (!resolver.isPayloadClassKey(parameter, methodAnnotation, input)) {
                            return false;
                        }
                        matched = true;
                        break;
                    }
                    if (!resolver.isNoMatchPayloadClassKey(parameter, methodAnnotation, input)) {
                        return false;
                    }
                }
                if (!matched) {
                    return true;
                }
            }
            return false;
        }

        private Object inputPlanCacheKey(HandlerInput<M> input) {
            Object result = this;
            for (int parameterIndex = 0; parameterIndex < parameterResolverPlans.length; parameterIndex++) {
                Parameter parameter = parameters[parameterIndex];
                boolean matched = false;
                List<ParameterResolverPlan<M>> plans = parameterResolverPlans[parameterIndex];
                for (ParameterResolverPlan<M> plan : plans) {
                    if (!(plan.resolver() instanceof HandlerInputResolver<?> rawInputResolver)) {
                        return null;
                    }
                    @SuppressWarnings("unchecked")
                    HandlerInputResolver<M> inputResolver = (HandlerInputResolver<M>) rawInputResolver;
                    Object key = inputResolver.getInputCacheKey(parameter, methodAnnotation, input);
                    if (key == null) {
                        return null;
                    }
                    result = new InputPlanCacheKey(result, key);
                    HandlerInputResolver.Resolution<M> resolution = inputResolver.prepareInput(
                            parameter, methodAnnotation, input);
                    if (resolution == null) {
                        return null;
                    }
                    if (resolution.matched()) {
                        matched = true;
                        break;
                    }
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private Function<? super HandlerInput<M>, Object> prepareInputResolver(
                HandlerInput<M> input, int parameterIndex) {
            Parameter parameter = parameters[parameterIndex];
            for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                if (!(plan.resolver() instanceof HandlerInputResolver<?> rawResolver)) {
                    return null;
                }
                HandlerInputResolver<M> resolver = (HandlerInputResolver<M>) rawResolver;
                HandlerInputResolver.Resolution<M> resolution = resolver.prepareInput(
                        parameter, methodAnnotation, input);
                if (resolution == null) {
                    return null;
                }
                if (!resolution.matched()) {
                    continue;
                }
                return resolution.resolver();
            }
            return null;
        }

        private HandlerInput<M> asHandlerInput(M message) {
            return new HandlerInput<>() {
                @Override
                public Object getPayload() {
                    return message;
                }

                @Override
                public M getMessage() {
                    return message;
                }
            };
        }

        private boolean targetCanBeInvoked(Object target) {
            return target == null
                    ? executable instanceof Constructor || staticMethod
                    : executable instanceof Method && !staticMethod;
        }

        private boolean payloadTargetCanBeInvoked(HandlerInput<M> input) {
            return executable instanceof Method && !staticMethod && targetClass.isInstance(input.getPayload());
        }

        private Function<Object, HandlerInvoker> prepareInvokerFunction(M m) {
            return getClass() == MethodHandlerMatcher.class
                    ? prepareInvokerOrNull(m) : prepareInvoker(m).orElse(null);
        }

        protected Class<?> computeClassForSpecificity() {
            Class<?> handlerType = messageFilter.getLeastSpecificAllowedClass(
                    executable, methodAnnotationType).orElse(null);
            for (Parameter p : parameters) {
                for (ParameterResolver<? super M> r : parameterResolvers) {
                    if (r.determinesSpecificity()) {
                        Function<? super M, Object> resolver = r.resolve(p, methodAnnotation);
                        if (resolver != null) {
                            Class<?> parameterType = p.getType();
                            if (handlerType != null && !handlerType.isAssignableFrom(parameterType)) {
                                return handlerType;
                            }
                            return parameterType;
                        }
                    }
                }
            }
            return handlerType;
        }

        protected Specificity computeSpecificity(M message) {
            Specificity result = new Specificity(classForSpecificity, Integer.MAX_VALUE);
            for (int i = 0; i < parameters.length; i++) {
                Parameter p = parameters[i];
                for (ParameterResolverPlan<M> plan : specificityResolverPlans[i]) {
                    ParameterResolver<? super M> r = plan.resolver();
                    Function<? super M, Object> resolver = null;
                    KeyedParameterResolver.Resolution<M> keyedResolution =
                            cacheKeyedResolvers ? plan.resolveForKey(p, methodAnnotation, message) : null;
                    if (keyedResolution != null) {
                        resolver = keyedResolution.resolver();
                    } else if (r instanceof PreparedParameterResolver<? super M> preparedParameterResolver) {
                        resolver = preparedParameterResolver.resolveIfPossible(p, methodAnnotation, message);
                    } else if (r.matches(p, methodAnnotation, message) && r.test(message, p)) {
                        resolver = r.resolve(p, methodAnnotation);
                    }
                    if (resolver != null) {
                        Class<?> parameterType = p.getType();
                        Class<?> candidate = classForSpecificity != null
                                             && !classForSpecificity.isAssignableFrom(parameterType)
                                             ? classForSpecificity : parameterType;
                        if (result.type() == null
                            || r.specificityPriority() < result.priority()
                            || r.specificityPriority() == result.priority()
                               && ReflectionUtils.getClassSpecificityComparator()
                                       .compare(candidate, result.type()) < 0) {
                            result = new Specificity(candidate, r.specificityPriority());
                        }
                    }
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private Specificity computeInputSpecificity(HandlerInput<M> input) {
            Specificity result = new Specificity(classForSpecificity, Integer.MAX_VALUE);
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                for (ParameterResolverPlan<M> plan : specificityResolverPlans[i]) {
                    if (!(plan.resolver() instanceof HandlerInputResolver<?> rawResolver)) {
                        return null;
                    }
                    HandlerInputResolver<M> resolver = (HandlerInputResolver<M>) rawResolver;
                    HandlerInputResolver.Resolution<M> resolution = resolver.prepareInput(
                            parameter, methodAnnotation, input);
                    if (resolution == null) {
                        return null;
                    }
                    if (resolution.resolver() != null) {
                        Class<?> parameterType = parameter.getType();
                        Class<?> candidate = classForSpecificity != null
                                             && !classForSpecificity.isAssignableFrom(parameterType)
                                ? classForSpecificity : parameterType;
                        if (result.type() == null
                            || resolver.specificityPriority() < result.priority()
                            || resolver.specificityPriority() == result.priority()
                               && ReflectionUtils.getClassSpecificityComparator()
                                       .compare(candidate, result.type()) < 0) {
                            result = new Specificity(candidate, resolver.specificityPriority());
                        }
                    }
                }
            }
            return result;
        }

        protected int compareForMessage(MethodHandlerMatcher<M> other, M message) {
            return compare(
                    priority,
                    computeSpecificity(message),
                    parameterCount,
                    methodIndex,
                    executable,
                    other.priority,
                    other.computeSpecificity(message),
                    other.parameterCount,
                    other.methodIndex,
                    other.executable,
                    config.samePriorityMethodComparator());
        }

        protected int methodIndex(Method instanceMethod, Class<?> instanceType) {
            return ReflectionUtils.getAllMethods(instanceType).indexOf(instanceMethod);
        }

        @SneakyThrows
        protected int getPriority(Annotation annotation) {
            if (annotation == null) {
                return 0;
            }
            Optional<Method> match = Arrays.stream(annotation.annotationType().getMethods())
                    .filter(m -> m.getName().equals("priority")).findFirst();
            if (match.isPresent()) {
                return (int) match.get().invoke(annotation);
            }
            return 0;
        }

        @SuppressWarnings("unchecked")
        private boolean collectApplicabilityKeyResolvers(
                IdentityHashMap<KeyedParameterResolver<? super M>, Boolean> result) {
            if (!messageFilter.isAlwaysTrue()) {
                return false;
            }
            for (int i = 0; i < parameterResolverPlans.length; i++) {
                List<ParameterResolverPlan<M>> plans = parameterResolverPlans[i];
                for (ParameterResolverPlan<M> plan : plans) {
                    if (plan.preparedResolver() != null) {
                        break;
                    }
                    if (!(plan.resolver() instanceof KeyedParameterResolver<?> keyedResolver)) {
                        return false;
                    }
                    if (plan.isCacheKeyRelevant()) {
                        result.put((KeyedParameterResolver<? super M>) keyedResolver, Boolean.TRUE);
                    }
                }
            }
            for (int i = 0; i < specificityResolverPlans.length; i++) {
                List<ParameterResolverPlan<M>> plans = specificityResolverPlans[i];
                for (ParameterResolverPlan<M> plan : plans) {
                    if (!(plan.resolver() instanceof KeyedParameterResolver<?> keyedResolver)) {
                        return false;
                    }
                    if (plan.isCacheKeyRelevant()) {
                        result.put((KeyedParameterResolver<? super M>) keyedResolver, Boolean.TRUE);
                    }
                }
            }
            return true;
        }

        private void disableKeyedResolverCaching() {
            cacheKeyedResolvers = false;
        }

        @SneakyThrows
        protected boolean isPassive(Annotation annotation) {
            if (annotation == null) {
                return false;
            }
            Optional<Method> match = Arrays.stream(annotation.annotationType().getMethods())
                    .filter(m -> m.getName().equals("passive")).findFirst();
            if (match.isPresent()) {
                return (boolean) match.get().invoke(annotation);
            }
            return false;
        }

        protected record Specificity(Class<?> type, int priority) {
        }

        protected static int compare(int leftPriority, Specificity leftSpecificity, int leftParameterCount,
                                     int leftMethodIndex, Executable leftExecutable,
                                     int rightPriority, Specificity rightSpecificity,
                                     int rightParameterCount, int rightMethodIndex,
                                     Executable rightExecutable,
                                     Comparator<Executable> samePriorityMethodComparator) {
            int result = Integer.compare(rightPriority, leftPriority);
            if (result != 0) {
                return result;
            }
            result = samePriorityMethodComparator.compare(leftExecutable, rightExecutable);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(leftSpecificity.priority(), rightSpecificity.priority());
            if (result != 0) {
                return result;
            }
            result = ReflectionUtils.getClassSpecificityComparator().compare(
                    leftSpecificity.type(), rightSpecificity.type());
            if (result != 0) {
                return result;
            }
            result = Integer.compare(rightParameterCount, leftParameterCount);
            if (result != 0) {
                return result;
            }
            return Integer.compare(leftMethodIndex, rightMethodIndex);
        }

        @AllArgsConstructor
        protected abstract class MethodHandlerInvoker implements HandlerInvoker {

            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Executable getMethod() {
                return executable;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <A extends Annotation> A getMethodAnnotation() {
                return (A) methodAnnotation;
            }

            @Override
            public boolean expectResult() {
                return hasReturnType;
            }

            @Override
            public boolean isPassive() {
                return passive;
            }

            @Override
            public String toString() {
                return Optional.ofNullable(targetClass).map(c -> {
                    String simpleName = c.getSimpleName();
                    return String.format("\"%s\"", simpleName.isEmpty() ? c : simpleName);
                }).orElse("MethodHandlerInvoker");
            }
        }

        private class BoundHandlerMethod implements HandlerMethod<M> {
            private final Object target;

            private BoundHandlerMethod(Object target) {
                this.target = target;
            }

            @Override
            public boolean canHandle(M message) {
                return messageFilter.test(message, executable, methodAnnotationType, targetClass);
            }

            @Override
            public Object invoke(M message, BiFunction<Object, Object, Object> resultCombiner) {
                if (parameterCount == 0) {
                    return invoker.invoke(target);
                }
                if (parameterCount == 1) {
                    return invoker.invoke(target, parameterResolverPlans[0].getFirst().preparedResolver()
                            .apply(message));
                }
                return invoker.invoke(target, parameterCount, i -> parameterResolverPlans[i].getFirst()
                        .preparedResolver().apply(message));
            }

            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Executable getMethod() {
                return executable;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <A extends Annotation> A getMethodAnnotation() {
                return (A) methodAnnotation;
            }

            @Override
            public boolean expectResult() {
                return hasReturnType;
            }

            @Override
            public boolean isPassive() {
                return passive;
            }

            @Override
            public String toString() {
                return Optional.ofNullable(targetClass).map(c -> {
                    String simpleName = c.getSimpleName();
                    return String.format("\"%s\"", simpleName.isEmpty() ? c : simpleName);
                }).orElse("BoundHandlerMethod");
            }
        }

        private class BoundHandlerMethodPlan implements HandlerMethodPlan<M> {
            private final Object target;
            private final Function<? super HandlerInput<M>, Object>[] resolvers;

            private BoundHandlerMethodPlan(
                    Object target, Function<? super HandlerInput<M>, Object>[] resolvers) {
                this.target = target;
                this.resolvers = resolvers;
            }

            @Override
            public Object invoke(HandlerInput<M> input) {
                if (parameterCount == 0) {
                    return invoker.invoke(target);
                }
                if (parameterCount == 1) {
                    return invoker.invoke(target, resolvers[0].apply(input));
                }
                return invoker.invoke(target, parameterCount, i -> resolvers[i].apply(input));
            }

            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Executable getMethod() {
                return executable;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <A extends Annotation> A getMethodAnnotation() {
                return (A) methodAnnotation;
            }

            @Override
            public boolean expectResult() {
                return hasReturnType;
            }

            @Override
            public boolean isPassive() {
                return passive;
            }
        }

        private class PayloadHandlerMethodPlan implements HandlerMethodPlan<M> {
            private final Function<? super HandlerInput<M>, Object>[] resolvers;

            private PayloadHandlerMethodPlan(Function<? super HandlerInput<M>, Object>[] resolvers) {
                this.resolvers = resolvers;
            }

            @Override
            public Object invoke(HandlerInput<M> input) {
                Object target = input.getPayload();
                if (parameterCount == 0) {
                    return invoker.invoke(target);
                }
                if (parameterCount == 1) {
                    return invoker.invoke(target, resolvers[0].apply(input));
                }
                return invoker.invoke(target, parameterCount, i -> resolvers[i].apply(input));
            }

            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Executable getMethod() {
                return executable;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <A extends Annotation> A getMethodAnnotation() {
                return (A) methodAnnotation;
            }

            @Override
            public boolean expectResult() {
                return hasReturnType;
            }

            @Override
            public boolean isPassive() {
                return passive;
            }
        }

        private record InputPlanCacheKey(Object parent, Object resolverKey) {
        }

        private class UnvalidatedPreparedParameterInvoker extends MethodHandlerInvoker implements IntFunction<Object> {
            private final Object target;
            private final M message;

            private UnvalidatedPreparedParameterInvoker(Object target, M message) {
                this.target = target;
                this.message = message;
            }

            @Override
            public Object invoke(BiFunction<Object, Object, Object> combiner) {
                return invoker.invoke(target, parameterCount, this);
            }

            @Override
            public Object apply(int i) {
                return parameterResolverPlans[i].getFirst().preparedResolver().apply(message);
            }
        }

        private class UnvalidatedDynamicParameterInvoker extends MethodHandlerInvoker implements IntFunction<Object> {
            private final Object target;
            private final M message;
            private final Function<? super M, Object>[] matchingResolvers;

            private UnvalidatedDynamicParameterInvoker(
                    Object target, M message, Function<? super M, Object>[] matchingResolvers) {
                this.target = target;
                this.message = message;
                this.matchingResolvers = matchingResolvers;
            }

            @Override
            public Object invoke(BiFunction<Object, Object, Object> combiner) {
                return invoker.invoke(target, parameterCount, this);
            }

            @Override
            public Object apply(int i) {
                return matchingResolvers[i].apply(message);
            }
        }

        private class UnvalidatedSingleDynamicParameterInvoker extends MethodHandlerInvoker {
            private final Object target;
            private final M message;
            private final Function<? super M, Object> matchingResolver;

            private UnvalidatedSingleDynamicParameterInvoker(
                    Object target, M message, Function<? super M, Object> matchingResolver) {
                this.target = target;
                this.message = message;
                this.matchingResolver = matchingResolver;
            }

            @Override
            public Object invoke(BiFunction<Object, Object, Object> combiner) {
                return invoker.invoke(target, matchingResolver.apply(message));
            }
        }

        private static final class ParameterResolverPlan<M> {
            private static final int maxCacheSize = 256;

            private final ParameterResolver<? super M> resolver;
            private final Function<? super M, Object> preparedResolver;
            private final boolean cacheKeyRelevant;
            private final KeyedParameterResolver.Resolution<M> staticKeyedResolution;
            private volatile ConcurrentHashMap<Object, KeyedParameterResolver.Resolution<M>> cache;

            private ParameterResolverPlan(ParameterResolver<? super M> resolver,
                                          Function<? super M, Object> preparedResolver,
                                          Parameter parameter, Annotation methodAnnotation) {
                this.resolver = resolver;
                this.preparedResolver = preparedResolver;
                this.cacheKeyRelevant = !(resolver instanceof KeyedParameterResolver<?> keyedResolver)
                                        || keyedResolver.isCacheKeyRelevant(parameter, methodAnnotation);
                this.staticKeyedResolution = resolver instanceof KeyedParameterResolver<?> && !cacheKeyRelevant
                        ? KeyedParameterResolver.Resolution.unmatched() : null;
            }

            private ParameterResolver<? super M> resolver() {
                return resolver;
            }

            private Function<? super M, Object> preparedResolver() {
                return preparedResolver;
            }

            private boolean isCacheKeyRelevant() {
                return cacheKeyRelevant;
            }

            @SuppressWarnings("unchecked")
            private KeyedParameterResolver.Resolution<M> resolveForKey(
                    Parameter parameter, Annotation methodAnnotation, M message) {
                if (!(resolver instanceof KeyedParameterResolver<?> rawResolver)) {
                    return null;
                }
                if (staticKeyedResolution != null) {
                    return staticKeyedResolution;
                }
                KeyedParameterResolver<M> keyedResolver = (KeyedParameterResolver<M>) rawResolver;
                Object key = keyedResolver.getCacheKey(message);
                if (key == null) {
                    return null;
                }
                ConcurrentHashMap<Object, KeyedParameterResolver.Resolution<M>> currentCache = cache;
                KeyedParameterResolver.Resolution<M> result = currentCache == null ? null : currentCache.get(key);
                if (result == null) {
                    result = Objects.requireNonNull(
                            keyedResolver.resolveForKey(parameter, methodAnnotation, message, key));
                    currentCache = cache();
                    if (currentCache.size() < maxCacheSize) {
                        KeyedParameterResolver.Resolution<M> existing = currentCache.putIfAbsent(key, result);
                        if (existing != null) {
                            result = existing;
                        }
                    }
                }
                return result;
            }

            private ConcurrentHashMap<Object, KeyedParameterResolver.Resolution<M>> cache() {
                ConcurrentHashMap<Object, KeyedParameterResolver.Resolution<M>> result = cache;
                if (result == null) {
                    synchronized (this) {
                        result = cache;
                        if (result == null) {
                            cache = result = new ConcurrentHashMap<>();
                        }
                    }
                }
                return result;
            }
        }
    }

    /**
     * A composite {@link HandlerMatcher} that delegates to a list of individual matchers.
     * <p>
     * Supports invoking one or multiple methods depending on configuration.
     * </p>
     */
    public static class ObjectHandlerMatcher<M> implements HandlerMatcher<Object, M> {
        private static final int maxCacheSize = 256;
        private static final int noMatch = -1;

        private final List<HandlerMatcher<Object, M>> methodHandlers;
        private final boolean invokeMultipleMethods;
        private final List<KeyedParameterResolver<? super M>> applicabilityKeyResolvers;
        private volatile ConcurrentHashMap<Object, Integer> instanceSelectionCache;
        private volatile ConcurrentHashMap<Object, Integer> staticSelectionCache;

        public ObjectHandlerMatcher(List<HandlerMatcher<Object, M>> methodHandlers, boolean invokeMultipleMethods) {
            this.methodHandlers = methodHandlers;
            this.invokeMultipleMethods = invokeMultipleMethods;
            if (methodHandlers.size() <= 1 && !methodHandlers.isEmpty()
                && methodHandlers.getFirst() instanceof MethodHandlerMatcher<?> methodHandlerMatcher) {
                methodHandlerMatcher.disableKeyedResolverCaching();
            }
            this.applicabilityKeyResolvers = findApplicabilityKeyResolvers();
        }

        @Override
        public boolean canHandle(M message) {
            for (HandlerMatcher<Object, M> d : methodHandlers) {
                if (d.canHandle(message)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Stream<Executable> matchingMethods(M message) {
            return methodHandlers.stream().flatMap(m -> m.matchingMethods(message));
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(Object target, M message) {
            return Optional.ofNullable(getInvokerOrNull(target, message));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(Object target, M message) {
            if (!invokeMultipleMethods && methodHandlers.size() == 1) {
                return methodHandlers.getFirst().getInvokerOrNull(target, message);
            }
            Object cacheKey = applicabilityCacheKey(message);
            if (cacheKey != null) {
                ConcurrentHashMap<Object, Integer> cache = selectionCache(target != null);
                Integer selected = cache.get(cacheKey);
                if (selected != null) {
                    if (selected == noMatch) {
                        return null;
                    }
                    HandlerInvoker cachedInvoker = methodHandlers.get(selected).getInvokerOrNull(target, message);
                    if (cachedInvoker != null) {
                        return cachedInvoker;
                    }
                    cache.remove(cacheKey, selected);
                }
                SelectedInvoker selection = selectInvoker(target, message);
                if (cache.size() < maxCacheSize) {
                    cache.putIfAbsent(cacheKey, selection == null ? noMatch : selection.index());
                }
                return selection == null ? null : selection.invoker();
            }
            return selectInvokerUncached(target, message);
        }

        private HandlerInvoker selectInvokerUncached(Object target, M message) {
            if (invokeMultipleMethods) {
                List<HandlerInvoker> invokers = new ArrayList<>();
                for (HandlerMatcher<Object, M> handler : methodHandlers) {
                    HandlerInvoker invoker = handler.getInvokerOrNull(target, message);
                    if (invoker != null) {
                        invokers.add(invoker);
                    }
                }
                return HandlerInvoker.join(invokers).orElse(null);
            }
            if (methodHandlers.size() == 1) {
                return methodHandlers.getFirst().getInvokerOrNull(target, message);
            }
            HandlerInvoker bestInvoker = null;
            MethodHandlerMatcher<M> bestMatcher = null;
            for (HandlerMatcher<Object, M> handler : methodHandlers) {
                HandlerInvoker invoker = handler.getInvokerOrNull(target, message);
                if (invoker != null) {
                    if (bestInvoker == null) {
                        bestInvoker = invoker;
                        if (handler instanceof MethodHandlerMatcher<?> methodHandlerMatcher) {
                            @SuppressWarnings("unchecked")
                            MethodHandlerMatcher<M> castMatcher = (MethodHandlerMatcher<M>) methodHandlerMatcher;
                            if (castMatcher.computeSpecificity(message).priority()
                                <= castMatcher.lowestSpecificityPriority) {
                                return bestInvoker;
                            }
                            bestMatcher = castMatcher;
                        }
                    } else if (bestMatcher != null
                               && handler instanceof MethodHandlerMatcher<?> methodHandlerMatcher) {
                        @SuppressWarnings("unchecked")
                        MethodHandlerMatcher<M> castMatcher = (MethodHandlerMatcher<M>) methodHandlerMatcher;
                        if (castMatcher.compareForMessage(bestMatcher, message) < 0) {
                            bestInvoker = invoker;
                            bestMatcher = castMatcher;
                        }
                    }
                }
            }
            return bestInvoker;
        }

        private SelectedInvoker selectInvoker(Object target, M message) {
            if (invokeMultipleMethods) {
                List<HandlerInvoker> invokers = new ArrayList<>();
                for (HandlerMatcher<Object, M> d : methodHandlers) {
                    HandlerInvoker invoker = d.getInvokerOrNull(target, message);
                    if (invoker != null) {
                        invokers.add(invoker);
                    }
                }
                HandlerInvoker joined = HandlerInvoker.join(invokers).orElse(null);
                return joined == null ? null : new SelectedInvoker(noMatch, joined);
            }
            if (methodHandlers.size() == 1) {
                HandlerInvoker invoker = methodHandlers.getFirst().getInvokerOrNull(target, message);
                return invoker == null ? null : new SelectedInvoker(0, invoker);
            }
            HandlerInvoker bestInvoker = null;
            MethodHandlerMatcher<M> bestMatcher = null;
            int bestIndex = noMatch;
            for (int i = 0; i < methodHandlers.size(); i++) {
                HandlerMatcher<Object, M> d = methodHandlers.get(i);
                HandlerInvoker invoker = d.getInvokerOrNull(target, message);
                if (invoker != null) {
                    if (bestInvoker == null) {
                        bestInvoker = invoker;
                        bestIndex = i;
                        if (d instanceof MethodHandlerMatcher<?> methodHandlerMatcher) {
                            @SuppressWarnings("unchecked")
                            MethodHandlerMatcher<M> castMatcher = (MethodHandlerMatcher<M>) methodHandlerMatcher;
                            if (castMatcher.computeSpecificity(message).priority()
                                <= castMatcher.lowestSpecificityPriority) {
                                return new SelectedInvoker(bestIndex, bestInvoker);
                            }
                            bestMatcher = castMatcher;
                        }
                    } else if (bestMatcher != null && d instanceof MethodHandlerMatcher<?> methodHandlerMatcher) {
                        @SuppressWarnings("unchecked")
                        MethodHandlerMatcher<M> castMatcher = (MethodHandlerMatcher<M>) methodHandlerMatcher;
                        if (castMatcher.compareForMessage(bestMatcher, message) < 0) {
                            bestInvoker = invoker;
                            bestMatcher = castMatcher;
                            bestIndex = i;
                        }
                    }
                }
            }
            return bestInvoker == null ? null : new SelectedInvoker(bestIndex, bestInvoker);
        }

        @SuppressWarnings("unchecked")
        private List<KeyedParameterResolver<? super M>> findApplicabilityKeyResolvers() {
            if (invokeMultipleMethods || methodHandlers.size() <= 1) {
                return List.of();
            }
            IdentityHashMap<KeyedParameterResolver<? super M>, Boolean> resolvers = new IdentityHashMap<>();
            for (HandlerMatcher<Object, M> handler : methodHandlers) {
                if (!(handler instanceof MethodHandlerMatcher<?> rawMatcher)) {
                    return List.of();
                }
                MethodHandlerMatcher<M> matcher = (MethodHandlerMatcher<M>) rawMatcher;
                if (!matcher.collectApplicabilityKeyResolvers(resolvers)) {
                    return List.of();
                }
            }
            return List.copyOf(resolvers.keySet());
        }

        private Object applicabilityCacheKey(M message) {
            Object result = null;
            for (KeyedParameterResolver<? super M> resolver : applicabilityKeyResolvers) {
                Object resolverKey = resolver.getCacheKey(message);
                if (resolverKey == null) {
                    return null;
                }
                result = result == null ? resolverKey : new CompositeApplicabilityKey(result, resolverKey);
            }
            return result;
        }

        private ConcurrentHashMap<Object, Integer> selectionCache(boolean instanceTarget) {
            ConcurrentHashMap<Object, Integer> result = instanceTarget ? instanceSelectionCache : staticSelectionCache;
            if (result == null) {
                synchronized (this) {
                    result = instanceTarget ? instanceSelectionCache : staticSelectionCache;
                    if (result == null) {
                        result = new ConcurrentHashMap<>();
                        if (instanceTarget) {
                            instanceSelectionCache = result;
                        } else {
                            staticSelectionCache = result;
                        }
                    }
                }
            }
            return result;
        }

        @Override
        public HandlerMethod<M> bindHandlerMethod(Object target) {
            if (invokeMultipleMethods || methodHandlers.size() != 1) {
                return null;
            }
            return methodHandlers.getFirst().bindHandlerMethod(target);
        }

        @Override
        public HandlerMethodPlan<M> prepareHandlerMethod(Object target, M message) {
            if (invokeMultipleMethods) {
                return null;
            }
            HandlerInvoker selected = getInvokerOrNull(target, message);
            if (selected == null) {
                return null;
            }
            Executable selectedMethod = selected.getMethod();
            for (HandlerMatcher<Object, M> handler : methodHandlers) {
                if (handler instanceof MethodHandlerMatcher<?> rawMatcher
                    && ((MethodHandlerMatcher<?>) rawMatcher).executable == selectedMethod) {
                    return handler.prepareHandlerMethod(target, message);
                }
            }
            return null;
        }

        @Override
        public HandlerMethodPlanner<M> bindHandlerMethodPlanner(Object target) {
            if (invokeMultipleMethods) {
                return null;
            }
            List<HandlerMethodPlanner<M>> planners = new ArrayList<>(methodHandlers.size());
            for (HandlerMatcher<Object, M> handler : methodHandlers) {
                HandlerMethodPlanner<M> planner = handler.bindHandlerMethodPlanner(target);
                if (planner == null) {
                    return null;
                }
                planners.add(planner);
            }
            return new HandlerMethodPlanner<>() {
                @Override
                public HandlerMethodApplicability<M> prepareApplicability(HandlerInput<M> input) {
                    return prepareInputApplicability(planners, input);
                }

                @Override
                public Object getCacheKey(M message) {
                    Object result = ObjectHandlerMatcher.this;
                    for (HandlerMethodPlanner<M> planner : planners) {
                        Object key = planner.getCacheKey(message);
                        if (key == null) {
                            return null;
                        }
                        result = new CompositeApplicabilityKey(result, key);
                    }
                    return result;
                }

                @Override
                public Object getCacheKey(HandlerInput<M> input) {
                    Object result = ObjectHandlerMatcher.this;
                    for (HandlerMethodPlanner<M> planner : planners) {
                        Object key = planner.getCacheKey(input);
                        if (key == null) {
                            return null;
                        }
                        result = new CompositeApplicabilityKey(result, key);
                    }
                    return result;
                }

                @Override
                public HandlerMethodPreparation<M> prepare(M message) {
                    HandlerInvoker selected = getInvokerOrNull(target, message);
                    if (selected == null) {
                        return HandlerMethodPreparation.noMatch();
                    }
                    Executable selectedMethod = selected.getMethod();
                    for (int i = 0; i < methodHandlers.size(); i++) {
                        HandlerMatcher<Object, M> handler = methodHandlers.get(i);
                        if (handler instanceof MethodHandlerMatcher<?> rawMatcher
                            && ((MethodHandlerMatcher<?>) rawMatcher).executable == selectedMethod) {
                            return planners.get(i).prepare(message);
                        }
                    }
                    return HandlerMethodPreparation.noMatch();
                }

                @Override
                public HandlerMethodPreparation<M> prepare(HandlerInput<M> input) {
                    if (planners.size() == 1) {
                        return planners.getFirst().prepare(input);
                    }
                    M message = input.getMessage();
                    HandlerInvoker selected = getInvokerOrNull(target, message);
                    if (selected == null) {
                        return HandlerMethodPreparation.noMatch();
                    }
                    Executable selectedMethod = selected.getMethod();
                    for (int i = 0; i < methodHandlers.size(); i++) {
                        HandlerMatcher<Object, M> handler = methodHandlers.get(i);
                        if (handler instanceof MethodHandlerMatcher<?> rawMatcher
                            && ((MethodHandlerMatcher<?>) rawMatcher).executable == selectedMethod) {
                            return planners.get(i).prepare(input);
                        }
                    }
                    return HandlerMethodPreparation.noMatch();
                }

                @Override
                public boolean isPayloadClassKey(HandlerInput<M> input) {
                    for (HandlerMethodPlanner<M> planner : planners) {
                        if (!planner.isPayloadClassKey(input)) {
                            return false;
                        }
                    }
                    return true;
                }

                @Override
                public boolean isNoMatchPayloadClassKey(HandlerInput<M> input) {
                    for (HandlerMethodPlanner<M> planner : planners) {
                        if (!planner.isNoMatchPayloadClassKey(input)) {
                            return false;
                        }
                    }
                    return true;
                }
            };
        }

        @Override
        public HandlerMethodPlanner<M> bindPayloadHandlerMethodPlanner() {
            if (invokeMultipleMethods) {
                return null;
            }
            List<HandlerMethodPlanner<M>> planners = new ArrayList<>(methodHandlers.size());
            for (HandlerMatcher<Object, M> handler : methodHandlers) {
                HandlerMethodPlanner<M> planner = handler.bindPayloadHandlerMethodPlanner();
                if (planner == null) {
                    return null;
                }
                planners.add(planner);
            }
            if (planners.size() == 1) {
                return planners.getFirst();
            }
            return new HandlerMethodPlanner<>() {
                @Override
                public HandlerMethodApplicability<M> prepareApplicability(HandlerInput<M> input) {
                    return prepareInputApplicability(planners, input);
                }

                @Override
                public Object getCacheKey(M message) {
                    return null;
                }

                @Override
                public Object getCacheKey(HandlerInput<M> input) {
                    Object result = ObjectHandlerMatcher.this;
                    for (HandlerMethodPlanner<M> planner : planners) {
                        Object key = planner.getCacheKey(input);
                        if (key == null) {
                            return null;
                        }
                        result = new CompositeApplicabilityKey(result, key);
                    }
                    return result;
                }

                @Override
                public HandlerMethodPreparation<M> prepare(M message) {
                    return HandlerMethodPreparation.unsupported();
                }

                @Override
                public HandlerMethodPreparation<M> prepare(HandlerInput<M> input) {
                    HandlerInvoker selected = getInvokerOrNull(input.getPayload(), input.getMessage());
                    if (selected == null) {
                        return HandlerMethodPreparation.noMatch();
                    }
                    Executable selectedMethod = selected.getMethod();
                    for (int i = 0; i < methodHandlers.size(); i++) {
                        HandlerMatcher<Object, M> handler = methodHandlers.get(i);
                        if (handler instanceof MethodHandlerMatcher<?> rawMatcher
                            && ((MethodHandlerMatcher<?>) rawMatcher).executable == selectedMethod) {
                            return planners.get(i).prepare(input);
                        }
                    }
                    return HandlerMethodPreparation.noMatch();
                }

                @Override
                public boolean isPayloadClassKey(HandlerInput<M> input) {
                    for (HandlerMethodPlanner<M> planner : planners) {
                        if (!planner.isPayloadClassKey(input)) {
                            return false;
                        }
                    }
                    return true;
                }

                @Override
                public boolean isNoMatchPayloadClassKey(HandlerInput<M> input) {
                    for (HandlerMethodPlanner<M> planner : planners) {
                        if (!planner.isNoMatchPayloadClassKey(input)) {
                            return false;
                        }
                    }
                    return true;
                }
            };
        }

        private HandlerMethodApplicability<M> prepareInputApplicability(
                List<HandlerMethodPlanner<M>> planners, HandlerInput<M> input) {
            Object key = ObjectHandlerMatcher.this;
            boolean payloadClassKey = true;
            List<HandlerMethodApplicability<M>> applicability = new ArrayList<>(planners.size());
            for (HandlerMethodPlanner<M> planner : planners) {
                HandlerMethodApplicability<M> candidate = planner.prepareApplicability(input);
                if (!candidate.isCacheable()) {
                    return HandlerMethodApplicability.unsupported();
                }
                key = new CompositeApplicabilityKey(key, candidate.cacheKey());
                payloadClassKey &= candidate.payloadClassKey();
                applicability.add(candidate);
            }
            HandlerMethodPreparation<M> preparation;
            if (applicability.size() == 1) {
                preparation = applicability.getFirst().preparation();
            } else {
                int selected = selectPreparedMethod(applicability, input);
                if (selected == UNSUPPORTED_SELECTION) {
                    return HandlerMethodApplicability.unsupported();
                }
                if (selected == noMatch) {
                    preparation = HandlerMethodPreparation.noMatch();
                } else {
                    preparation = applicability.get(selected).preparation();
                }
            }
            return preparation.isUnsupported() ? HandlerMethodApplicability.unsupported()
                    : HandlerMethodApplicability.cacheable(key, payloadClassKey, preparation);
        }

        private static final int UNSUPPORTED_SELECTION = -2;

        @SuppressWarnings("unchecked")
        private int selectPreparedMethod(
                List<HandlerMethodApplicability<M>> applicability, HandlerInput<M> input) {
            int bestIndex = noMatch;
            MethodHandlerMatcher<M> bestMatcher = null;
            MethodHandlerMatcher.Specificity bestSpecificity = null;
            for (int i = 0; i < applicability.size(); i++) {
                if (!applicability.get(i).preparation().isPrepared()) {
                    continue;
                }
                HandlerMatcher<Object, M> handler = methodHandlers.get(i);
                if (bestIndex == noMatch) {
                    bestIndex = i;
                    if (handler instanceof MethodHandlerMatcher<?> rawMatcher) {
                        bestMatcher = (MethodHandlerMatcher<M>) rawMatcher;
                        bestSpecificity = bestMatcher.computeInputSpecificity(input);
                        if (bestSpecificity == null) {
                            return UNSUPPORTED_SELECTION;
                        }
                        if (bestSpecificity.priority() <= bestMatcher.lowestSpecificityPriority) {
                            return bestIndex;
                        }
                    }
                } else if (bestMatcher != null && handler instanceof MethodHandlerMatcher<?> rawMatcher) {
                    MethodHandlerMatcher<M> matcher = (MethodHandlerMatcher<M>) rawMatcher;
                    MethodHandlerMatcher.Specificity specificity = matcher.computeInputSpecificity(input);
                    if (specificity == null) {
                        return UNSUPPORTED_SELECTION;
                    }
                    if (MethodHandlerMatcher.compare(
                            matcher.priority, specificity, matcher.parameterCount, matcher.methodIndex,
                            matcher.executable, bestMatcher.priority, bestSpecificity, bestMatcher.parameterCount,
                            bestMatcher.methodIndex, bestMatcher.executable,
                            matcher.config.samePriorityMethodComparator()) < 0) {
                        bestIndex = i;
                        bestMatcher = matcher;
                        bestSpecificity = specificity;
                    }
                }
            }
            return bestIndex;
        }

        private record SelectedInvoker(int index, HandlerInvoker invoker) {
        }

        private record CompositeApplicabilityKey(Object first, Object second) {
        }

    }

}
