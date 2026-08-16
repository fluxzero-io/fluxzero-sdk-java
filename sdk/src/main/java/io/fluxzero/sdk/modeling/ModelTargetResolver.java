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

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.getGenericPropertyType;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyType;
import static java.util.function.Predicate.not;

/**
 * Compiles and resolves the direct model IDs and ancestor dependencies needed by model-aware handlers.
 * <p>
 * An unqualified model dependency first matches a payload property with the same name as the model's
 * {@link EntityId}; when that property is absent, one uniquely typed {@link Id}{@code <Model>} property is accepted.
 * A read-only parameter without such a direct ID is resolved through temporal parent relations. A parameter-level
 * {@link io.fluxzero.sdk.tracking.handling.Association @Association("qualifier")} selects a payload property when it
 * exists and otherwise qualifies an ancestor edge by its explicit {@link ParentId#path()}.
 * <p>
 * Plans are structural and may be created during handler registration. Payload property readers are cached in
 * {@link ReflectionUtils.TypeMetadata}; resolving a message performs no reflective discovery.
 */
public final class ModelTargetResolver {
    private static final int READ = 1;
    private static final int WRITE = 2;

    private ModelTargetResolver() {
    }

    /**
     * Compiles a target plan for the selected model-aware handlers.
     *
     * @throws IllegalStateException when a required ID property is missing or ambiguous
     */
    public static TargetPlan plan(
            Class<?> payloadType, Collection<ModelMetadata.HandlerMethod> handlerMethods) {
        return plan(payloadType, handlerMethods, null);
    }

