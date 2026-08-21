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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.fluxzero.common.handling.HandlerInspector.inspect;
import static io.fluxzero.common.reflection.ReflectionUtils.getGenericPropertyType;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyType;

/**
 * Registered and compiled knowledge for one reachable model mutation payload.
 */
public final class MutationPlan {
    private static final int READ = 1;
    private static final int WRITE = 2;

    private final ModelReducer reducer;
    private final TargetPlan targets;
    private final ModelCommitPolicy commitPolicy;
    private final boolean commit;
    private final boolean automatic;

    private MutationPlan(
            ModelReducer reducer,
            TargetPlan targets,
            ModelCommitPolicy commitPolicy,
            boolean commit,
            boolean automatic) {
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.commitPolicy = Objects.requireNonNull(commitPolicy, "commitPolicy");
        this.commit = commit;
        this.automatic = automatic;
    }

    public TargetPlan targets() {
        return targets;
    }

    ModelReducer reducer() {
        return reducer;
    }

    ModelCommitPolicy commitPolicy() {
        return commitPolicy;
    }

    boolean commit() {
        return commit;
    }

    boolean automatic() {
        return automatic;
    }

    public boolean empty() {
        return reducer.empty();
    }

    public boolean direct() {
        return reducer.direct();
    }

    /** Compiles immutable definition data with application-specific parameter resolvers. */
    public static final class Compiler {
        private final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
        private final ConcurrentHashMap<ReplayKey, MutationPlan> replayPlans = new ConcurrentHashMap<>();

        public Compiler(List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
            List<ParameterResolver<? super DeserializingMessage>> resolvers =
                    new ArrayList<>(parameterResolvers.size() + 1);
            if (parameterResolvers.stream().noneMatch(ModelEntityParameterResolver.class::isInstance)) {
                @SuppressWarnings("unchecked")
                ParameterResolver<? super DeserializingMessage> modelResolver =
                        (ParameterResolver<? super DeserializingMessage>) (ParameterResolver<?>)
                                new ModelEntityParameterResolver();
                resolvers.add(modelResolver);
            }
            resolvers.addAll(parameterResolvers);
            this.parameterResolvers = List.copyOf(resolvers);
        }

        HandlerPlan compileHandlers(Collection<EntityMetadata.HandlerMethod> selectedHandlers) {
            @SuppressWarnings("unchecked")
            List<EntityMetadata.HandlerMethod> handlers = selectedHandlers instanceof List<?> list
                    ? (List<EntityMetadata.HandlerMethod>) list : List.copyOf(selectedHandlers);
            return new HandlerPlan(handlers, this);
        }

        ModelReducer compileReducer(
                Collection<EntityMetadata.HandlerMethod> selectedHandlers,
                Class<?> payloadType) {
            @SuppressWarnings("unchecked")
            List<EntityMetadata.HandlerMethod> handlers = selectedHandlers instanceof List<?> list
                    ? (List<EntityMetadata.HandlerMethod>) list : List.copyOf(selectedHandlers);
            DirectSingleTargetApply direct = handlers.size() == 1
                    ? directSingleTargetApply(handlers.getFirst(), payloadType) : null;
            return new ModelReducer(compileHandlers(handlers), direct);
        }

        private HandlerMatcher<Object, DeserializingMessage> compileMatcher(
                EntityMetadata.HandlerMethod handler) {
            return inspect(
                    handler.executable().getDeclaringClass(), parameterResolvers,
                    HandlerConfiguration.<DeserializingMessage>builder()
                            .methodAnnotation(annotationType(handler.kind()))
                            .handlerFilter((type, executable) -> executable.equals(handler.executable()))
                            .build());
        }

        private static Class<? extends java.lang.annotation.Annotation> annotationType(
                EntityMetadata.HandlerKind kind) {
            return switch (kind) {
                case APPLY -> Apply.class;
                case ASSERT_LEGAL -> AssertLegal.class;
                case INTERCEPT_APPLY -> InterceptApply.class;
            };
        }

        public MutationPlan compileReplay(Class<?> payloadType, Class<?> modelType) {
            return replayPlans.computeIfAbsent(
                    new ReplayKey(payloadType, modelType), this::compileReplay);
        }

        private MutationPlan compileReplay(ReplayKey key) {
            LinkedHashSet<EntityMetadata.HandlerMethod> selected = new LinkedHashSet<>();
            EntityMetadata.of(key.payloadType()).applyMethods().stream()
                    .filter(handler -> handler.dynamicApplyResult()
                                       || handler.targetModelTypes().stream()
                                               .anyMatch(target -> compatible(target, key.modelType())))
                    .forEach(selected::add);
            EntityMetadata.of(key.modelType()).applyMethods().stream()
                    .filter(handler -> EntityMetadata.acceptsPayload(handler, key.payloadType()))
                    .forEach(selected::add);
            List<EntityMetadata.HandlerMethod> handlers = List.copyOf(selected);
            DirectSingleTargetApply direct = handlers.size() == 1
                    && handlers.getFirst().targetModelTypes().size() == 1
                    && compatible(handlers.getFirst().targetModelTypes().getFirst(), key.modelType())
                    ? directSingleTargetApply(handlers.getFirst(), key.payloadType()) : null;
            return new MutationPlan(
                    new ModelReducer(compileHandlers(handlers), direct),
                    compile(key.payloadType(), handlers),
                    ModelCommitPolicy.SYNC_AFTER_HANDLER, !handlers.isEmpty(), false);
        }

        private static boolean compatible(Class<?> left, Class<?> right) {
            return left.isAssignableFrom(right) || right.isAssignableFrom(left);
        }

        private record ReplayKey(Class<?> payloadType, Class<?> modelType) {
        }

    }

    public record DirectSingleTargetApply(MemberInvoker invoker, boolean receiver) {
    }


