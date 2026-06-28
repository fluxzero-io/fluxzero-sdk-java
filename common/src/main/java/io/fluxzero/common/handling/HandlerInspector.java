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
     * Builds a {@link HandlerMatcher} from executable metadata views and prepared invocation handles.
     * <p>
     * This is the generated-metadata counterpart to {@link #inspect(Class, List, HandlerConfiguration)}: no JVM method
     * enumeration is required by this matcher.
     */
    public static <M> HandlerMatcher<Object, M> inspectViews(
            Class<?> targetClass,
            List<? extends ExecutableView> executableViews,
            Function<ExecutableView, ExecutableInvocation> invocationBackend,
            List<ParameterResolver<? super M>> parameterResolvers,
            HandlerConfiguration<? super M> config) {
        List<HandlerMatcher<Object, M>> matchers = new ArrayList<>();
        for (int i = 0; i < executableViews.size(); i++) {
            ExecutableView executableView = executableViews.get(i);
            if (config.methodMatches(targetClass, executableView)) {
                matchers.add(new ExecutableViewHandlerMatcher<>(
                        i, executableView, targetClass, invocationBackend.apply(executableView),
                        parameterResolvers, config));
            }
        }
        return new ObjectHandlerMatcher<>(matchers, config.invokeMultipleMethods());
    }

    private interface SpecificityAwareMatcher<M> {
        MethodHandlerMatcher.Specificity computeSpecificity(M message);

        int lowestSpecificityPriority();

        int priority();

        int parameterCount();

        int methodIndex();

        Executable executable();

        int compareForMessage(SpecificityAwareMatcher<M> other, M message);
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
    public static class MethodHandlerMatcher<M> implements HandlerMatcher<Object, M>, SpecificityAwareMatcher<M> {
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
        private final ExecutableView executableView;
        private final Parameter[] parameters;
        private final List<? extends ParameterView> parameterViews;
        private final int parameterCount;
        private final boolean staticMethod;
        private final ExecutableInvocation invoker;
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
            this.config = config;
            this.methodIndex = executable instanceof Method ? methodIndex((Method) executable, targetClass) : 0;
            this.executable = ensureAccessible(executable);
            this.executableView = ExecutableView.of(this.executable);
            this.parameterResolvers = parameterResolvers.stream()
                    .filter(silentTest(r -> r.mayApply(executableView, targetClass))).toList();
            this.parameters = this.executable.getParameters();
            this.parameterViews = this.executableView.parameters();
            this.parameterCount = this.parameters.length;
            this.staticMethod = Modifier.isStatic(this.executable.getModifiers());
            this.hasReturnType = ReflectionUtils.hasReturnType(executable);
            this.methodAnnotation = config.getAnnotation(executableView).orElse(null);
            this.methodAnnotationType = Optional.ofNullable(this.methodAnnotation).map(Annotation::annotationType)
                    .orElse(null);
            this.messageFilter = config.messageFilter()
                    .prepare(this.executableView, this.methodAnnotationType, targetClass);
            this.parameterResolverPlans = prepareParameterResolvers();
            this.onlyPreparedParameterResolvers = onlyPreparedParameterResolvers(this.parameterResolverPlans);
            this.validateMethodInvocation = config.methodInvocationValidator() != MethodInvocationValidator.noOp();
            this.classForSpecificity = computeClassForSpecificity();
            this.lowestSpecificityPriority = this.parameterResolvers.stream()
                    .filter(ParameterResolver::determinesSpecificity)
                    .mapToInt(ParameterResolver::specificityPriority).min().orElse(Integer.MAX_VALUE);
            this.priority = getPriority(methodAnnotation);
            this.passive = isPassive(methodAnnotation);
            this.invoker = config.executableInvocationBackend().prepare(this.executable);
        }

        @Override
        public boolean canHandle(M message) {
            return prepareInvokerFunction(message) != null;
        }

        @Override
        public Stream<Executable> matchingMethods(M message) {
            return canHandle(message) ? Stream.of(executable) : Stream.empty();
        }

        @Override
        public Stream<ExecutableView> matchingExecutableViews(M message) {
            return canHandle(message) ? Stream.of(executableView) : Stream.empty();
        }

        @SuppressWarnings("unchecked")
        protected Optional<Function<Object, HandlerInvoker>> prepareInvoker(M m) {
            return Optional.ofNullable(prepareInvokerOrNull(m));
        }

        @SuppressWarnings("unchecked")
        protected Function<Object, HandlerInvoker> prepareInvokerOrNull(M m) {
            if (!messageFilter.test(m, executableView, methodAnnotationType, targetClass)) {
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
            if (!messageFilter.test(m, executableView, methodAnnotationType, targetClass)) {
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
            ParameterView p = parameterViews.get(parameterIndex);
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
                    config.methodInvocationValidator().validate(m, target, executableView, args);
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
                    config.methodInvocationValidator().validate(m, target, executableView, args);
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
                ParameterView p = parameterViews.get(i);
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
                    executableView, methodAnnotationType).orElse(null);
            for (ParameterView p : parameterViews) {
                for (ParameterResolver<? super M> r : parameterResolvers) {
                    if (r.determinesSpecificity()) {
                        Function<? super M, Object> resolver = r.resolve(p, methodAnnotation);
                        if (resolver != null) {
                            Class<?> parameterType = p.type().orElse(null);
                            if (parameterType == null) {
                                continue;
                            }
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

        public Specificity computeSpecificity(M message) {
            Specificity result = new Specificity(classForSpecificity, Integer.MAX_VALUE);
            for (ParameterView p : parameterViews) {
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
                        Class<?> parameterType = p.type().orElse(null);
                        if (parameterType == null) {
                            continue;
                        }
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

        public int compareForMessage(SpecificityAwareMatcher<M> other, M message) {
            return compare(
                    priority,
                    computeSpecificity(message),
                    parameterCount,
                    methodIndex,
                    executable,
                    other.priority(),
                    other.computeSpecificity(message),
                    other.parameterCount(),
                    other.methodIndex(),
                    other.executable(),
                    config.samePriorityMethodComparator());
        }

        @Override
        public int lowestSpecificityPriority() {
            return lowestSpecificityPriority;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int parameterCount() {
            return parameterCount;
        }

        @Override
        public int methodIndex() {
            return methodIndex;
        }

        @Override
        public Executable executable() {
            return executable;
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
            if (leftExecutable != null && rightExecutable != null) {
                result = samePriorityMethodComparator.compare(leftExecutable, rightExecutable);
                if (result != 0) {
                    return result;
                }
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

            @Override
            public ExecutableView getExecutableView() {
                return executableView;
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
                return messageFilter.test(message, executableView, methodAnnotationType, targetClass);
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

            @Override
            public ExecutableView getExecutableView() {
                return executableView;
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
     * Handler matcher backed by executable metadata and a prepared invocation handle.
     */
    public static class ExecutableViewHandlerMatcher<M> implements HandlerMatcher<Object, M>,
            SpecificityAwareMatcher<M> {
        private final int methodIndex;
        private final ExecutableView executableView;
        private final int parameterCount;
        private final boolean staticMethod;
        private final ExecutableInvocation invoker;
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
        private final List<MethodHandlerMatcher.ParameterResolverPlan<M>>[] parameterResolverPlans;
        private final boolean onlyPreparedParameterResolvers;
        private final boolean validateMethodInvocation;

        @SuppressWarnings("unchecked")
        public ExecutableViewHandlerMatcher(
                int methodIndex,
                ExecutableView executableView,
                Class<?> targetClass,
                ExecutableInvocation invoker,
                List<ParameterResolver<? super M>> parameterResolvers,
                @NonNull HandlerConfiguration<? super M> config) {
            this.methodIndex = methodIndex;
            this.executableView = executableView;
            this.targetClass = targetClass;
            this.config = config;
            this.parameterResolvers = parameterResolvers.stream()
                    .filter(silentTest(r -> r.mayApply(executableView, targetClass))).toList();
            this.parameterCount = executableView.parameters().size();
            this.staticMethod = executableView.isStatic();
            this.hasReturnType = executableView.hasReturnType();
            this.methodAnnotation = config.getAnnotation(executableView).orElse(null);
            this.methodAnnotationType = Optional.ofNullable(this.methodAnnotation).map(Annotation::annotationType)
                    .orElse(null);
            this.messageFilter = config.messageFilter()
                    .prepare(this.executableView, this.methodAnnotationType, targetClass);
            this.parameterResolverPlans = prepareParameterResolvers();
            this.onlyPreparedParameterResolvers = onlyPreparedParameterResolvers(this.parameterResolverPlans);
            this.validateMethodInvocation = config.methodInvocationValidator() != MethodInvocationValidator.noOp();
            this.classForSpecificity = computeClassForSpecificity();
            this.lowestSpecificityPriority = this.parameterResolvers.stream()
                    .filter(ParameterResolver::determinesSpecificity)
                    .mapToInt(ParameterResolver::specificityPriority).min().orElse(Integer.MAX_VALUE);
            this.priority = getPriority(methodAnnotation);
            this.passive = isPassive(methodAnnotation);
            this.invoker = invoker;
        }

        @Override
        public boolean canHandle(M message) {
            return prepareInvokerFunction(message) != null;
        }

        @Override
        public Stream<Executable> matchingMethods(M message) {
            return Stream.empty();
        }

        @Override
        public Stream<ExecutableView> matchingExecutableViews(M message) {
            return canHandle(message) ? Stream.of(executableView) : Stream.empty();
        }

        private Function<Object, HandlerInvoker> prepareInvokerOrNull(M m) {
            if (!messageFilter.test(m, executableView, methodAnnotationType, targetClass)) {
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
            if (!messageFilter.test(m, executableView, methodAnnotationType, targetClass)) {
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
            ParameterView p = executableView.parameters().get(parameterIndex);
            for (MethodHandlerMatcher.ParameterResolverPlan<M> plan : parameterResolverPlans[parameterIndex]) {
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

        @SuppressWarnings("unchecked")
        private List<MethodHandlerMatcher.ParameterResolverPlan<M>>[] prepareParameterResolvers() {
            List<MethodHandlerMatcher.ParameterResolverPlan<M>>[] result = new List[parameterCount];
            for (int i = 0; i < parameterCount; i++) {
                ParameterView p = executableView.parameters().get(i);
                List<MethodHandlerMatcher.ParameterResolverPlan<M>> plans = new ArrayList<>();
                for (ParameterResolver<? super M> resolver : parameterResolvers) {
                    Function<? super M, Object> preparedResolver = resolver.prepare(p, methodAnnotation);
                    plans.add(new MethodHandlerMatcher.ParameterResolverPlan<>(resolver, preparedResolver));
                    if (preparedResolver != null) {
                        break;
                    }
                }
                result[i] = List.copyOf(plans);
            }
            return result;
        }

        private boolean onlyPreparedParameterResolvers(List<MethodHandlerMatcher.ParameterResolverPlan<M>>[] plans) {
            if (plans.length == 0) {
                return false;
            }
            for (List<MethodHandlerMatcher.ParameterResolverPlan<M>> plan : plans) {
                if (plan.size() != 1 || plan.getFirst().preparedResolver() == null) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(Object target, M message) {
            return Optional.ofNullable(getInvokerOrNull(target, message));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(Object target, M message) {
            if (!targetCanBeInvoked(target)) {
                return null;
            }
            return createInvokerOrNull(target, message);
        }

        @Override
        public HandlerMethod<M> bindHandlerMethod(Object target) {
            if (!targetCanBeInvoked(target)
                || validateMethodInvocation
                || parameterCount != 0 && !onlyPreparedParameterResolvers) {
                return null;
            }
            return new BoundViewHandlerMethod(target);
        }

        private boolean targetCanBeInvoked(Object target) {
            return target == null
                    ? executableView.kind() == ExecutableView.Kind.CONSTRUCTOR || staticMethod
                    : executableView.kind() == ExecutableView.Kind.METHOD && !staticMethod;
        }

        private Function<Object, HandlerInvoker> prepareInvokerFunction(M m) {
            return prepareInvokerOrNull(m);
        }

        protected Class<?> computeClassForSpecificity() {
            Class<?> handlerType = messageFilter.getLeastSpecificAllowedClass(
                    executableView, methodAnnotationType).orElse(null);
            for (ParameterView p : executableView.parameters()) {
                for (ParameterResolver<? super M> r : parameterResolvers) {
                    if (r.determinesSpecificity()) {
                        Function<? super M, Object> resolver = r.resolve(p, methodAnnotation);
                        if (resolver != null) {
                            Class<?> parameterType = p.type().orElse(null);
                            if (parameterType == null) {
                                continue;
                            }
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

        @Override
        public MethodHandlerMatcher.Specificity computeSpecificity(M message) {
            MethodHandlerMatcher.Specificity result =
                    new MethodHandlerMatcher.Specificity(classForSpecificity, Integer.MAX_VALUE);
            for (ParameterView p : executableView.parameters()) {
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
                        Class<?> parameterType = p.type().orElse(null);
                        if (parameterType == null) {
                            continue;
                        }
                        Class<?> candidate = classForSpecificity != null
                                             && !classForSpecificity.isAssignableFrom(parameterType)
                                             ? classForSpecificity : parameterType;
                        if (result.type() == null
                            || r.specificityPriority() < result.priority()
                            || r.specificityPriority() == result.priority()
                               && ReflectionUtils.getClassSpecificityComparator()
                                       .compare(candidate, result.type()) < 0) {
                            result = new MethodHandlerMatcher.Specificity(candidate, r.specificityPriority());
                        }
                    }
                }
            }
            return result;
        }

        @Override
        public int compareForMessage(SpecificityAwareMatcher<M> other, M message) {
            return MethodHandlerMatcher.compare(
                    priority,
                    computeSpecificity(message),
                    parameterCount,
                    methodIndex,
                    executable(),
                    other.priority(),
                    other.computeSpecificity(message),
                    other.parameterCount(),
                    other.methodIndex(),
                    other.executable(),
                    config.samePriorityMethodComparator());
        }

        @Override
        public int lowestSpecificityPriority() {
            return lowestSpecificityPriority;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int parameterCount() {
            return parameterCount;
        }

        @Override
        public int methodIndex() {
            return methodIndex;
        }

        @Override
        public Executable executable() {
            return executableView.executable().orElse(null);
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

        private HandlerInvoker createNoParameterInvoker(Object target) {
            return new ViewHandlerInvoker() {
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
            return new ViewHandlerInvoker() {
                @Override
                public Object invoke(BiFunction<Object, Object, Object> combiner) {
                    Object[] args = new Object[parameterCount];
                    for (int i = 0; i < parameterCount; i++) {
                        args[i] = parameterResolverPlans[i].getFirst().preparedResolver().apply(m);
                    }
                    config.methodInvocationValidator().validate(m, target, executableView, args);
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
                return new UnvalidatedDynamicParameterInvoker(target, m, matchingResolvers);
            }
            return new ViewHandlerInvoker() {
                @Override
                public Object invoke(BiFunction<Object, Object, Object> combiner) {
                    Object[] args = new Object[parameterCount];
                    for (int i = 0; i < parameterCount; i++) {
                        args[i] = matchingResolvers[i].apply(m);
                    }
                    config.methodInvocationValidator().validate(m, target, executableView, args);
                    return invoker.invoke(target, parameterCount, i -> args[i]);
                }
            };
        }

        protected abstract class ViewHandlerInvoker implements HandlerInvoker {

            @Override
            public Class<?> getTargetClass() {
                return targetClass;
            }

            @Override
            public Executable getMethod() {
                return executable();
            }

            @Override
            public ExecutableView getExecutableView() {
                return executableView;
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
                }).orElse("ViewHandlerInvoker");
            }
        }

        private class BoundViewHandlerMethod implements HandlerMethod<M> {
            private final Object target;

            private BoundViewHandlerMethod(Object target) {
                this.target = target;
            }

            @Override
            public boolean canHandle(M message) {
                return messageFilter.test(message, executableView, methodAnnotationType, targetClass);
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
                return executable();
            }

            @Override
            public ExecutableView getExecutableView() {
                return executableView;
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

        private class UnvalidatedPreparedParameterInvoker extends ViewHandlerInvoker implements IntFunction<Object> {
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

        private class UnvalidatedDynamicParameterInvoker extends ViewHandlerInvoker implements IntFunction<Object> {
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

        private class UnvalidatedSingleDynamicParameterInvoker extends ViewHandlerInvoker {
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
        public Stream<ExecutableView> matchingExecutableViews(M message) {
            return methodHandlers.stream().flatMap(m -> m.matchingExecutableViews(message));
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
            SpecificityAwareMatcher<M> bestMatcher = null;
            for (HandlerMatcher<Object, M> d : methodHandlers) {
                HandlerInvoker invoker = d.getInvokerOrNull(target, message);
                if (invoker != null) {
                    @SuppressWarnings("unchecked")
                    SpecificityAwareMatcher<M> candidateMatcher =
                            d instanceof SpecificityAwareMatcher<?> matcher
                            ? (SpecificityAwareMatcher<M>) matcher : null;
                    if (bestInvoker == null) {
                        bestInvoker = invoker;
                        if (candidateMatcher != null) {
                            bestMatcher = candidateMatcher;
                        }
                    } else if (bestMatcher != null && candidateMatcher != null) {
                        if (candidateMatcher.compareForMessage(bestMatcher, message) < 0) {
                            bestInvoker = invoker;
                            bestMatcher = candidateMatcher;
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
