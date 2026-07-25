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

import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.tracking.handling.Association;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.fluxzero.common.reflection.ReflectionUtils.getGenericPropertyType;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyType;

/**
 * Cached structural metadata for an independently stored model or a type containing model handlers.
 * <p>
 * Instances are owned by {@link ReflectionUtils.TypeMetadata}; callers must use {@link #of(Class)} instead of
 * constructing or caching this metadata independently.
 */
public final class ModelMetadata {
    private final Class<?> type;
    private final Model model;
    private final Aggregate aggregate;
    private final RootConfiguration rootConfiguration;
    private final Property entityId;
    private final List<ParentReference> parentReferences;
    private final List<HandlerMethod> handlerMethods;

    /**
     * Returns the centrally cached model metadata for a Java type.
     */
    public static ModelMetadata of(Class<?> type) {
        return ReflectionUtils.getTypeMetadata(type)
                .specializedMetadata(ModelMetadata.class, ModelMetadata::new);
    }

    /**
     * Returns and validates the centrally cached metadata, including all statically typed parent relations reachable
     * from this model.
     * <p>
     * Relations using untyped IDs are checked for cycles when their relationship deltas are committed.
     */
    public static ModelMetadata validate(Class<?> type) {
        ModelMetadata result = of(type);
        result.validateParentGraph();
        return result;
    }

    private ModelMetadata(Class<?> type) {
        this.type = type;
        ReflectionUtils.TypeMetadata typeMetadata = ReflectionUtils.getTypeMetadata(type);
        this.model = typeMetadata.typeAnnotation(Model.class);
        this.aggregate = typeMetadata.typeAnnotation(Aggregate.class);
        if (model != null && aggregate != null) {
            throw invalid("%s cannot be annotated with both @Model and @Aggregate".formatted(type.getName()));
        }
        this.rootConfiguration = model == null
                ? aggregate == null ? null : RootConfiguration.from(aggregate)
                : RootConfiguration.from(model);

        List<? extends AccessibleObject> entityIds = typeMetadata.annotatedProperties(EntityId.class);
        if (model != null && entityIds.size() != 1) {
            throw invalid("%s must declare exactly one @EntityId property, but found %d"
                                  .formatted(type.getName(), entityIds.size()));
        }
        this.entityId = entityIds.isEmpty() ? null : property(entityIds.getFirst());
        if (model != null) {
            validateScalarId(entityId, "@EntityId");
        }

        this.parentReferences = inspectParentReferences(typeMetadata);
        if (model == null && !parentReferences.isEmpty()) {
            throw invalid("@ParentId is only supported on @Model types, but was found on %s".formatted(type.getName()));
        }
        this.handlerMethods = inspectHandlerMethods(typeMetadata);
    }

    public Class<?> type() {
        return type;
    }

    public Optional<Model> model() {
        return Optional.ofNullable(model);
    }

    public Optional<Aggregate> aggregate() {
        return Optional.ofNullable(aggregate);
    }

    public boolean isModel() {
        return model != null;
    }

    /**
     * Returns aggregate-neutral persistence settings for an explicitly annotated root.
     */
    public Optional<RootConfiguration> rootConfiguration() {
        return Optional.ofNullable(rootConfiguration);
    }

    public Optional<Property> entityId() {
        return Optional.ofNullable(entityId);
    }

    public List<ParentReference> parentReferences() {
        return parentReferences;
    }

    public List<HandlerMethod> handlerMethods() {
        return handlerMethods;
    }

    public List<HandlerMethod> applyMethods() {
        return handlerMethods.stream().filter(method -> method.kind() == HandlerKind.APPLY).toList();
    }