    /**
     * Compiles a target plan while leaving one model type to an explicit graph selection owned by the caller.
     */
    static TargetPlan plan(
            Class<?> payloadType,
            Collection<ModelMetadata.HandlerMethod> handlerMethods,
            Class<?> explicitModelType) {
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(handlerMethods, "handlerMethods");
        PayloadMetadata payload = PayloadMetadata.of(payloadType);
        List<MutableSlot> slots = new ArrayList<>();
        List<MutableDeferredWrite> deferredWrites = new ArrayList<>();
        List<AncestorDependency> ancestorDependencies = new ArrayList<>();
        for (ModelMetadata.HandlerMethod handler : handlerMethods) {
            compileHandler(
                    payload, handler, slots,
                    deferredWrites, ancestorDependencies,
                    explicitModelType);
        }
        List<SlotPlan> compiledSlots = slots.stream().map(MutableSlot::freeze).toList();
        Map<MutableSlot, Integer> slotIndexes = new IdentityHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            slotIndexes.put(slots.get(i), i);
        }
        return new TargetPlan(
                payloadType,
                compiledSlots,
                deferredWrites.stream()
                        .map(write -> write.freeze(slotIndexes))
                        .toList(),
                List.copyOf(new LinkedHashSet<>(ancestorDependencies)));
    }

    /**
     * Resolves the selected handlers against one payload using a freshly compiled plan.
     * <p>
     * Registration paths should retain the {@link TargetPlan} instead of using this convenience method repeatedly.
     */
    public static Resolution resolve(
            Object payload, Collection<ModelMetadata.HandlerMethod> handlerMethods) {
        Object value = payloadValue(payload);
        if (value == null) {
            throw new IllegalArgumentException("Cannot resolve model targets from a null payload");
        }
        return plan(value.getClass(), handlerMethods).resolve(value);
    }

    /**
     * Returns statically typed independent model types referenced by {@link Id} properties on a payload.
     * <p>
     * This supports receiver-side model handler discovery without traversing relationships or inspecting an ID value.
     */
    public static List<Class<?>> referencedModelTypes(Class<?> payloadType) {
        Objects.requireNonNull(payloadType, "payloadType");
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        PayloadMetadata.of(payloadType).properties.values().forEach(property ->
                ModelMetadata.inferIdTarget(property.type, property.genericType)
                        .filter(type -> ModelMetadata.of(type).isModel())
                        .ifPresent(result::add));
        return List.copyOf(result);
    }

    /**
     * Resolves one direct model ID from an arbitrary payload using the same rules as model-aware apply handlers.
     * <p>
     * This method deliberately does not traverse parent relations. An empty result means that the payload has no
     * matching direct ID property for the requested model type.
     */
    static Optional<String> resolveDirectModelId(
            Object payload, Class<?> modelType, String associationProperty) {
        return Optional.ofNullable(resolveDirectModelReference(
                payload, modelType, associationProperty).modelId());
    }

    static DirectModelReference resolveDirectModelReference(
            Object payload, Class<?> modelType, String associationProperty) {
        Object value = payloadValue(payload);
        if (value == null) {
            return DirectModelReference.missing();
        }
        PayloadProperty property = PayloadMetadata.of(value.getClass())
                .resolveIfDirect(modelType, associationProperty);
        if (property == null) {
            return DirectModelReference.missing();
        }
        Object idValue = property.read(value);
        if (idValue == null) {
            return new DirectModelReference(true, null);
        }
        return new DirectModelReference(
                true, repositoryId(idValue, modelType, property.name, null, value));
    }

    record DirectModelReference(boolean present, String modelId) {
        private static DirectModelReference missing() {
            return new DirectModelReference(false, null);
        }
    }

    /**
     * Resolves every statically typed independent-model ID carried by a payload.
     * <p>
     * Message-handler parameter injection uses these values only as graph anchors when a selected handler requests an
     * ancestor without also declaring the addressed descendant as a parameter.
     */
    static List<ResolvedModel> resolveReferencedModels(
            Object payload) {
        Object value = payloadValue(payload);
        if (value == null) {
            return List.of();
        }
        PayloadMetadata metadata =
                PayloadMetadata.of(value.getClass());
        LinkedHashMap<String, ResolvedModel> resolved =
                new LinkedHashMap<>();
        for (PayloadProperty property :
                metadata.properties.values()) {
            Optional<Class<?>> modelType =
                    ModelMetadata.inferIdTarget(
                                    property.type,
                                    property.genericType)
                            .filter(type ->
                                            ModelMetadata.of(type)
                                                    .isModel());
            if (modelType.isEmpty()) {
                continue;
            }
            PayloadProperty readable =
                    property.withReader(
                            metadata.typeMetadata.getter(
                                    property.name));
            Object idValue = readable.read(value);
            if (idValue == null) {
                continue;
            }
            String modelId = repositoryId(idValue, modelType.get(), property.name, null, value);
            merge(resolved, new ResolvedModel(
                    modelId, modelType.get(), Access.READ_ONLY, List.of(property.name)));
        }
        return List.copyOf(resolved.values());
    }

    /**
     * Merges one resolved identity into an insertion-ordered target map.
     * <p>
     * All model-aware handler paths use this operation so compatible type narrowing, access widening and source
     * qualifiers cannot diverge between commands, regular message handlers and ancestor resolution.
     */
    public static void merge(
            Map<String, ResolvedModel> targets, ResolvedModel addition) {
        targets.merge(addition.modelId(), addition, ResolvedModel::merge);
    }

    private static void compileHandler(
            PayloadMetadata payload,
            ModelMetadata.HandlerMethod handler,
            List<MutableSlot> allSlots,
            List<MutableDeferredWrite> allDeferredWrites,
            List<AncestorDependency> allAncestorDependencies,
            Class<?> explicitModelType) {
        List<MutableSlot> handlerSlots = new ArrayList<>();
        if (handler.receiverModelType() != null
            && !compatible(handler.receiverModelType(), explicitModelType)) {
            handlerSlots.add(new MutableSlot(
                    handler.receiverModelType(), Source.RECEIVER,
                    payload.resolve(handler.receiverModelType(), null, false),
                    READ, handler.executable().toGenericString()));
        }
        for (ModelMetadata.ModelParameter parameter : handler.modelParameters()) {
            if (compatible(parameter.modelType(), explicitModelType)) {
                continue;
            }
            PayloadProperty direct = payload.resolveIfDirect(
                    parameter.modelType(),
                    parameter.associationProperty());
            if (direct == null) {
                allAncestorDependencies.add(
                        new AncestorDependency(
                                parameter.modelType(),
                                parameter.associationProperty(),
                                handler.executable()
                                        .toGenericString()));
            } else {
                handlerSlots.add(new MutableSlot(
                        parameter.modelType(), Source.PARAMETER,
                        direct, READ,
                        handler.executable().toGenericString()));
            }
        }

        if (handler.kind() == ModelMetadata.HandlerKind.APPLY) {
            for (Class<?> targetType : handler.targetModelTypes()) {
                if (compatible(targetType, explicitModelType)) {
                    continue;
                }
                MutableSlot receiver = handlerSlots.stream()
                        .filter(slot -> slot.source == Source.RECEIVER && slot.modelType.equals(targetType))
                        .findFirst().orElse(null);
                if (receiver != null) {
                    receiver.access |= WRITE;
                    continue;
                }
                List<MutableSlot> matchingParameters = handlerSlots.stream()
                        .filter(slot -> slot.source == Source.PARAMETER && slot.modelType.equals(targetType))
                        .toList();
                if (matchingParameters.size() == 1) {
                    matchingParameters.getFirst().access |= WRITE;
                    continue;
                }
                if (matchingParameters.isEmpty()) {
                    handlerSlots.add(new MutableSlot(
                            targetType, Source.RETURN_TARGET, payload.resolve(targetType, null, false),
                            WRITE, handler.executable().toGenericString()));
                    continue;
                }

                PayloadProperty exactTarget = payload.resolve(targetType, null, true);
                if (exactTarget != null) {
                    MutableSlot existing = matchingParameters.stream()
                            .filter(slot -> slot.property.name.equals(exactTarget.name))
                            .findFirst().orElse(null);
                    if (existing == null) {
                        handlerSlots.add(new MutableSlot(
                                targetType, Source.RETURN_TARGET, exactTarget, WRITE,
                                handler.executable().toGenericString()));
                    } else {
                        existing.access |= WRITE;
                    }
                } else {
                    allDeferredWrites.add(new MutableDeferredWrite(
                            targetType, matchingParameters, handler.executable().toGenericString()));
                }
            }
        }
        allSlots.addAll(handlerSlots);
    }

    private static boolean compatible(
            Class<?> candidate,
            Class<?> explicitModelType) {
        return explicitModelType != null
               && (candidate.isAssignableFrom(explicitModelType)
                   || explicitModelType.isAssignableFrom(candidate));
    }

    private static Object payloadValue(Object payload) {
        return payload instanceof HasMessage message ? message.getPayload() : payload;
    }

    /**
     * Precompiled target-ID readers for one payload type and a selected set of handlers.
     */
    public static final class TargetPlan {
        private final Class<?> payloadType;
        private final List<SlotPlan> slots;
        private final List<DeferredWritePlan> deferredWrites;
        private final List<AncestorDependency> ancestorDependencies;
        private final List<String> singleSourceProperties;

        private TargetPlan(
                Class<?> payloadType,
                List<SlotPlan> slots,
                List<DeferredWritePlan> deferredWrites,
                List<AncestorDependency> ancestorDependencies) {
            this.payloadType = payloadType;
            this.slots = List.copyOf(slots);
            this.deferredWrites = List.copyOf(deferredWrites);
            this.ancestorDependencies =
                    List.copyOf(ancestorDependencies);
            this.singleSourceProperties = this.slots.size() == 1
                    ? List.of(this.slots.getFirst().property.name) : List.of();
        }

        /**
         * Payload type for which this plan was compiled.
         */
        public Class<?> payloadType() {
            return payloadType;
        }

        boolean isDirectSingleTarget() {
            return slots.size() == 1
                   && deferredWrites.isEmpty()
                   && ancestorDependencies.isEmpty();
        }

        String resolveSingleModelId(Object payload) {
            if (!isDirectSingleTarget()) {
                throw new IllegalStateException(
                        "Target plan is not a direct single-target plan");
            }
            Object value = payloadValue(payload);
            if (value == null || !payloadType.isInstance(value)) {
                throw new IllegalArgumentException(
                        "Expected payload of type %s but got %s".formatted(
                                payloadType.getName(),
                                value == null ? "null" : value.getClass().getName()));
            }
            SlotPlan slot = slots.getFirst();
            Object idValue = slot.property.read(value);
            if (idValue == null) {
                throw nullId(slot);
            }
            return modelId(idValue, slot, value);
        }

        Class<?> singleModelType() {
            return slots.getFirst().modelType;
        }

        Access singleAccess() {
            return Access.from(slots.getFirst().access);
        }

        List<String> singleSourceProperties() {
            return singleSourceProperties;
        }

        /**
         * Resolves and deduplicates every required model load.
         */
        public Resolution resolve(Object payload) {
            Object value = payloadValue(payload);
            if (value == null || !payloadType.isInstance(value)) {
                throw new IllegalArgumentException(
                        "Expected payload of type %s but got %s".formatted(
                                payloadType.getName(), value == null ? "null" : value.getClass().getName()));
            }
            if (slots.size() == 1 && deferredWrites.isEmpty()) {
                SlotPlan slot = slots.getFirst();
                Object idValue = slot.property.read(value);
                if (idValue == null) {
                    throw nullId(slot);
                }
                return new Resolution(
                        List.of(new ResolvedModel(
                                modelId(idValue, slot, value), slot.modelType, Access.from(slot.access),
                                List.of(slot.property.name))),
                        List.of(), ancestorDependencies);
            }

            LinkedHashMap<String, ResolvedModel> resolved =
                    new LinkedHashMap<>(slots.size());
            String[] idsBySlot = deferredWrites.isEmpty() ? null : new String[slots.size()];
            for (int i = 0; i < slots.size(); i++) {
                SlotPlan slot = slots.get(i);
                Object idValue = slot.property.read(value);
                if (idValue == null) {
                    throw nullId(slot);
                }
                String modelId = modelId(idValue, slot, value);
                if (idsBySlot != null) {
                    idsBySlot[i] = modelId;
                }
                merge(resolved, new ResolvedModel(
                        modelId, slot.modelType, Access.from(slot.access),
                        List.of(slot.property.name)));
            }

            List<DeferredWriteTarget> unresolvedWrites = new ArrayList<>();
            for (DeferredWritePlan deferred : deferredWrites) {
                List<String> candidateIds = new ArrayList<>(deferred.candidateSlotIndexes.size());
                for (Integer index : deferred.candidateSlotIndexes) {
                    String id = idsBySlot[index];
                    if (!candidateIds.contains(id)) {
                        candidateIds.add(id);
                    }
                }
                if (candidateIds.size() == 1) {
                    String modelId = candidateIds.getFirst();
                    merge(resolved, new ResolvedModel(
                            modelId, deferred.modelType, Access.WRITE_ONLY, List.of()));
                } else {
                    unresolvedWrites.add(new DeferredWriteTarget(
                            deferred.modelType, List.copyOf(candidateIds), deferred.handler));
                }
            }
            return new Resolution(
                    List.copyOf(resolved.values()),
                    unresolvedWrites,
                    ancestorDependencies);
        }

        private static IllegalArgumentException nullId(SlotPlan slot) {
            return new IllegalArgumentException(
                    "Payload property '%s' resolved to null for %s model required by %s"
                            .formatted(slot.property.name, slot.modelType.getName(), slot.handler));
        }

        private static String modelId(Object idValue, SlotPlan slot, Object source) {
            return repositoryId(
                    idValue, slot.metadata, slot.modelType,
                    slot.property.name, slot.handler, source);
        }
    }

    private static String repositoryId(
            Object idValue, Class<?> modelType, String property, String handler, Object source) {
        return repositoryId(idValue, ModelMetadata.of(modelType), modelType, property, handler, source);
    }

    private static String repositoryId(
            Object idValue, ModelMetadata metadata, Class<?> modelType, String property, String handler,
            Object source) {
        try {
            return metadata.parentScopedEntityId()
                    ? metadata.repositoryId(idValue, source)
                    : metadata.repositoryId(idValue);
        } catch (RuntimeException e) {
            String requirement = handler == null ? "" : " required by " + handler;
            throw new IllegalArgumentException(
                    "Payload property '%s' has an invalid ID for %s model%s"
                            .formatted(property, modelType.getName(), requirement), e);
        }
    }

    /**
     * One direct model load required by the selected handlers.
     *
     * @param modelId          exact persisted model key
     * @param access           whether the model is read and/or may be written
     * @param sourceProperties payload properties that resolved to this identity
     */
    public record ResolvedModel(
            String modelId, Class<?> modelType, Access access, List<String> sourceProperties) {
        public ResolvedModel {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelType, "modelType");
            Objects.requireNonNull(access, "access");
            sourceProperties = List.copyOf(sourceProperties);
        }

        private ResolvedModel merge(ResolvedModel addition) {
            if (!modelType.isAssignableFrom(addition.modelType)
                && !addition.modelType.isAssignableFrom(modelType)) {
                throw new IllegalStateException(
                        "Model ID '%s' is requested as incompatible types %s and %s"
                                .formatted(modelId, modelType.getName(), addition.modelType.getName()));
            }
            LinkedHashSet<String> sources = new LinkedHashSet<>(sourceProperties);
            sources.addAll(addition.sourceProperties);
            return new ResolvedModel(
                    modelId,
                    modelType.isAssignableFrom(addition.modelType) ? addition.modelType : modelType,
                    access.merge(addition.access),
                    List.copyOf(sources));
        }
    }

    /**
     * Read-only model dependency resolved by following temporal parent relations from the direct commit targets.
     *
     * @param modelType   required ancestor model type
     * @param association optional explicit {@link ParentId#path()} qualifier
     * @param handler     handler signature used in actionable resolution failures
     */
    public record AncestorDependency(Class<?> modelType, String association, String handler) {
        public AncestorDependency {
            Objects.requireNonNull(modelType, "modelType");
            Objects.requireNonNull(handler, "handler");
        }
    }

    /**
     * Resolution result containing deduplicated direct model loads and read-only ancestor dependencies.
     * <p>
     * A deferred write occurs only when an external apply has multiple qualified parameters of its return model type
     * and no canonical entity-ID property. All candidates are still direct, preloaded IDs. A non-null apply result
     * selects one by its returned {@link EntityId}; returning {@code null} is invalid until the write target is
     * otherwise disambiguated.
     */
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

        /**
         * Whether this resolution still requires a temporal ancestor graph lookup.
         */
        public boolean hasAncestorDependencies() {
            return !ancestorDependencies.isEmpty();
        }

        /**
         * Replaces the load targets after ancestor resolution while preserving deferred write selection.
         */
        public Resolution withResolvedModels(List<ResolvedModel> resolvedModels) {
            return new Resolution(resolvedModels, deferredWrites, List.of());
        }
    }

    /**
     * Return target that is selected from preloaded candidates after an apply result is available.
     */
    public record DeferredWriteTarget(Class<?> modelType, List<String> candidateModelIds, String handler) {
        public DeferredWriteTarget {
            candidateModelIds = List.copyOf(candidateModelIds);
        }
    }

    /**
     * State access required from a resolved model.
     */
    public enum Access {
        READ_ONLY(true, false),
        WRITE_ONLY(false, true),
        READ_WRITE(true, true);

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

    private enum Source {
        RECEIVER,
        PARAMETER,
        RETURN_TARGET
    }

    private record SlotPlan(
            Class<?> modelType, ModelMetadata metadata,
            PayloadProperty property, int access, String handler) {
    }

    private record DeferredWritePlan(Class<?> modelType, List<Integer> candidateSlotIndexes, String handler) {
        private DeferredWritePlan {
            candidateSlotIndexes = List.copyOf(candidateSlotIndexes);
        }
    }

    private static final class MutableSlot {
        private final Class<?> modelType;
        private final Source source;
        private final PayloadProperty property;
        private int access;
        private final String handler;

        private MutableSlot(
                Class<?> modelType, Source source, PayloadProperty property, int access, String handler) {
            this.modelType = modelType;
            this.source = source;
            this.property = property;
            this.access = access;
            this.handler = handler;
        }

        private SlotPlan freeze() {
            Class<?> effectiveType = ModelMetadata.inferIdTarget(
                            property.type, property.genericType)
                    .filter(modelType::isAssignableFrom)
                    .filter(type -> ModelMetadata.of(type).isModel())
                    .orElse(modelType);
            return new SlotPlan(
                    effectiveType, ModelMetadata.of(effectiveType),
                    property, access, handler);
        }
    }

    private record MutableDeferredWrite(Class<?> modelType, List<MutableSlot> candidates, String handler) {
        private DeferredWritePlan freeze(Map<MutableSlot, Integer> slotIndexes) {
            return new DeferredWritePlan(
                    modelType, candidates.stream().map(slotIndexes::get).toList(), handler);
        }
    }

    private record PayloadProperty(
            String name, Class<?> type, Type genericType, Function<Object, Object> reader) {
        private PayloadProperty withReader(Function<Object, Object> reader) {
            return new PayloadProperty(name, type, genericType, reader);
        }

        private Object read(Object payload) {
            return reader.apply(payload);
        }
    }

    private static final class PayloadMetadata {
        private final Class<?> payloadType;
        private final ReflectionUtils.TypeMetadata typeMetadata;
        private final Map<String, PayloadProperty> properties;

        private static PayloadMetadata of(Class<?> payloadType) {
            return ReflectionUtils.getTypeMetadata(payloadType)
                    .specializedMetadata(PayloadMetadata.class, PayloadMetadata::new);
        }

        private PayloadMetadata(Class<?> payloadType) {
            this.payloadType = payloadType;
            ReflectionUtils.TypeMetadata metadata = ReflectionUtils.getTypeMetadata(payloadType);
            this.typeMetadata = metadata;
            LinkedHashMap<String, AccessibleObject> members = new LinkedHashMap<>();
            metadata.fields().stream()
                    .filter(not(Field::isSynthetic))
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .forEach(field -> members.putIfAbsent(field.getName(), field));
            metadata.methods().stream()
                    .filter(not(Method::isSynthetic))
                    .filter(not(Method::isBridge))
                    .filter(method -> !Object.class.equals(method.getDeclaringClass()))
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getParameterCount() == 0)
                    .filter(method -> !void.class.equals(method.getReturnType()))
                    .filter(method -> !method.getName().equals("getClass"))
                    .forEach(method -> members.putIfAbsent(getPropertyName(method), method));
            LinkedHashMap<String, PayloadProperty> properties = new LinkedHashMap<>();
            members.forEach((name, member) -> properties.put(name, new PayloadProperty(
                    name, getPropertyType(member), getGenericPropertyType(member), null)));
            this.properties = Collections.unmodifiableMap(properties);
        }

        private PayloadProperty resolve(Class<?> modelType, String explicitProperty, boolean exactOnly) {
            if (exactOnly && explicitProperty == null) {
                ModelMetadata model = ModelMetadata.validate(modelType);
                if (!model.isModel()) {
                    throw new IllegalStateException(
                            "Handler dependency %s is not annotated with @Model"
                                    .formatted(modelType.getName()));
                }
                String entityIdProperty = model.entityId().orElseThrow().name();
                PayloadProperty exact = properties.get(entityIdProperty);
                return exact == null ? null : requireScalar(exact.name, modelType);
            }
            PayloadProperty direct = resolveIfDirect(modelType, explicitProperty);
            if (direct != null || exactOnly) {
                return direct;
            }
            ModelMetadata model = ModelMetadata.validate(modelType);
            String entityIdProperty = model.entityId().orElseThrow().name();
            throw new IllegalStateException(
                    "Payload %s has no property named '%s' and no uniquely typed Id<%s> for model %s. "
                            .formatted(payloadType.getName(), entityIdProperty, modelType.getSimpleName(),
                                       modelType.getName())
                    + "Add the direct target ID or qualify the model parameter with "
                    + "@Association(\"payloadProperty\").");
        }

        private PayloadProperty resolveIfDirect(Class<?> modelType, String explicitProperty) {
            ModelMetadata model = ModelMetadata.validate(modelType);
            if (!model.isModel()) {
                throw new IllegalStateException(
                        "Handler dependency %s is not annotated with @Model".formatted(modelType.getName()));
            }
            String entityIdProperty = model.entityId().orElseThrow().name();
            if (explicitProperty != null) {
                return properties.containsKey(explicitProperty)
                        ? requireScalar(explicitProperty, modelType) : null;
            }
            PayloadProperty exact = properties.get(entityIdProperty);
            if (exact != null) {
                return requireScalar(exact.name, modelType);
            }
            List<PayloadProperty> typedCandidates = properties.values().stream()
                    .filter(property -> ModelMetadata.inferIdTarget(property.type, property.genericType)
                            .filter(modelType::equals).isPresent())
                    .toList();
            if (typedCandidates.size() == 1) {
                PayloadProperty result = typedCandidates.getFirst();
                return result.withReader(typeMetadata.getter(result.name));
            }
            if (typedCandidates.isEmpty()) {
                return null;
            }
            throw new IllegalStateException(
                    "Payload %s has ambiguous Id<%s> properties %s for model %s. "
                            .formatted(payloadType.getName(), modelType.getSimpleName(),
                                       typedCandidates.stream().map(PayloadProperty::name).toList(),
                                       modelType.getName())
                    + "Qualify the model parameter with @Association(\"payloadProperty\").");
        }

        private PayloadProperty requireScalar(String propertyName, Class<?> modelType) {
            PayloadProperty result = properties.get(propertyName);
            if (result == null) {
                throw new IllegalStateException(
                        "Payload %s has no property '%s' required for model %s"
                                .formatted(payloadType.getName(), propertyName, modelType.getName()));
            }
            if (result.type.isArray() || Collection.class.isAssignableFrom(result.type)
                || Map.class.isAssignableFrom(result.type)) {
                throw new IllegalStateException(
                        "Payload property %s.%s must contain one direct model ID, but has type %s"
                                .formatted(payloadType.getName(), propertyName, result.type.getTypeName()));
            }
            return result.withReader(typeMetadata.getter(result.name));
        }
    }
}