    static DirectSingleTargetApply directSingleTargetApply(
            EntityMetadata.HandlerMethod handler,
            Class<?> payloadType) {
        if (handler.kind() != EntityMetadata.HandlerKind.APPLY
            || handler.targetModelTypes().size() != 1
            || handler.collectionApplyResult()
            || handler.dynamicApplyResult()
            || !handler.modelParameters().isEmpty()
            || handler.executable().getParameterCount() != 1) {
            return null;
        }
        Executable executable = handler.executable();
        java.lang.reflect.Parameter parameter = executable.getParameters()[0];
        if (parameter.getAnnotations().length != 0
            || !parameter.getType().isAssignableFrom(payloadType)) {
            return null;
        }
        boolean receiver = !(executable instanceof Constructor<?>)
                           && !Modifier.isStatic(executable.getModifiers());
        if (receiver && handler.receiverModelType() == null) {
            return null;
        }
        return new DirectSingleTargetApply(
                ReflectionUtils.getTypeMetadata(executable.getDeclaringClass()).invoker(executable, true),
                receiver);
    }

    static record HandlerPlan(
            List<CompiledHandler> all,
            List<CompiledHandler> beforeAssertions,
            List<CompiledHandler> afterAssertions,
            List<CompiledHandler> applies,
            List<CompiledHandler> interceptors) {
        static final HandlerPlan EMPTY = new HandlerPlan(
                List.of(), List.of(), List.of(), List.of(), List.of());

        private HandlerPlan(List<EntityMetadata.HandlerMethod> handlers, Compiler compiler) {
            this(handlers.stream().map(handler -> {
                validateApplyResult(handler);
                return new CompiledHandler(
                        handler, compiler.compileMatcher(handler),
                        EffectOverrides.of(handler.executable()));
            }).toList());
        }

        private HandlerPlan(List<CompiledHandler> handlers) {
            this(List.copyOf(handlers),
                 select(handlers, EntityMetadata.HandlerKind.ASSERT_LEGAL, false),
                 select(handlers, EntityMetadata.HandlerKind.ASSERT_LEGAL, true),
                 select(handlers, EntityMetadata.HandlerKind.APPLY, null),
                 select(handlers, EntityMetadata.HandlerKind.INTERCEPT_APPLY, null));
        }

        List<EntityMetadata.HandlerMethod> methods() {
            return all.stream().map(CompiledHandler::method).toList();
        }

        private static List<CompiledHandler> select(
                List<CompiledHandler> handlers,
                EntityMetadata.HandlerKind kind,
                Boolean afterHandler) {
            List<CompiledHandler> result = handlers.stream()
                    .filter(handler -> handler.method().kind() == kind)
                    .filter(handler -> afterHandler == null
                            || assertAfterHandler(handler.method()) == afterHandler)
                    .collect(java.util.stream.Collectors.toList());
            result.sort((left, right) -> kind == EntityMetadata.HandlerKind.ASSERT_LEGAL
                    ? compareAssertions(left.method(), right.method())
                    : compareHandlers(left.method(), right.method()));
            return List.copyOf(result);
        }
    }

    record CompiledHandler(
            EntityMetadata.HandlerMethod method,
            HandlerMatcher<Object, DeserializingMessage> matcher,
            EffectOverrides effect) {
    }

    record EffectOverrides(
            EventPublication publication,
            EventPublicationStrategy strategy,
            AggregateEventRouting routing,
            ModelConflictPolicy conflict) {
        private static final EffectOverrides NONE = new EffectOverrides(
                EventPublication.DEFAULT, EventPublicationStrategy.DEFAULT,
                AggregateEventRouting.DEFAULT, ModelConflictPolicy.DEFAULT);

        static EffectOverrides of(Executable handler) {
            Apply apply = handler == null ? null : handler.getAnnotation(Apply.class);
            return apply == null ? NONE : new EffectOverrides(
                    apply.eventPublication(), apply.publicationStrategy(),
                    apply.eventRouting(), apply.conflictPolicy());
        }
    }

    private static void validateApplyResult(EntityMetadata.HandlerMethod handler) {
        if (handler.hasApplyResult() && !handler.dynamicApplyResult()
            && handler.targetModelTypes().size() != 1) {
            throw new IllegalStateException(
                    "Apply %s targets more than one model type".formatted(handler.executable()));
        }
    }