    private List<ParentReference> inspectParentReferences(ReflectionUtils.TypeMetadata typeMetadata) {
        LinkedHashMap<String, ParentProperty> properties = new LinkedHashMap<>();
        for (AccessibleObject candidate : parentCandidates(typeMetadata)) {
            ParentId annotation = parentAnnotation(typeMetadata, candidate);
            if (annotation != null) {
                String propertyName = getPropertyName(candidate);
                ParentProperty previous = properties.putIfAbsent(
                        propertyName, new ParentProperty(property(candidate), annotation));
                if (previous != null && !previous.annotation().equals(annotation)) {
                    throw invalid("Conflicting @ParentId declarations on property %s.%s"
                                          .formatted(type.getName(), propertyName));
                }
            }
        }

        List<ParentReference> result = new ArrayList<>();
        for (ParentProperty parentProperty : properties.values()) {
            validateScalarId(parentProperty.property(), "@ParentId");
            if (entityId != null && entityId.name().equals(parentProperty.property().name())) {
                throw invalid("Property %s.%s cannot be both @EntityId and @ParentId"
                                      .formatted(type.getName(), entityId.name()));
            }
            ParentId annotation = parentProperty.annotation();
            String path = validateParentPath(parentProperty.property(), annotation.path());
            Class<?> inferredType = inferIdTarget(
                    parentProperty.property().type(), parentProperty.property().genericType()).orElse(null);
            Class<?> explicitType = void.class.equals(annotation.value()) ? null : annotation.value();
            if (inferredType != null && explicitType != null && !inferredType.equals(explicitType)) {
                throw invalid("@ParentId %s.%s explicitly refers to %s but its ID type refers to %s"
                                      .formatted(type.getName(), parentProperty.property().name(),
                                                 explicitType.getName(), inferredType.getName()));
            }
            Class<?> parentModelType = explicitType == null ? inferredType : explicitType;
            if (parentModelType != null && !isModelType(parentModelType)) {
                throw invalid("@ParentId %s.%s refers to %s, which is not annotated with @Model"
                                      .formatted(type.getName(), parentProperty.property().name(),
                                                 parentModelType.getName()));
            }
            if (!path.isEmpty() && parentModelType == null) {
                throw invalid("@ParentId path '%s' on %s.%s requires a typed Id<T> or an explicit parent model type"
                                      .formatted(path, type.getName(), parentProperty.property().name()));
            }
            result.add(new ParentReference(parentProperty.property(), path, parentModelType));
        }
        return List.copyOf(result);
    }

    private static ParentId parentAnnotation(
            ReflectionUtils.TypeMetadata typeMetadata, AccessibleObject candidate) {
        return switch (candidate) {
            case Field field -> (ParentId) typeMetadata.fieldAnnotation(field, ParentId.class).orElse(null);
            case Executable executable ->
                    (ParentId) typeMetadata.methodAnnotation(executable, ParentId.class).orElse(null);
            default -> null;
        };
    }

