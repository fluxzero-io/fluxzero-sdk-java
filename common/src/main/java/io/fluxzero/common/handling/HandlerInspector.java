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
import java.util.List;
import java.util.Optional;
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
        private final boolean onlyPreparedParameterResolvers;
        private final boolean validateMethodInvocation;
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
            Parameter p = parameters[parameterIndex];
            for (ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
                if (plan.preparedResolver() != null) {
                    return plan.preparedResolver();
                }
                ParameterResolver<? super M> r = plan.resolver();
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
                    plans.add(new ParameterResolverPlan<>(resolver, preparedResolver));
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

        private boolean targetCanBeInvoked(Object target) {
            return target == null
                    ? executable instanceof Constructor || staticMethod
                    : executable instanceof Method && !staticMethod;
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
            for (Parameter p : parameters) {
                for (ParameterResolver<? super M> r : parameterResolvers) {
                    if (!r.determinesSpecificity()) {
                        continue;
                    }
                    Function<? super M, Object> resolver = null;
                    if (r instanceof PreparedParameterResolver<? super M> preparedParameterResolver) {
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

        private record ParameterResolverPlan<M>(
                ParameterResolver<? super M> resolver,
                Function<? super M, Object> preparedResolver) {
        }
    }

    /**
     * A composite {@link HandlerMatcher} that delegates to a list of individual matchers.
     * <p>
     * Supports invoking one or multiple methods depending on configuration.
     * </p>
     */
    @AllArgsConstructor
    public static class ObjectHandlerMatcher<M> implements HandlerMatcher<Object, M> {
        private final List<HandlerMatcher<Object, M>> methodHandlers;
        private final boolean invokeMultipleMethods;

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
            if (invokeMultipleMethods) {
                List<HandlerInvoker> invokers = new ArrayList<>();
                for (HandlerMatcher<Object, M> d : methodHandlers) {
                    HandlerInvoker invoker = d.getInvokerOrNull(target, message);
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
            for (HandlerMatcher<Object, M> d : methodHandlers) {
                HandlerInvoker invoker = d.getInvokerOrNull(target, message);
                if (invoker != null) {
                    if (bestInvoker == null) {
                        bestInvoker = invoker;
                        if (d instanceof MethodHandlerMatcher<?> methodHandlerMatcher) {
                            @SuppressWarnings("unchecked")
                            MethodHandlerMatcher<M> castMatcher = (MethodHandlerMatcher<M>) methodHandlerMatcher;
                            if (castMatcher.computeSpecificity(message).priority()
                                <= castMatcher.lowestSpecificityPriority) {
                                return bestInvoker;
                            }
                            bestMatcher = castMatcher;
                        }
                    } else if (bestMatcher != null && d instanceof MethodHandlerMatcher<?> methodHandlerMatcher) {
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

        @Override
        public HandlerMethod<M> bindHandlerMethod(Object target) {
            if (invokeMultipleMethods || methodHandlers.size() != 1) {
                return null;
            }
            return methodHandlers.getFirst().bindHandlerMethod(target);
        }

    }

}