    static List<?> applyResults(EntityMetadata.HandlerMethod handler, Object result) {
        if (!handler.collectionApplyResult()) {
            return Collections.singletonList(result);
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Apply %s returned null instead of a model collection"
                            .formatted(handler.executable().toGenericString()));
        }
        if (!(result instanceof Collection<?> values)) {
            throw new IllegalStateException(
                    "Apply %s returned %s instead of a model collection"
                            .formatted(handler.executable().toGenericString(), result.getClass().getName()));
        }
        List<Object> snapshot = new ArrayList<>(values.size());
        int index = 0;
        for (Object value : values) {
            if (value == null) {
                throw new IllegalStateException(
                        "Apply %s returned a null model at collection index %d; use Graph.delete() for deletion"
                                .formatted(handler.executable().toGenericString(), index));
            }
            snapshot.add(value);
            index++;
        }
        return snapshot;
    }

    static Class<?> applyTargetType(
            EntityMetadata.HandlerMethod handler,
            Object result,
            int resultIndex) {
        if (!handler.dynamicApplyResult()) {
            Class<?> targetType = handler.targetModelTypes().getFirst();
            if (result != null && !targetType.isInstance(result)) {
                throw new IllegalStateException(
                        "Apply %s returned %s instead of %s%s".formatted(
                                handler.executable().toGenericString(), result.getClass().getName(),
                                targetType.getName(),
                                handler.collectionApplyResult() ? " at collection index " + resultIndex : ""));
            }
            return targetType;
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Apply %s returned null for a dynamically typed model result"
                            .formatted(handler.executable().toGenericString()));
        }
        EntityMetadata metadata = EntityMetadata.validate(result.getClass());
        if (!metadata.isModel()) {
            throw new IllegalStateException(
                    "Apply %s returned %s%s, which is not annotated with @Model".formatted(
                            handler.executable().toGenericString(), result.getClass().getName(),
                            handler.collectionApplyResult() ? " at collection index " + resultIndex : ""));
        }
        return result.getClass();
    }

    private static int compareHandlers(
            EntityMetadata.HandlerMethod left, EntityMetadata.HandlerMethod right) {
        return left.executable().toGenericString().compareTo(right.executable().toGenericString());
    }

    private static int compareAssertions(
            EntityMetadata.HandlerMethod left, EntityMetadata.HandlerMethod right) {
        int priority = Integer.compare(assertionPriority(right), assertionPriority(left));
        return priority == 0 ? compareHandlers(left, right) : priority;
    }

    private static int assertionPriority(EntityMetadata.HandlerMethod handler) {
        return ReflectionUtils.<AssertLegal>getMethodAnnotation(handler.executable(), AssertLegal.class)
                .map(AssertLegal::priority).orElse(AssertLegal.DEFAULT_PRIORITY);
    }

    private static boolean assertAfterHandler(EntityMetadata.HandlerMethod handler) {
        return ReflectionUtils.<AssertLegal>getMethodAnnotation(handler.executable(), AssertLegal.class)
                .map(AssertLegal::afterHandler).orElse(false);
    }

    static ParameterPlan parameterPlan(Executable executable) {
        return ReflectionUtils.getTypeMetadata(executable.getDeclaringClass())
                .specializedMetadata(ParameterPlans.class, ParameterPlans::new)
                .get(executable);
    }

    record ParameterPlan(
            Executable executable,
            Map<Parameter, EntityMetadata.ModelParameter> parameters) {
        private static ParameterPlan inspect(Executable executable) {
            LinkedHashMap<Parameter, EntityMetadata.ModelParameter> parameters = new LinkedHashMap<>();
            for (Parameter parameter : executable.getParameters()) {
                EntityMetadata.inspectModelParameter(parameter)
                        .ifPresent(modelParameter -> parameters.put(parameter, modelParameter));
            }
            return new ParameterPlan(executable, Collections.unmodifiableMap(parameters));
        }

        boolean hasModels() {
            return !parameters.isEmpty();
        }

        Optional<Resolution> resolve(
                DeserializingMessage message,
                Function<EntityMetadata.ModelParameter, DirectReferences> references) {
            return resolveDependencies(
                    message, executable, parameters.values(), references);
        }
    }

    private static final class ParameterPlans {
        private final Map<Executable, ParameterPlan> plans = new ConcurrentHashMap<>();

        @SuppressWarnings("unused")
        private ParameterPlans(Class<?> ignored) {
        }

        private ParameterPlan get(Executable executable) {
            return plans.computeIfAbsent(executable, ParameterPlan::inspect);
        }
    }

    /** Application-bound definitions invalidated together when model registration changes. */
    static final class Catalog {
        private final Compiler compiler;
        private final AutomaticModelHandling automaticHandling;
        private final CopyOnWriteArrayList<Class<?>> registeredModelTypes = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Class<?>> knownModelTypes = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<Class<?>, MutationPlan> definitions = new ConcurrentHashMap<>();
        private volatile CachedDefinition recentDefinition;
        private volatile boolean registeredModelTypesDiscovered;

        Catalog(
                Compiler compiler,
                AutomaticModelHandling automaticHandling) {
            this.compiler = Objects.requireNonNull(compiler, "compiler");
            this.automaticHandling = Objects.requireNonNull(automaticHandling, "automaticHandling");
        }

        List<Class<?>> registeredModelTypes() {
            return List.copyOf(registeredModelTypes);
        }

        List<Class<?>> knownModelTypes() {
            discoverRegisteredModelTypes();
            return List.copyOf(knownModelTypes);
        }

        void register(Class<?> modelType) {
            registeredModelTypes.addIfAbsent(modelType);
            knownModelTypes.addIfAbsent(modelType);
            clear();
        }

        void unregister(Class<?> modelType) {
            registeredModelTypes.remove(modelType);
            clear();
        }

        MutationPlan get(Class<?> payloadType) {
            CachedDefinition recent = recentDefinition;
            if (recent != null && recent.payloadType() == payloadType) {
                return recent.definition();
            }
            MutationPlan result = definitions.computeIfAbsent(payloadType, this::compileDefinition);
            recentDefinition = new CachedDefinition(payloadType, result);
            return result;
        }

        private MutationPlan compileDefinition(Class<?> payloadType) {
            List<EntityMetadata.HandlerMethod> handlers = inspectHandlers(payloadType);
            List<EntityMetadata.HandlerMethod> applies = handlers.stream()
                    .filter(handler -> handler.kind() == EntityMetadata.HandlerKind.APPLY).toList();
            applies.stream().flatMap(handler -> handler.targetModelTypes().stream())
                    .forEach(knownModelTypes::addIfAbsent);
            PlanTraits traits = inspectPlanTraits(payloadType, new LinkedHashSet<>());
            return new MutationPlan(
                    compiler.compileReducer(handlers, payloadType),
                    compile(payloadType, handlers),
                    ModelCommitPolicy.merge(traits.policies()),
                    traits.commit(), traits.commit() && traits.automatic());
        }

        private List<EntityMetadata.HandlerMethod> inspectHandlers(Class<?> payloadType) {
            LinkedHashSet<EntityMetadata.HandlerMethod> result =
                    new LinkedHashSet<>(EntityMetadata.of(payloadType).handlerMethods());
            LinkedHashSet<Class<?>> receiverTypes =
                    new LinkedHashSet<>(referencedModelTypes(payloadType));
            receiverTypes.addAll(registeredModelTypes);
            for (Class<?> receiverType : receiverTypes) {
                EntityMetadata.of(receiverType).handlerMethods().stream()
                        .filter(handler -> EntityMetadata.acceptsPayload(handler, payloadType))
                        .forEach(result::add);
            }
            return List.copyOf(result);
        }

        private PlanTraits inspectPlanTraits(Class<?> payloadType, Set<Class<?>> visiting) {
            if (!visiting.add(payloadType)) {
                return PlanTraits.NEUTRAL;
            }
            try {
                boolean commit = false;
                boolean automatic = true;
                LinkedHashSet<ModelCommitPolicy> policies = new LinkedHashSet<>();
                for (EntityMetadata.HandlerMethod handler : inspectHandlers(payloadType)) {
                    if (handler.kind() == EntityMetadata.HandlerKind.APPLY) {
                        commit |= handler.hasApplyResult();
                        if (handler.hasApplyResult()) {
                            automatic &= automaticHandlingEnabled(handler);
                        }
                        if (handler.dynamicApplyResult()) {
                            policies.add(ModelCommitPolicy.SYNC_AFTER_HANDLER);
                        }
                        handler.targetModelTypes().stream()
                                .map(EntityMetadata::of).map(EntityMetadata::model).flatMap(Optional::stream)
                                .map(Model::commitPolicy).map(ModelCommitPolicy::resolve)
                                .forEach(policies::add);
                    } else if (handler.kind() == EntityMetadata.HandlerKind.INTERCEPT_APPLY) {
                        commit |= handler.emittedPayloadTypes().isEmpty();
                        for (Class<?> emitted : handler.emittedPayloadTypes()) {
                            PlanTraits nested = inspectPlanTraits(emitted, visiting);
                            commit |= nested.commit();
                            automatic &= nested.automatic();
                            policies.addAll(nested.policies());
                        }
                    }
                }
                return new PlanTraits(commit, automatic, policies);
            } finally {
                visiting.remove(payloadType);
            }
        }

        private boolean automaticHandlingEnabled(EntityMetadata.HandlerMethod handler) {
            Apply apply = handler.executable().getAnnotation(Apply.class);
            AutomaticModelHandling policy =
                    apply == null ? AutomaticModelHandling.DEFAULT : apply.automaticHandling();
            if (policy == AutomaticModelHandling.DEFAULT) {
                policy = handler.targetModelTypes().stream()
                        .map(type -> type.getAnnotation(Model.class)).filter(Objects::nonNull)
                        .map(Model::automaticHandling)
                        .filter(value -> value != AutomaticModelHandling.DEFAULT)
                        .findFirst().orElse(AutomaticModelHandling.DEFAULT);
            }
            return (policy == AutomaticModelHandling.DEFAULT ? automaticHandling : policy)
                   != AutomaticModelHandling.DISABLED;
        }

        private void discoverRegisteredModelTypes() {
            if (registeredModelTypesDiscovered) {
                return;
            }
            synchronized (knownModelTypes) {
                if (registeredModelTypesDiscovered) {
                    return;
                }
                ReflectionUtils.getRegisteredTypes().stream()
                        .filter(type -> ReflectionUtils.getTypeMetadata(type).typeAnnotation(Model.class) != null)
                        .forEach(knownModelTypes::addIfAbsent);
                registeredModelTypesDiscovered = true;
            }
        }

        private void clear() {
            definitions.clear();
            recentDefinition = null;
        }
    }

    private record CachedDefinition(Class<?> payloadType, MutationPlan definition) {
    }

    private record PlanTraits(boolean commit, boolean automatic, Set<ModelCommitPolicy> policies) {
        private static final PlanTraits NEUTRAL = new PlanTraits(false, true, Set.of());

        private PlanTraits {
            policies = Set.copyOf(policies);
        }
    }

    /** Compiles and validates a target plan without an explicit target override. */
    public static TargetPlan plan(
            Class<?> payloadType,
            Collection<EntityMetadata.HandlerMethod> handlers) {
        return compile(payloadType, handlers).validate(null, false);
    }

    /** Compiles one target accessor plan for a payload and its selected handlers. */
    public static TargetPlan compile(
            Class<?> payloadType,
            Collection<EntityMetadata.HandlerMethod> handlers) {
        Payload payload = Payload.of(Objects.requireNonNull(payloadType, "payloadType"));
        List<Slot> slots = new ArrayList<>();
        List<Deferred> deferred = new ArrayList<>();
        Set<PlannedAncestor> ancestors = new LinkedHashSet<>();
        Set<Class<?>> explicitTypes = new LinkedHashSet<>();
        boolean[] dynamic = {false};
        Objects.requireNonNull(handlers, "handlerMethods").forEach(handler -> {
            compile(payload, handler, slots, deferred, ancestors);
            if (handler.receiverModelType() != null) {
                explicitTypes.add(handler.receiverModelType());
            }
            handler.modelParameters().forEach(parameter -> explicitTypes.add(parameter.modelType()));
            explicitTypes.addAll(handler.targetModelTypes());
            dynamic[0] |= handler.dynamicApplyResult();
        });
        return new TargetPlan(
                payloadType, List.copyOf(slots), List.copyOf(deferred), List.copyOf(ancestors),
                Set.copyOf(explicitTypes), dynamic[0]);
    }

    private static void compile(
            Payload payload,
            EntityMetadata.HandlerMethod handler,
            List<Slot> slots,
            List<Deferred> deferred,
            Set<PlannedAncestor> ancestors) {
        String signature = handler.executable().toGenericString();
        boolean apply = handler.kind() == EntityMetadata.HandlerKind.APPLY;
        List<Slot> local = new ArrayList<>();
        if (handler.receiverModelType() != null) {
            local.add(new Slot(
                    handler.receiverModelType(), payload.required(handler.receiverModelType(), signature),
                    false, READ, signature, true, apply));
        }
        for (EntityMetadata.ModelParameter parameter : handler.modelParameters()) {
            Property property = parameter.collectionWrapped()
                    ? payload.collection(parameter.modelType(), parameter.associationProperty())
                    : payload.direct(parameter.modelType(), parameter.associationProperty());
            if (property != null) {
                local.add(new Slot(
                        parameter.modelType(), property, parameter.collectionWrapped(),
                        READ, signature, false, apply));
            } else if (parameter.collectionWrapped()) {
                local.add(new Slot(
                        parameter.modelType(), Property.missing(
                                "Payload %s has no model ID collection property '%s' required by %s".formatted(
                                payload.type.getName(), parameter.associationProperty(), signature)),
                        true, READ, signature, false, apply));
            } else {
                ancestors.add(new PlannedAncestor(new AncestorDependency(
                        parameter.modelType(), parameter.associationProperty(), signature), apply));
            }
        }
        if (handler.kind() == EntityMetadata.HandlerKind.APPLY) {
            if (handler.dynamicApplyResult()) {
                local.forEach(slot -> slot.access |= WRITE);
            }
            handler.targetModelTypes().forEach(type -> writeSlot(payload, handler, type, local, deferred));
        }
        slots.addAll(local);
    }

    private static void writeSlot(
            Payload payload,
            EntityMetadata.HandlerMethod handler,
            Class<?> type,
            List<Slot> slots,
            List<Deferred> deferred) {
        String signature = handler.executable().toGenericString();
        List<Slot> candidates = slots.stream().filter(slot -> slot.modelType.equals(type)).toList();
        Slot receiver = candidates.stream().filter(slot -> slot.receiver).findFirst().orElse(null);
        if (receiver != null || candidates.size() == 1) {
            (receiver == null ? candidates.getFirst() : receiver).access |= WRITE;
        } else if (candidates.isEmpty()) {
            if (!handler.collectionApplyResult()) {
                slots.add(new Slot(
                        type, payload.required(type, signature), false, WRITE, signature, false, true));
            }
        } else {
            Property exact = payload.exact(type);
            Slot exactSlot = exact == null ? null : candidates.stream()
                    .filter(slot -> slot.property.name.equals(exact.name)).findFirst().orElse(null);
            if (exactSlot != null) {
                exactSlot.access |= WRITE;
            } else if (exact != null) {
                slots.add(new Slot(type, exact, false, WRITE, signature, false, true));
            } else {
                deferred.add(new Deferred(type, candidates, signature, true));
            }
        }
    }

    /** Returns independent model types referenced by typed ID properties. */
    public static List<Class<?>> referencedModelTypes(Class<?> payloadType) {
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        Payload.of(payloadType).properties.values().forEach(property -> property.modelType()
                .filter(type -> EntityMetadata.of(type).isModel()).ifPresent(result::add));
        return List.copyOf(result);
    }

    static DirectReferences directReferences(
            DeserializingMessage message,
            EntityMetadata.ModelParameter parameter) {
        String association = parameter.associationProperty();
        if (metadataContains(message, parameter)) {
            Object value = message.getMetadata().get(association);
            if (!parameter.collectionWrapped()) {
                return DirectReferences.scalar(value == null ? null : value.toString());
            }
            if (value == null) {
                return DirectReferences.collection(List.of());
            }
            if (!(value instanceof Collection<?> collection)) {
                throw new IllegalArgumentException(
                        "Metadata property '%s' must contain a model ID collection, but found %s".formatted(
                                association, value.getClass().getName()));
            }
            List<String> result = new ArrayList<>(collection.size());
            collection.forEach(id -> {
                if (id == null) {
                    throw new IllegalArgumentException(
                            "Metadata property '%s' contains a null model ID".formatted(association));
                }
                result.add(id.toString());
            });
            return DirectReferences.collection(result);
        }
        Object directPayload = payload(message.getPayload());
        Property property = directPayload == null ? null : parameter.collectionWrapped()
                ? Payload.of(directPayload.getClass()).collection(parameter.modelType(), association)
                : Payload.of(directPayload.getClass()).direct(parameter.modelType(), association);
        if (property == null) {
            return DirectReferences.missing();
        }
        Object value = property.read(directPayload);
        return parameter.collectionWrapped()
                ? DirectReferences.collection(ids(
                        value, parameter.modelType(), property.name(), null, directPayload))
                : DirectReferences.scalar(value == null ? null : repositoryId(
                        value, parameter.modelType(), property.name(), null, directPayload));
    }

    private static boolean metadataContains(
            DeserializingMessage message,
            EntityMetadata.ModelParameter parameter) {
        return parameter.associationProperty() != null && !parameter.associationExcludeMetadata()
               && message.getMetadata() != null
               && message.getMetadata().containsKey(parameter.associationProperty());
    }

    static Optional<Resolution> resolveDependencies(
            DeserializingMessage message,
            Executable executable,
            Collection<EntityMetadata.ModelParameter> parameters,
            Function<EntityMetadata.ModelParameter, DirectReferences> references) {
        Map<String, ResolvedModel> targets = new LinkedHashMap<>();
        Set<AncestorDependency> ancestors = new LinkedHashSet<>();
        boolean emptyCollection = false;
        for (EntityMetadata.ModelParameter parameter : parameters) {
            DirectReferences direct = references.apply(parameter);
            if (parameter.collectionWrapped()) {
                if (direct.present()) {
                    emptyCollection |= direct.modelIds().isEmpty();
                    direct.modelIds().forEach(id -> merge(targets, new ResolvedModel(
                            id, parameter.modelType(), Access.READ_ONLY,
                            List.of(parameter.associationProperty()))));
                }
            } else {
                if (!direct.present()) {
                    ancestors.add(new AncestorDependency(
                            parameter.modelType(), parameter.associationProperty(), executable.toGenericString()));
                } else if (direct.modelId() != null) {
                    String source = parameter.associationProperty() == null
                            ? EntityMetadata.of(parameter.modelType()).entityIdName()
                            : parameter.associationProperty();
                    merge(targets, new ResolvedModel(
                            direct.modelId(), parameter.modelType(), Access.READ_ONLY, List.of(source)));
                }
            }
        }
        if (!ancestors.isEmpty()) {
            resolveReferencedModels(message.getPayload()).forEach(target -> merge(targets, target));
        }
        return targets.isEmpty() && !emptyCollection ? Optional.empty()
                : Optional.of(new Resolution(
                        List.copyOf(targets.values()), List.of(), List.copyOf(ancestors)));
    }

    static List<ResolvedModel> resolveReferencedModels(Object input) {
        Object payload = payload(input);
        if (payload == null) {
            return List.of();
        }
        Map<String, ResolvedModel> result = new LinkedHashMap<>();
        Payload.of(payload.getClass()).properties.values().forEach(property -> property.modelType()
                .filter(type -> EntityMetadata.of(type).isModel()).ifPresent(type -> {
                    Object id = property.read(payload);
                    if (id != null) {
                        merge(result, new ResolvedModel(
                                repositoryId(id, type, property.name(), null, payload),
                                type, Access.READ_ONLY, List.of(property.name())));
                    }
                }));
        return List.copyOf(result.values());
    }

    /** Merges access to one identity and rejects incompatible global-ID claims. */
    public static void merge(Map<String, ResolvedModel> targets, ResolvedModel addition) {
        targets.merge(addition.modelId(), addition, ResolvedModel::merge);
    }

    /** Precompiled target-ID readers for one payload type. */
    public static final class TargetPlan {
        private final Class<?> payloadType;
        private final List<Slot> slots;
        private final List<Deferred> deferred;
        private final List<PlannedAncestor> ancestors;
        private final Set<Class<?>> explicitTypes;
        private final boolean dynamic;

        private TargetPlan(
                Class<?> payloadType,
                List<Slot> slots,
                List<Deferred> deferred,
                List<PlannedAncestor> ancestors,
                Set<Class<?>> explicitTypes,
                boolean dynamic) {
            this.payloadType = payloadType;
            this.slots = slots;
            this.deferred = deferred;
            this.ancestors = ancestors;
            this.explicitTypes = explicitTypes;
            this.dynamic = dynamic;
        }

        boolean isDirectSingleTarget() {
            return slots.size() == 1 && !slots.getFirst().collection
                   && deferred.isEmpty() && ancestors.isEmpty();
        }

        String resolveSingleModelId(Object input) {
            Object value = checkedPayload(input);
            Slot slot = slots.getFirst();
            Object id = slot.property.read(value);
            if (id == null) {
                throw nullId(slot);
            }
            return repositoryId(id, slot, value);
        }

        Class<?> singleModelType() {
            return slots.getFirst().modelType;
        }

        Access singleAccess() {
            return Access.from(slots.getFirst().access);
        }

        List<String> singleSourceProperties() {
            return List.of(slots.getFirst().property.name());
        }

        public Resolution resolve(Object input) {
            return resolve(input, null, false);
        }

        Resolution resolve(Object input, Class<?> explicitType, boolean appliesOnly) {
            return resolve(input, null, explicitType, appliesOnly);
        }

        Resolution resolve(
                Object input,
                String explicitId,
                Class<?> explicitType,
                boolean appliesOnly) {
            validate(explicitType, appliesOnly);
            Object payload = checkedPayload(input);
            Map<String, ResolvedModel> result = new LinkedHashMap<>();
            Map<Slot, List<String>> slotIds = deferred.isEmpty() ? Map.of() : new IdentityHashMap<>();
            for (Slot slot : slots) {
                if (appliesOnly && !slot.apply || compatible(slot.modelType, explicitType)) {
                    continue;
                }
                Object raw = slot.property.read(payload);
                if (raw == null && !slot.collection) {
                    throw nullId(slot);
                }
                List<String> ids = slot.collection
                        ? ids(raw, slot.modelType, slot.property.name(), slot.handler, payload)
                        : List.of(repositoryId(raw, slot, payload));
                if (!deferred.isEmpty()) {
                    slotIds.put(slot, ids);
                }
                ids.forEach(id -> merge(result, new ResolvedModel(
                        id, slot.modelType, Access.from(slot.access), List.of(slot.property.name()))));
            }
            List<DeferredWriteTarget> unresolved = new ArrayList<>();
            for (Deferred target : deferred) {
                if (appliesOnly && !target.apply || compatible(target.modelType, explicitType)) {
                    continue;
                }
                Set<String> candidates = new LinkedHashSet<>();
                target.candidates.forEach(slot -> candidates.addAll(slotIds.get(slot)));
                if (candidates.size() == 1) {
                    merge(result, new ResolvedModel(
                            candidates.iterator().next(), target.modelType, Access.WRITE_ONLY, List.of()));
                } else {
                    unresolved.add(new DeferredWriteTarget(
                            target.modelType, List.copyOf(candidates), target.handler));
                }
            }
            if (explicitId != null && (dynamic || explicitTypes.stream()
                    .anyMatch(type -> compatible(type, explicitType)))) {
                List<String> sources = slots.stream()
                        .filter(slot -> !slot.receiver && compatible(slot.modelType, explicitType))
                        .map(slot -> slot.property.name()).filter(Objects::nonNull).distinct().toList();
                merge(result, new ResolvedModel(
                        explicitId, explicitType, Access.READ_WRITE, sources));
            }
            return new Resolution(
                    List.copyOf(result.values()), unresolved,
                    ancestors.stream()
                            .filter(dependency -> !appliesOnly || dependency.apply)
                            .map(PlannedAncestor::dependency)
                            .filter(dependency -> !compatible(dependency.modelType(), explicitType)).toList());
        }

        private Object checkedPayload(Object input) {
            Object value = payload(input);
            if (value == null || !payloadType.isInstance(value)) {
                throw new IllegalArgumentException("Expected payload of type %s but got %s".formatted(
                        payloadType.getName(), value == null ? "null" : value.getClass().getName()));
            }
            return value;
        }

        private TargetPlan validate(Class<?> explicitType, boolean appliesOnly) {
            slots.stream().filter(slot -> !appliesOnly || slot.apply)
                    .filter(slot -> !compatible(slot.modelType, explicitType))
                    .map(slot -> slot.property).filter(Property::missing).findFirst().ifPresent(property -> {
                        throw new IllegalStateException(property.error);
                    });
            return this;
        }
    }

    record DirectReferences(boolean present, String modelId, List<String> modelIds) {
        DirectReferences {
            modelIds = List.copyOf(modelIds);
        }

        private static DirectReferences missing() {
            return new DirectReferences(false, null, List.of());
        }

        private static DirectReferences scalar(String modelId) {
            return new DirectReferences(true, modelId, List.of());
        }

        private static DirectReferences collection(List<String> modelIds) {
            return new DirectReferences(true, null, modelIds);
        }
    }

    /** One direct model load required by the selected handlers. */
    public record ResolvedModel(
            String modelId,
            Class<?> modelType,
            Access access,
            List<String> sourceProperties) {
        public ResolvedModel {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelType, "modelType");
            Objects.requireNonNull(access, "access");
            sourceProperties = List.copyOf(sourceProperties);
        }

        private ResolvedModel merge(ResolvedModel other) {
            if (!compatible(modelType, other.modelType)) {
                throw new IllegalStateException(
                        "Model ID '%s' is requested as incompatible types %s and %s".formatted(
                                modelId, modelType.getName(), other.modelType.getName()));
            }
            Set<String> sources = new LinkedHashSet<>(sourceProperties);
            sources.addAll(other.sourceProperties);
            return new ResolvedModel(
                    modelId, modelType.isAssignableFrom(other.modelType) ? other.modelType : modelType,
                    access.merge(other.access), List.copyOf(sources));
        }
    }

    /** Read-only dependency resolved through temporal parent relations. */
    public record AncestorDependency(Class<?> modelType, String association, String handler) {
        public AncestorDependency {
            Objects.requireNonNull(modelType, "modelType");
            Objects.requireNonNull(handler, "handler");
        }
    }

    /** Resolved direct targets, deferred writes and ancestor dependencies. */
    public record Resolution(
            List<ResolvedModel> models,
            List<DeferredWriteTarget> deferredWrites,
            List<AncestorDependency> ancestorDependencies) {
        public Resolution(List<ResolvedModel> models, List<DeferredWriteTarget> deferredWrites) {
            this(models, deferredWrites, List.of());
        }

        public Resolution {
            models = List.copyOf(models);
            deferredWrites = List.copyOf(deferredWrites);
            ancestorDependencies = List.copyOf(ancestorDependencies);
        }

        public boolean hasAncestorDependencies() {
            return !ancestorDependencies.isEmpty();
        }

        public Resolution withResolvedModels(List<ResolvedModel> resolvedModels) {
            return new Resolution(resolvedModels, deferredWrites, List.of());
        }
    }

    /** Runtime-selected write target among already loaded candidates. */
    public record DeferredWriteTarget(
            Class<?> modelType,
            List<String> candidateModelIds,
            String handler) {
        public DeferredWriteTarget {
            candidateModelIds = List.copyOf(candidateModelIds);
        }
    }

    /** Required state access for one resolved model. */
    public enum Access {
        READ_ONLY(true, false), WRITE_ONLY(false, true), READ_WRITE(true, true);

        private final boolean read;
        private final boolean write;

        Access(boolean read, boolean write) {
            this.read = read;
            this.write = write;
        }

        public boolean reads() {
            return read;
        }

        public boolean writes() {
            return write;
        }

        private Access merge(Access other) {
            return from((read || other.read ? READ : 0) | (write || other.write ? WRITE : 0));
        }

        private static Access from(int value) {
            return switch (value) {
                case READ -> READ_ONLY;
                case WRITE -> WRITE_ONLY;
                case READ | WRITE -> READ_WRITE;
                default -> throw new IllegalArgumentException("Unsupported model access value " + value);
            };
        }
    }

    private static final class Slot {
        private final Class<?> modelType;
        private final EntityMetadata metadata;
        private final Property property;
        private final boolean collection;
        private final String handler;
        private final boolean receiver;
        private final boolean apply;
        private int access;

        private Slot(
                Class<?> requestedType,
                Property property,
                boolean collection,
                int access,
                String handler,
                boolean receiver,
                boolean apply) {
            this.modelType = collection || property.missing() ? requestedType
                    : property.modelType().filter(requestedType::isAssignableFrom)
                            .filter(type -> EntityMetadata.of(type).isModel()).orElse(requestedType);
            this.metadata = EntityMetadata.of(modelType);
            this.property = property;
            this.collection = collection;
            this.access = access;
            this.handler = handler;
            this.receiver = receiver;
            this.apply = apply;
        }
    }

    private record Deferred(Class<?> modelType, List<Slot> candidates, String handler, boolean apply) {
        private Deferred {
            candidates = List.copyOf(candidates);
        }
    }

    private record PlannedAncestor(AncestorDependency dependency, boolean apply) {
    }

    private record Property(
            String name,
            Class<?> type,
            Type genericType,
            Function<Object, Object> reader,
            String error) {
        private static Property missing(String error) {
            return new Property(null, null, null, null, error);
        }

        private boolean missing() {
            return error != null;
        }

        private Optional<Class<?>> modelType() {
            return missing() ? Optional.empty() : EntityMetadata.inferIdTarget(type, genericType);
        }

        private Object read(Object target) {
            return reader.apply(target);
        }
    }

    private static final class Payload {
        private final Class<?> type;
        private final Map<String, Property> properties;

        private static Payload of(Class<?> type) {
            return ReflectionUtils.getTypeMetadata(type).specializedMetadata(Payload.class, Payload::new);
        }

        private Payload(Class<?> type) {
            this.type = type;
            ReflectionUtils.TypeMetadata metadata = ReflectionUtils.getTypeMetadata(type);
            Map<String, AccessibleObject> members = new LinkedHashMap<>();
            metadata.fields().stream().filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .forEach(field -> members.putIfAbsent(field.getName(), field));
            metadata.methods().stream().filter(method -> !method.isSynthetic() && !method.isBridge())
                    .filter(method -> !Object.class.equals(method.getDeclaringClass()))
                    .filter(method -> !Modifier.isStatic(method.getModifiers())
                                      && method.getParameterCount() == 0
                                      && !void.class.equals(method.getReturnType())
                                      && !method.getName().equals("getClass"))
                    .forEach(method -> members.putIfAbsent(getPropertyName(method), method));
            Map<String, Property> result = new LinkedHashMap<>();
            members.forEach((name, member) -> result.put(name, new Property(
                    name, getPropertyType(member), getGenericPropertyType(member),
                    target -> metadata.getter(name).apply(target), null)));
            properties = Collections.unmodifiableMap(result);
        }

        private Property required(Class<?> modelType, String handler) {
            Property result = direct(modelType, null);
            if (result != null) {
                return result;
            }
            String id = EntityMetadata.validate(modelType).entityIdName();
            return Property.missing(
                    "Payload %s has no property named '%s' and no uniquely typed Id<%s> for model %s. ".formatted(
                            type.getName(), id, modelType.getSimpleName(), modelType.getName())
                    + "Add the direct target ID or qualify the model parameter with "
                    + "@Association(\"payloadProperty\"). Required by " + handler);
        }

        private Property direct(Class<?> modelType, String association) {
            EntityMetadata model = validated(modelType);
            if (association != null) {
                return scalar(properties.get(association));
            }
            Property exact = properties.get(model.entityIdName());
            if (exact != null) {
                return scalar(exact);
            }
            List<Property> typed = properties.values().stream()
                    .filter(property -> property.modelType().filter(modelType::equals).isPresent()).toList();
            if (typed.size() < 2) {
                return typed.isEmpty() ? null : typed.getFirst();
            }
            throw new IllegalStateException(
                    "Payload %s has ambiguous Id<%s> properties %s for model %s. ".formatted(
                            type.getName(), modelType.getSimpleName(),
                            typed.stream().map(Property::name).toList(), modelType.getName())
                    + "Qualify the model parameter with @Association(\"payloadProperty\").");
        }

        private Property exact(Class<?> modelType) {
            Property result = properties.get(validated(modelType).entityIdName());
            return scalar(result);
        }

        private Property collection(Class<?> modelType, String association) {
            validated(modelType);
            Property result = association == null ? null : properties.get(association);
            if (result != null && !Collection.class.isAssignableFrom(result.type)) {
                throw new IllegalStateException(
                        "Payload property %s.%s must contain an ordered model ID collection, but has type %s".formatted(
                                type.getName(), association, result.type.getTypeName()));
            }
            return result;
        }

        private Property scalar(Property property) {
            if (property != null && (property.type.isArray()
                    || Collection.class.isAssignableFrom(property.type)
                    || Map.class.isAssignableFrom(property.type))) {
                throw new IllegalStateException(
                        "Payload property %s.%s must contain one direct model ID, but has type %s".formatted(
                                type.getName(), property.name, property.type.getTypeName()));
            }
            return property;
        }

        private static EntityMetadata validated(Class<?> type) {
            EntityMetadata result = EntityMetadata.validate(type);
            if (!result.isModel()) {
                throw new IllegalStateException(
                        "Handler dependency %s is not annotated with @Model".formatted(type.getName()));
            }
            return result;
        }
    }

    private static Object payload(Object input) {
        return input instanceof HasMessage message ? message.getPayload() : input;
    }

    private static boolean compatible(Class<?> candidate, Class<?> explicit) {
        return explicit != null
               && (candidate.isAssignableFrom(explicit) || explicit.isAssignableFrom(candidate));
    }

    private static IllegalArgumentException nullId(Slot slot) {
        return new IllegalArgumentException(
                "Payload property '%s' resolved to null for %s model required by %s".formatted(
                        slot.property.name(), slot.modelType.getName(), slot.handler));
    }

    private static String repositoryId(Object id, Slot slot, Object source) {
        try {
            return slot.metadata.parentScopedEntityId()
                    ? slot.metadata.repositoryId(id, source) : slot.metadata.repositoryId(id);
        } catch (RuntimeException e) {
            throw invalidId(slot.property.name(), slot.modelType, slot.handler, e);
        }
    }

    private static String repositoryId(
            Object id,
            Class<?> modelType,
            String property,
            String handler,
            Object source) {
        try {
            EntityMetadata metadata = EntityMetadata.of(modelType);
            return metadata.parentScopedEntityId()
                    ? metadata.repositoryId(id, source) : metadata.repositoryId(id);
        } catch (RuntimeException e) {
            throw invalidId(property, modelType, handler, e);
        }
    }

    private static IllegalArgumentException invalidId(
            String property,
            Class<?> modelType,
            String handler,
            RuntimeException cause) {
        return new IllegalArgumentException(
                "Payload property '%s' has an invalid ID for %s model%s".formatted(
                        property, modelType.getName(), handler == null ? "" : " required by " + handler), cause);
    }

    private static List<String> ids(
            Object raw,
            Class<?> modelType,
            String property,
            String handler,
            Object source) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(
                    "Payload property '%s' required by %s must contain a model ID collection".formatted(
                            property, handler));
        }
        List<String> result = new ArrayList<>(collection.size());
        int index = 0;
        for (Object id : collection) {
            if (id == null) {
                throw new IllegalArgumentException(
                        "Payload property '%s' required by %s contains a null model ID at index %d".formatted(
                                property, handler, index));
            }
            result.add(repositoryId(id, modelType, property, handler, source));
            index++;
        }
        return List.copyOf(result);
    }
}