    private String validateParentPath(Property property, String path) {
        if (path.isEmpty()) {
            return path;
        }
        if (path.isBlank() || !path.equals(path.trim())) {
            throw invalid("@ParentId path on %s.%s must not be blank or have surrounding whitespace"
                                  .formatted(type.getName(), property.name()));
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw invalid("@ParentId path '%s' on %s.%s must be a relative path without empty segments"
                                  .formatted(path, type.getName(), property.name()));
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw invalid("@ParentId path '%s' on %s.%s must not contain '.' or '..' segments"
                                      .formatted(path, type.getName(), property.name()));
            }
        }
        return path;
    }

    private static List<AccessibleObject> parentCandidates(ReflectionUtils.TypeMetadata typeMetadata) {
        List<AccessibleObject> result = new ArrayList<>(typeMetadata.fields());
        result.addAll(typeMetadata.methods().stream()
                              .filter(method -> method.getParameterCount() == 0)
                              .filter(method -> !void.class.equals(method.getReturnType()))
                              .toList());
        return result;
    }

    private List<HandlerMethod> inspectHandlerMethods(ReflectionUtils.TypeMetadata typeMetadata) {
        List<HandlerMethod> result = new ArrayList<>();
        addHandlers(result, typeMetadata.annotatedExecutables(Apply.class), HandlerKind.APPLY);
        addHandlers(result, typeMetadata.annotatedExecutables(AssertLegal.class), HandlerKind.ASSERT_LEGAL);
        addHandlers(result, typeMetadata.annotatedExecutables(InterceptApply.class), HandlerKind.INTERCEPT_APPLY);
        return List.copyOf(result);
    }

    private void addHandlers(List<HandlerMethod> result, List<Executable> methods, HandlerKind kind) {
        for (Executable executable : methods) {
            List<ModelParameter> parameters = inspectModelParameters(executable);
            List<Class<?>> targets = kind == HandlerKind.APPLY ? inspectApplyTargets(executable) : List.of();
            Class<?> receiverModelType = model != null && executable instanceof Method method
                                         && !Modifier.isStatic(method.getModifiers()) ? type : null;
            if (kind == HandlerKind.APPLY && isVoid(executable) && (model != null || !parameters.isEmpty())) {
                throw invalid("Invalid @Apply method %s: void is not supported for @Model targets. "
                                      .formatted(executable.toGenericString())
                              + "Return the resulting model, or return null to delete it.");
            }
            validateParameterAmbiguity(executable, parameters);
            result.add(new HandlerMethod(executable, kind, receiverModelType, targets, parameters));
        }
    }

    private static List<Class<?>> inspectApplyTargets(Executable executable) {
        Class<?> resultType = switch (executable) {
            case Constructor<?> constructor -> constructor.getDeclaringClass();
            case Method method -> method.getReturnType();
            default -> Object.class;
        };
        return isModelType(resultType) ? List.of(resultType) : List.of();
    }

    private static List<ModelParameter> inspectModelParameters(Executable executable) {
        List<ModelParameter> result = new ArrayList<>();
        for (Parameter parameter : executable.getParameters()) {
            ParameterType parameterType = modelParameterType(parameter).orElse(null);
            if (parameterType == null) {
                continue;
            }
            Association association = ReflectionUtils.getAnnotation(parameter, Association.class).orElse(null);
            String associationProperty = null;
            if (association != null && association.value().length > 1) {
                throw invalid("Model parameter %s in %s may declare at most one @Association property"
                                      .formatted(parameter, executable.toGenericString()));
            }
            if (association != null && association.value().length == 1) {
                associationProperty = association.value()[0];
                if (associationProperty.isBlank() || !associationProperty.equals(associationProperty.trim())) {
                    throw invalid("Model parameter %s in %s has an invalid blank or padded @Association property"
                                          .formatted(parameter, executable.toGenericString()));
                }
            }
            result.add(new ModelParameter(
                    parameter, parameterType.modelType(), parameterType.entityWrapped(), associationProperty));
        }
        return List.copyOf(result);
    }

    private static void validateParameterAmbiguity(Executable executable, List<ModelParameter> parameters) {
        Map<Class<?>, List<ModelParameter>> byType = new LinkedHashMap<>();
        parameters.forEach(parameter -> byType.computeIfAbsent(parameter.modelType(), ignored -> new ArrayList<>())
                .add(parameter));
        for (Map.Entry<Class<?>, List<ModelParameter>> entry : byType.entrySet()) {
            List<ModelParameter> sameType = entry.getValue();
            if (sameType.size() < 2) {
                continue;
            }
            Set<String> qualifiers = new LinkedHashSet<>();
            boolean valid = sameType.stream().allMatch(parameter ->
                    parameter.associationProperty() != null && qualifiers.add(parameter.associationProperty()));
            if (!valid) {
                throw invalid("Handler %s has multiple %s model parameters. Qualify each with a unique "
                                      .formatted(executable.toGenericString(), entry.getKey().getName())
                              + "@Association(\"payloadProperty\") value.");
            }
        }
    }

    private void validateParentGraph() {
        if (model == null) {
            return;
        }
        validateParentGraph(type, new HashMap<>(), new ArrayList<>());
    }

    private static void validateParentGraph(Class<?> current, Map<Class<?>, VisitState> visited, List<Class<?>> path) {
        VisitState state = visited.get(current);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            int start = path.indexOf(current);
            List<Class<?>> cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(current);
            throw invalid("Model parent cycle detected: " + cycle.stream().map(Class::getName)
                    .reduce((left, right) -> left + " -> " + right).orElse(""));
        }
        visited.put(current, VisitState.VISITING);
        path.add(current);
        for (ParentReference reference : of(current).parentReferences) {
            if (reference.parentModelType() != null) {
                validateParentGraph(reference.parentModelType(), visited, path);
            }
        }
        path.removeLast();
        visited.put(current, VisitState.VISITED);
    }

    private static Optional<ParameterType> modelParameterType(Parameter parameter) {
        if (isModelType(parameter.getType())) {
            return Optional.of(new ParameterType(parameter.getType(), false));
        }
        if (!Entity.class.isAssignableFrom(parameter.getType())) {
            return Optional.empty();
        }
        List<Type> arguments = ReflectionUtils.getTypeArguments(parameter.getParameterizedType());
        if (arguments.size() != 1) {
            return Optional.empty();
        }
        Class<?> entityType = concreteType(arguments.getFirst());
        return isModelType(entityType) ? Optional.of(new ParameterType(entityType, true)) : Optional.empty();
    }

    private static Class<?> concreteType(Type type) {
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return ReflectionUtils.rawClass(wildcard.getLowerBounds()[0]);
            }
            if (wildcard.getUpperBounds().length > 0) {
                return ReflectionUtils.rawClass(wildcard.getUpperBounds()[0]);
            }
        }
        return ReflectionUtils.rawClass(type);
    }

    static Optional<Class<?>> inferIdTarget(Class<?> propertyType, Type genericPropertyType) {
        if (!Id.class.isAssignableFrom(propertyType)) {
            return Optional.empty();
        }
        Type idType = genericPropertyType;
        if (!(idType instanceof ParameterizedType)
            || !Id.class.isAssignableFrom(ReflectionUtils.rawClass(idType))) {
            idType = ReflectionUtils.getGenericType(propertyType, Id.class);
        }
        List<Type> arguments = ReflectionUtils.getTypeArguments(idType);
        if (arguments.size() != 1) {
            return Optional.empty();
        }
        Class<?> target = concreteType(arguments.getFirst());
        return Object.class.equals(target) ? Optional.empty() : Optional.of(target);
    }

    private static boolean isModelType(Class<?> type) {
        return type != null && ReflectionUtils.getTypeMetadata(type).typeAnnotation(Model.class) != null;
    }

    private static boolean isVoid(Executable executable) {
        return executable instanceof Method method && void.class.equals(method.getReturnType());
    }

    private static Property property(AccessibleObject member) {
        return new Property(
                getPropertyName(member),
                member,
                getPropertyType(member),
                getGenericPropertyType(member),
                DefaultMemberInvoker.asInvoker((java.lang.reflect.Member) member));
    }

    private static void validateScalarId(Property property, String annotation) {
        if (property == null) {
            return;
        }
        Class<?> propertyType = property.type();
        if (propertyType.isArray() || Collection.class.isAssignableFrom(propertyType)
            || Map.class.isAssignableFrom(propertyType)) {
            throw invalid("%s property %s.%s must contain one scalar ID, but has type %s"
                                  .formatted(annotation,
                                             ((java.lang.reflect.Member) property.member()).getDeclaringClass().getName(),
                                             property.name(),
                                             propertyType.getTypeName()));
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }

    /**
     * Cached property metadata with a compiled reader.
     */
    public record Property(
            String name, AccessibleObject member, Class<?> type, Type genericType, MemberInvoker reader) {
        public Object read(Object target) {
            return reader.invoke(target);
        }
    }

    /**
     * One outgoing parent relationship declared by a child model.
     *
     * @param path            optional parent-relative automatic composition path
     * @param parentModelType inferred or explicitly declared parent model type, or {@code null} for an untyped ID
     */
    public record ParentReference(Property property, String path, Class<?> parentModelType) {
        public Object read(Object target) {
            return property.read(target);
        }

        public boolean automaticallyComposed() {
            return !path.isEmpty();
        }
    }

    /**
     * Model-aware handler descriptor.
     *
     * @param executable        annotated handler method or constructor
     * @param kind              model-aware handler annotation kind
     * @param receiverModelType model type of a non-static handler receiver, or {@code null}
     * @param targetModelTypes  model types targeted by an apply return value
     * @param modelParameters   injected model value or {@link Entity} dependencies
     */
    public record HandlerMethod(
            Executable executable,
            HandlerKind kind,
            Class<?> receiverModelType,
            List<Class<?>> targetModelTypes,
            List<ModelParameter> modelParameters) {
        public HandlerMethod {
            targetModelTypes = List.copyOf(targetModelTypes);
            modelParameters = List.copyOf(modelParameters);
        }
    }

    /**
     * A model value or {@code Entity<Model>} parameter needed by a handler.
     *
     * @param associationProperty explicit payload property qualifier, or {@code null} for automatic matching
     */
    public record ModelParameter(
            Parameter parameter, Class<?> modelType, boolean entityWrapped, String associationProperty) {
    }

    public enum HandlerKind {
        APPLY,
        ASSERT_LEGAL,
        INTERCEPT_APPLY
    }

    /**
     * Aggregate-neutral persistence settings of an explicitly annotated root.
     */
    public record RootConfiguration(
            RootKind kind,
            boolean eventSourced,
            boolean ignoreUnknownEvents,
            int snapshotPeriod,
            int maxSnapshotCount,
            boolean cached,
            int cachingDepth,
            int checkpointPeriod,
            AggregateCommitPolicy commitPolicy,
            EventPublication eventPublication,
            EventPublicationStrategy publicationStrategy,
            AggregateEventRouting eventRouting,
            boolean searchable,
            String collection,
            String timestampPath,
            String endPath) {

        private static RootConfiguration from(Model annotation) {
            return new RootConfiguration(
                    RootKind.MODEL, annotation.eventSourced(), annotation.ignoreUnknownEvents(),
                    annotation.snapshotPeriod(), annotation.maxSnapshotCount(), annotation.cached(),
                    annotation.cachingDepth(), annotation.checkpointPeriod(), annotation.commitPolicy(),
                    annotation.eventPublication(), annotation.publicationStrategy(), annotation.eventRouting(),
                    annotation.searchable(), annotation.collection(), annotation.timestampPath(), annotation.endPath());
        }

        private static RootConfiguration from(Aggregate annotation) {
            return new RootConfiguration(
                    RootKind.AGGREGATE, annotation.eventSourced(), annotation.ignoreUnknownEvents(),
                    annotation.snapshotPeriod(), annotation.maxSnapshotCount(), annotation.cached(),
                    annotation.cachingDepth(), annotation.checkpointPeriod(), annotation.commitPolicy(),
                    annotation.eventPublication(), annotation.publicationStrategy(), annotation.eventRouting(),
                    annotation.searchable(), annotation.collection(), annotation.timestampPath(), annotation.endPath());
        }
    }

    public enum RootKind {
        MODEL,
        AGGREGATE
    }

    private record ParentProperty(Property property, ParentId annotation) {
    }

    private record ParameterType(Class<?> modelType, boolean entityWrapped) {
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
