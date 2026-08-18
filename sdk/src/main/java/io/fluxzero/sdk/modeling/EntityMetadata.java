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

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.reflection.DefaultMemberInvoker;
import io.fluxzero.common.reflection.GenericTypeResolver;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.web.ApiDoc;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.fluxzero.common.reflection.ReflectionUtils.getGenericPropertyType;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyType;

/**
 * Cached structural metadata for an entity, persisted root, or type containing model handlers.
 * <p>
 * Instances are owned by {@link ReflectionUtils.TypeMetadata}; callers must use {@link #of(Class)} instead of
 * constructing or caching this metadata independently.
 */
public final class EntityMetadata {
    private final Class<?> type;
    private final Model model;
    private final Aggregate aggregate;
    private final RootConfiguration rootConfiguration;
    private final Property entityId;
    private final String entityIdPrefix;
    private final String entityIdPostfix;
    private final boolean parentScopedEntityId;
    private final List<AliasProperty> aliasProperties;
    private final List<ParentReference> parentReferences;
    private final List<HandlerMethod> handlerMethods;
    private final Map<Parameter, ModelParameter> modelParameters;

    /**
     * Returns the centrally cached entity metadata for a Java type.
     */
    public static EntityMetadata of(Class<?> type) {
        return ReflectionUtils.getTypeMetadata(type)
                .specializedMetadata(EntityMetadata.class, EntityMetadata::new);
    }

    /**
     * Returns and validates the centrally cached metadata, including all statically typed parent relations reachable
     * from this type.
     * <p>
     * Relations using untyped IDs are checked for cycles when their relationship deltas are committed.
     */
    public static EntityMetadata validate(Class<?> type) {
        EntityMetadata result = of(type);
        ReflectionUtils.getTypeMetadata(type)
                .specializedMetadata(
                        ParentGraphValidation.class,
                        ignored -> {
                            result.validateParentGraph();
                            return ParentGraphValidation.INSTANCE;
                        });
        return result;
    }

    /**
     * Returns whether a model handler can structurally consume the supplied payload type.
     */
    public static boolean acceptsPayload(
            HandlerMethod handler, Class<?> payloadType) {
        boolean unmatchedDomainParameter = false;
        for (Parameter parameter : handler.executable().getParameters()) {
            if (handler.modelParameters().stream()
                    .anyMatch(model -> model.parameter().equals(parameter))) {
                continue;
            }
            Class<?> type = parameter.getType();
            if (type.isAssignableFrom(payloadType)) {
                return true;
            }
            if (!type.equals(Instant.class)
                && !type.equals(io.fluxzero.common.api.Metadata.class)
                && !type.equals(Message.class)
                && !type.equals(DeserializingMessage.class)) {
                unmatchedDomainParameter = true;
            }
        }
        return !unmatchedDomainParameter;
    }

    private EntityMetadata(Class<?> type) {
        this.type = type;
        ReflectionUtils.TypeMetadata typeMetadata = ReflectionUtils.getTypeMetadata(type);
        this.model = typeMetadata.typeAnnotation(Model.class);
        this.aggregate = typeMetadata.typeAnnotation(Aggregate.class);
        if (model != null && aggregate != null) {
            throw invalid("%s cannot be annotated with both @Model and @Aggregate".formatted(type.getName()));
        }
        this.rootConfiguration = model == null
                ? aggregate == null ? null : RootConfiguration.aggregate(aggregate)
                : RootConfiguration.model(model);
        if (model != null) {
            validateGraphProjection(model);
        }

        List<? extends AccessibleObject> entityIds = typeMetadata.annotatedProperties(EntityId.class);
        if (model != null && entityIds.size() != 1) {
            throw invalid("%s must declare exactly one @EntityId property, but found %d"
                                  .formatted(type.getName(), entityIds.size()));
        }
        AccessibleObject entityIdMember = entityIds.isEmpty() ? null : entityIds.getFirst();
        this.entityId = entityIdMember == null ? null : property(entityIdMember);
        EntityId entityIdAnnotation = entityIdMember == null ? null
                : ReflectionUtils.getAnnotationAs(entityIdMember, EntityId.class, EntityId.class).orElseThrow();
        this.entityIdPrefix = entityIdAnnotation == null ? "" : entityIdAnnotation.prefix();
        this.entityIdPostfix = entityIdAnnotation == null ? "" : entityIdAnnotation.postfix();
        this.parentScopedEntityId = entityIdAnnotation != null && entityIdAnnotation.parentScoped();
        if (model != null) {
            validateScalarId(entityId, "@EntityId");
        }

        this.aliasProperties = typeMetadata.annotatedProperties(Alias.class).stream()
                .map(member -> {
                    Alias annotation = ReflectionUtils.getAnnotationAs(
                                    member, Alias.class, Alias.class)
                            .orElseThrow();
                    return new AliasProperty(
                            property(member), annotation.prefix(), annotation.postfix());
                })
                .toList();

        this.parentReferences = inspectParentReferences(typeMetadata);
        if (model == null && !parentReferences.isEmpty()) {
            throw invalid("@ParentId is only supported on @Model types, but was found on %s".formatted(type.getName()));
        }
        if (parentScopedEntityId) {
            if (model == null) {
                throw invalid("@EntityId(parentScoped = true) is only supported on @Model types, but was found on %s"
                                      .formatted(type.getName()));
            }
            if (parentReferences.isEmpty()) {
                throw invalid("@EntityId(parentScoped = true) on %s requires at least one @ParentId"
                                      .formatted(type.getName()));
            }
            if (parentReferences.stream().anyMatch(reference -> reference.parentModelTypes().isEmpty())) {
                throw invalid("Every @ParentId on parent-scoped model %s must declare or infer its parent model type"
                                      .formatted(type.getName()));
            }
        }
        this.handlerMethods = inspectHandlerMethods(typeMetadata);
        Map<Parameter, ModelParameter> modelParameters = new LinkedHashMap<>();
        handlerMethods.stream().map(HandlerMethod::modelParameters).flatMap(Collection::stream)
                .forEach(parameter -> modelParameters.put(parameter.parameter(), parameter));
        this.modelParameters = Map.copyOf(modelParameters);
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

    /**
     * Returns the exact repository identity for a functional identifier of this type.
     * <p>
     * When the entity property is an {@link Id} subtype, a String input is interpreted as that ID's functional value,
     * so the ID's own repository prefix is applied before the outer {@link EntityId} affixes.
     */
    public String repositoryId(Object functionalId) {
        if (parentScopedEntityId) {
            throw new IllegalArgumentException(
                    "%s has a parent-scoped @EntityId; supply its parent identity or resolve it from a Graph"
                            .formatted(type.getName()));
        }
        return unscopedRepositoryId(functionalId);
    }

    private String unscopedRepositoryId(Object functionalId) {
        Objects.requireNonNull(functionalId, "Entity ID must not be null");
        String nested = nestedRepositoryId(functionalId);
        if (nested == null) {
            throw new IllegalArgumentException("Entity ID returned a null repository value for " + type.getName());
        }
        if (entityIdPrefix.isEmpty() && entityIdPostfix.isEmpty()) {
            return nested;
        }
        if (entityIdPrefix.isEmpty()) {
            return nested + entityIdPostfix;
        }
        if (entityIdPostfix.isEmpty()) {
            return entityIdPrefix + nested;
        }
        return entityIdPrefix + nested + entityIdPostfix;
    }

    /** Returns the exact repository identity read from this type's {@link EntityId} property. */
    public String repositoryIdOf(Object value) {
        if (entityId == null) {
            throw new IllegalStateException(type.getName() + " does not declare an @EntityId property");
        }
        Object id = entityId.read(Objects.requireNonNull(value, "Entity value must not be null"));
        return parentScopedEntityId
                ? scopedRepositoryId(id, parentValues(value))
                : unscopedRepositoryId(id);
    }

    /** Returns whether this model's persisted identity is scoped by a parent relationship. */
    public boolean parentScopedEntityId() {
        return parentScopedEntityId;
    }

    /** Returns the functional value held by this type's {@link EntityId} property. */
    public Object functionalIdOf(Object value) {
        if (entityId == null) {
            throw new IllegalStateException(type.getName() + " does not declare an @EntityId property");
        }
        return entityId.read(Objects.requireNonNull(value, "Entity value must not be null"));
    }

    /**
     * Resolves a parent-scoped primary identity from a functional child ID and explicit parent.
     */
    public String repositoryId(Object functionalId, Object parentId, Class<?> parentType) {
        if (!parentScopedEntityId) {
            return unscopedRepositoryId(functionalId);
        }
        Objects.requireNonNull(parentId, "Parent ID must not be null");
        List<ParentValue> matches = parentReferences.stream()
                .filter(reference -> parentType == null
                        || reference.supports(parentType))
                .map(reference -> new ParentValue(
                        reference, parentId,
                        parentType == null ? reference.parentModelType(parentId) : parentType))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "Expected exactly one @ParentId on %s for parent type %s, but found %d"
                            .formatted(type.getName(), parentType == null ? "<unspecified>" : parentType.getName(),
                                       matches.size()));
        }
        return scopedRepositoryId(functionalId, matches);
    }

    /**
     * Returns the repository identity for a known functional ID, reading parent scope from the supplied source only
     * when this model explicitly opted into parent-scoped identity.
     */
    public String repositoryId(Object functionalId, Object source) {
        return parentScopedEntityId
                ? scopedRepositoryId(functionalId, parentValues(source))
                : repositoryId(functionalId);
    }

    private List<ParentValue> parentValues(Object source) {
        List<ParentValue> result = new ArrayList<>();
        for (ParentReference reference : parentReferences) {
            Object value = type.isInstance(source)
                    ? reference.read(source)
                    : ReflectionUtils.readProperty(reference.property().name(), source).orElse(null);
            if (value != null) {
                result.add(new ParentValue(reference, value, reference.parentModelType(value)));
            }
        }
        List<ParentValue> candidates = List.copyOf(result);
        return candidates.size() < 2 ? candidates : candidates.stream()
                .filter(candidate -> candidates.stream().noneMatch(other -> other != candidate
                        && candidate.parentModelType() != null
                        && other.parentModelType() != null
                        && isAncestor(candidate.parentModelType(),
                                      other.parentModelType(), new LinkedHashSet<>())))
                .toList();
    }

    private static boolean isAncestor(
            Class<?> candidateAncestor, Class<?> descendant,
            Set<Class<?>> visited) {
        if (!visited.add(descendant)) {
            return false;
        }
        for (ParentReference parent : EntityMetadata.of(descendant).parentReferences()) {
            for (Class<?> parentType : parent.parentModelTypes()) {
                if (candidateAncestor.equals(parentType)
                    || isAncestor(candidateAncestor, parentType, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String scopedRepositoryId(Object functionalId, List<ParentValue> parents) {
        if (parents.size() != 1) {
            throw new IllegalArgumentException(
                    "Parent-scoped model %s requires exactly one non-null @ParentId, but found %d"
                            .formatted(type.getName(), parents.size()));
        }
        ParentValue parent = parents.getFirst();
        String parentType = Objects.requireNonNull(parent.parentModelType(),
                                                   "Parent model type must be known for scoped identity").getName();
        String parentId = parent.reference().repositoryId(parent.value());
        String childId = unscopedRepositoryId(functionalId);
        return "@%d:%s:%d:%s:%s".formatted(
                parentType.length(), parentType, parentId.length(), parentId, childId);
    }

    private record ParentValue(ParentReference reference, Object value, Class<?> parentModelType) {
    }

    private String nestedRepositoryId(Object functionalId) {
        if (entityId == null || !Id.class.isAssignableFrom(entityId.type())
            || entityId.type().isInstance(functionalId)) {
            return functionalId.toString();
        }
        String value = functionalId instanceof Id<?> id ? id.getFunctionalId() : functionalId.toString();
        try {
            return ReflectionUtils.getTypeMetadata(entityId.type())
                    .invoker(entityId.type().getDeclaredConstructor(String.class), true)
                    .invoke(null, value).toString();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "@EntityId type %s must declare a single String constructor to support functional String lookup"
                            .formatted(entityId.type().getName()), e);
        }
    }

    /**
     * Whether this model declares at least one independently persisted alias.
     */
    public boolean hasAliases() {
        return !aliasProperties.isEmpty();
    }

    /**
     * Returns the complete aliases declared by the supplied model value, or {@code null} when its type does not
     * participate in independent-model alias persistence.
     */
    public List<String> aliases(Object value) {
        if (aliasProperties.isEmpty()) {
            return null;
        }
        if (value == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (AliasProperty alias : aliasProperties) {
            Object candidate = alias.property().read(value);
            if (candidate instanceof Collection<?> collection) {
                collection.stream().filter(Objects::nonNull)
                        .map(item -> alias.value(item.toString()))
                        .forEach(result::add);
            } else if (candidate != null) {
                result.add(alias.value(candidate.toString()));
            }
        }
        return List.copyOf(result);
    }

    String entityIdName() {
        return entityId == null ? null : entityId.name();
    }

    public List<ParentReference> parentReferences() {
        return parentReferences;
    }

    /**
     * Whether this model is placed in an automatically composed graph through at least one explicit parent path.
     * <p>
     * Graph participation is independent from {@link Model#searchable()}: a model can supply a current document for
     * composition without exposing that document through its own searchable collection.
     */
    public boolean participatesInGraphComposition() {
        return parentReferences.stream()
                .anyMatch(ParentReference::automaticallyComposed);
    }

    public List<HandlerMethod> handlerMethods() {
        return handlerMethods;
    }

    Optional<ModelParameter> modelParameter(Parameter parameter) {
        return Optional.ofNullable(modelParameters.get(parameter));
    }

    /**
     * Inspects one arbitrary handler parameter for a model value or {@code Entity<Model>} dependency.
     * <p>
     * Unlike {@link #modelParameter(Parameter)}, this method is not limited to model-aware apply methods and can be
     * used by regular message-handler parameter resolvers.
     */
    static Optional<ModelParameter> inspectModelParameter(Parameter parameter) {
        ParameterType parameterType = modelParameterType(parameter).orElse(null);
        if (parameterType == null) {
            return Optional.empty();
        }
        Association association = ReflectionUtils.getAnnotation(parameter, Association.class).orElse(null);
        String associationProperty = null;
        if (association != null && association.value().length > 1) {
            throw invalid("Model parameter %s in %s may declare at most one @Association property"
                                  .formatted(parameter, parameter.getDeclaringExecutable().toGenericString()));
        }
        if (association != null && association.value().length == 1) {
            associationProperty = association.value()[0];
            if (associationProperty.isBlank() || !associationProperty.equals(associationProperty.trim())) {
                throw invalid("Model parameter %s in %s has an invalid blank or padded @Association property"
                                      .formatted(parameter, parameter.getDeclaringExecutable().toGenericString()));
            }
        }
        if (parameterType.collectionWrapped() && associationProperty == null) {
            throw invalid("Collection model parameter %s in %s requires @Association(\"payloadProperty\") "
                                  .formatted(parameter, parameter.getDeclaringExecutable().toGenericString())
                          + "to select its ordered ID collection.");
        }
        return Optional.of(new ModelParameter(
                parameter, parameterType.modelType(), parameterType.entityWrapped(), parameterType.graphWrapped(),
                parameterType.collectionWrapped(),
                associationProperty,
                association != null
                && association.excludeMetadata()));
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
            ParentId annotation = parentProperty.annotation();
            String path = validateParentPath(parentProperty.property(), annotation.path());
            Class<?> inferredType = inferIdTarget(
                    parentProperty.property().type(), parentProperty.property().genericType()).orElse(null);
            Class<?> explicitType = void.class.equals(annotation.value()) ? null : annotation.value();
            List<Class<?>> explicitTypes = List.of(annotation.types());
            if (explicitType != null && !explicitTypes.isEmpty()) {
                throw invalid("@ParentId %s.%s may declare either value or types, but not both"
                                      .formatted(type.getName(), parentProperty.property().name()));
            }
            LinkedHashSet<Class<?>> parentTypes = new LinkedHashSet<>(explicitTypes);
            if (parentTypes.size() != explicitTypes.size()) {
                throw invalid("@ParentId %s.%s declares duplicate parent model types"
                                      .formatted(type.getName(), parentProperty.property().name()));
            }
            if (parentTypes.remove(void.class)) {
                throw invalid("@ParentId %s.%s types must not contain void"
                                      .formatted(type.getName(), parentProperty.property().name()));
            }
            if (explicitType != null) {
                parentTypes.add(explicitType);
            } else if (parentTypes.isEmpty() && inferredType != null) {
                parentTypes.add(inferredType);
            }
            if (inferredType != null && !parentTypes.isEmpty() && !parentTypes.contains(inferredType)) {
                throw invalid("@ParentId %s.%s explicitly refers to %s but its ID type refers to %s"
                                      .formatted(type.getName(), parentProperty.property().name(),
                                                 parentTypes.stream().map(Class::getName).toList(),
                                                 inferredType.getName()));
            }
            for (Class<?> parentModelType : parentTypes) {
                if (isModelType(parentModelType)) {
                    continue;
                }
                throw invalid("@ParentId %s.%s refers to %s, which is not annotated with @Model"
                                      .formatted(type.getName(), parentProperty.property().name(),
                                                 parentModelType.getName()));
            }
            if (parentTypes.size() > 1 && !Id.class.isAssignableFrom(parentProperty.property().type())) {
                throw invalid("Polymorphic @ParentId %s.%s requires an Id property so its runtime parent type is unambiguous"
                                      .formatted(type.getName(), parentProperty.property().name()));
            }
            if (!path.isEmpty() && parentTypes.isEmpty()) {
                throw invalid("@ParentId path '%s' on %s.%s requires a typed Id<T> or an explicit parent model type"
                                      .formatted(path, type.getName(), parentProperty.property().name()));
            }
            result.add(new ParentReference(
                    parentProperty.property(), path, List.copyOf(parentTypes), annotation.apiDoc(),
                    annotation.deleteOnParentDeletion()));
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
            if (io.fluxzero.common.SearchUtils
                    .isInteger(segment)) {
                throw invalid("@ParentId path '%s' on %s.%s must not contain numeric segments because graph children are list-valued"
                                      .formatted(path, type.getName(), property.name()));
            }
        }
        if (io.fluxzero.common.search.JacksonInverter
                .isMetadataPath(path)) {
            throw invalid("@ParentId path '%s' on %s.%s must not use the reserved document metadata path"
                                  .formatted(path, type.getName(), property.name()));
        }
        return path;
    }

    private void validateGraphProjection(Model annotation) {
        GraphProjection projection =
                annotation.graphProjection();
        if (!annotation.materializeGraph()) {
            return;
        }
        if (!annotation.searchable()) {
            throw invalid("%s enables a graph projection but is not searchable"
                                  .formatted(type.getName()));
        }
        if (!projection.collection().isEmpty()
            && (projection.collection().isBlank()
                || !projection.collection().equals(
                projection.collection().trim()))) {
            throw invalid("Graph projection collection on %s must not be blank or have surrounding whitespace"
                                  .formatted(type.getName()));
        }
        LinkedHashSet<String> paths =
                new LinkedHashSet<>();
        LinkedHashSet<String> projectionPaths =
                new LinkedHashSet<>();
        for (GraphPathOverride override :
                projection.pathOverrides()) {
            validateProjectionPath(
                    override.path(),
                    "canonical override path");
            validateProjectionPath(
                    override.projectionPath(),
                    "projection override path");
            if (!paths.add(override.path())) {
                throw invalid("Duplicate graph projection path override '%s' on %s"
                                      .formatted(override.path(), type.getName()));
            }
            if (!projectionPaths.add(
                    override.projectionPath())) {
                throw invalid("Multiple graph projection paths on %s project to '%s'"
                                      .formatted(type.getName(), override.projectionPath()));
            }
        }
    }

    private void validateProjectionPath(
            String path, String description) {
        if (path.isEmpty() || path.isBlank()
            || !path.equals(path.trim())
            || path.startsWith("/")
            || path.endsWith("/")
            || path.contains("//")) {
            throw invalid("Graph projection %s '%s' on %s must be a non-empty relative path"
                                  .formatted(description, path, type.getName()));
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment)
                || "..".equals(segment)
                || io.fluxzero.common.SearchUtils
                        .isInteger(segment)) {
                throw invalid("Graph projection %s '%s' on %s contains a reserved path segment"
                                      .formatted(description, path, type.getName()));
            }
        }
        if (io.fluxzero.common.search.JacksonInverter
                .isMetadataPath(path)) {
            throw invalid("Graph projection %s '%s' on %s uses the reserved document metadata path"
                                  .formatted(description, path, type.getName()));
        }
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
            ApplyResult applyResult = kind == HandlerKind.APPLY
                    ? inspectApplyResult(executable) : ApplyResult.NONE;
            List<Class<?>> emittedPayloadTypes =
                    kind == HandlerKind.INTERCEPT_APPLY
                            ? inspectInterceptOutputs(executable) : List.of();
            Class<?> receiverModelType = model != null && executable instanceof Method method
                                         && !Modifier.isStatic(method.getModifiers()) ? type : null;
            if (kind == HandlerKind.APPLY && isVoid(executable) && (model != null || !parameters.isEmpty())) {
                throw invalid("Invalid @Apply method %s: void is not supported for @Model targets. "
                                      .formatted(executable.toGenericString())
                              + "Return the resulting model, or return null to delete it.");
            }
            validateParameterAmbiguity(executable, parameters);
            result.add(new HandlerMethod(
                    executable, kind, receiverModelType,
                    applyResult.targetModelTypes(),
                    applyResult.collection(), applyResult.dynamic(),
                    parameters, emittedPayloadTypes));
        }
    }

    private ApplyResult inspectApplyResult(Executable executable) {
        if (executable instanceof Constructor<?> constructor) {
            Class<?> resultType = constructor.getDeclaringClass();
            return isModelType(resultType)
                    ? new ApplyResult(List.of(resultType), false, false)
                    : ApplyResult.NONE;
        }
        if (!(executable instanceof Method method)) {
            return ApplyResult.NONE;
        }
        Type result = GenericTypeResolver.resolve(
                method.getGenericReturnType(), type,
                method.getDeclaringClass());
        Class<?> resultType = ReflectionUtils.rawClass(result);
        if (Collection.class.isAssignableFrom(resultType)) {
            List<Type> arguments = ReflectionUtils.getTypeArguments(result);
            if (arguments.size() != 1) {
                return ApplyResult.NONE;
            }
            Class<?> elementType = concreteType(arguments.getFirst());
            if (isModelType(elementType)) {
                return new ApplyResult(
                        List.of(elementType), true, false);
            }
            return Object.class.equals(elementType)
                    ? new ApplyResult(List.of(), true, true)
                    : ApplyResult.NONE;
        }
        if (isModelType(resultType)) {
            return new ApplyResult(
                    List.of(resultType), false, false);
        }
        return Object.class.equals(resultType)
                ? new ApplyResult(List.of(), false, true)
                : ApplyResult.NONE;
    }

    private static List<Class<?>> inspectInterceptOutputs(
            Executable executable) {
        if (!(executable instanceof Method method)) {
            return List.of();
        }
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        collectOutputTypes(method.getGenericReturnType(), result, new LinkedHashSet<>());
        return List.copyOf(result);
    }

    private static void collectOutputTypes(
            Type type,
            Set<Class<?>> result,
            Set<Type> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getLowerBounds()) {
                collectOutputTypes(bound, result, visited);
            }
            for (Type bound : wildcard.getUpperBounds()) {
                collectOutputTypes(bound, result, visited);
            }
            return;
        }
        if (type instanceof java.lang.reflect.GenericArrayType array) {
            collectOutputTypes(array.getGenericComponentType(), result, visited);
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> rawType = ReflectionUtils.rawClass(parameterized);
            if (Collection.class.isAssignableFrom(rawType)
                || Optional.class.isAssignableFrom(rawType)
                || java.util.stream.Stream.class.isAssignableFrom(rawType)) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    collectOutputTypes(argument, result, visited);
                }
                return;
            }
            if (!Object.class.equals(rawType)) {
                result.add(rawType);
            }
            return;
        }
        if (type instanceof Class<?> outputType) {
            if (outputType.isArray()) {
                collectOutputTypes(
                        outputType.getComponentType(), result, visited);
            } else if (!Object.class.equals(outputType)
                       && !void.class.equals(outputType)) {
                result.add(outputType);
            }
        }
    }

    private static List<ModelParameter> inspectModelParameters(Executable executable) {
        List<ModelParameter> result = new ArrayList<>();
        for (Parameter parameter : executable.getParameters()) {
            inspectModelParameter(parameter).ifPresent(result::add);
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
            for (Class<?> parentType : reference.parentModelTypes()) {
                validateParentGraph(parentType, visited, path);
            }
        }
        path.removeLast();
        visited.put(current, VisitState.VISITED);
    }

    private static Optional<ParameterType> modelParameterType(Parameter parameter) {
        if (isModelType(parameter.getType())) {
            return Optional.of(new ParameterType(parameter.getType(), false, false, false));
        }
        if (List.class.equals(parameter.getType()) || Collection.class.equals(parameter.getType())) {
            List<Type> collectionArguments = ReflectionUtils.getTypeArguments(parameter.getParameterizedType());
            if (collectionArguments.size() != 1
                || !(collectionArguments.getFirst() instanceof ParameterizedType elementType)
                || !Graph.class.isAssignableFrom(ReflectionUtils.rawClass(elementType.getRawType()))) {
                return Optional.empty();
            }
            List<Type> graphArguments = ReflectionUtils.getTypeArguments(elementType);
            if (graphArguments.size() != 1) {
                return Optional.empty();
            }
            Class<?> modelType = concreteType(graphArguments.getFirst());
            return isModelType(modelType)
                    ? Optional.of(new ParameterType(modelType, false, true, true))
                    : Optional.empty();
        }
        boolean entity = Entity.class.isAssignableFrom(parameter.getType());
        boolean graph = Graph.class.isAssignableFrom(parameter.getType());
        if (!entity && !graph) {
            return Optional.empty();
        }
        List<Type> arguments = ReflectionUtils.getTypeArguments(parameter.getParameterizedType());
        if (arguments.size() != 1) {
            return Optional.empty();
        }
        Class<?> entityType = concreteType(arguments.getFirst());
        return isModelType(entityType)
                ? Optional.of(new ParameterType(entityType, entity, graph, false))
                : Optional.empty();
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

    private record AliasProperty(Property property, String prefix, String postfix) {
        private String value(String value) {
            return prefix + value + postfix;
        }
    }

    /**
     * One outgoing parent relationship declared by a child model.
     *
     * @param path            optional parent-relative automatic composition path
     * @param parentModelTypes inferred or explicitly declared possible parent model types; empty for an untyped ID
     * @param apiDoc          optional documentation for the list-valued automatic composition path
     * @param deleteOnParentDeletion whether deletion of this parent owns the child lifecycle
     */
    public record ParentReference(
            Property property,
            String path,
            List<Class<?>> parentModelTypes,
            ApiDoc apiDoc,
            boolean deleteOnParentDeletion) {
        public ParentReference {
            parentModelTypes = List.copyOf(parentModelTypes);
        }

        public Object read(Object target) {
            return property.read(target);
        }

        /** Returns the statically unique parent type, or {@code null} for untyped and polymorphic references. */
        public Class<?> parentModelType() {
            return parentModelTypes.size() == 1 ? parentModelTypes.getFirst() : null;
        }

        /** Returns whether this reference can point to the supplied parent model type. */
        public boolean supports(Class<?> parentType) {
            return parentModelTypes.stream().anyMatch(candidate -> candidate.equals(parentType));
        }

        /**
         * Resolves the concrete parent model type for one runtime parent ID.
         */
        public Class<?> parentModelType(Object parentId) {
            if (parentModelTypes.size() < 2) {
                return parentModelType();
            }
            if (!(parentId instanceof Id<?> id)) {
                throw new IllegalArgumentException(
                        "Polymorphic @ParentId %s requires a typed Id value, but found %s"
                                .formatted(property.name(), parentId == null ? "null" : parentId.getClass().getName()));
            }
            Class<?> runtimeType = id.getType();
            List<Class<?>> exact = parentModelTypes.stream().filter(runtimeType::equals).toList();
            if (exact.size() == 1) {
                return exact.getFirst();
            }
            List<Class<?>> compatible = parentModelTypes.stream()
                    .filter(candidate -> candidate.isAssignableFrom(runtimeType)
                            || runtimeType.isAssignableFrom(candidate))
                    .toList();
            if (compatible.size() == 1) {
                return compatible.getFirst();
            }
            throw new IllegalArgumentException(
                    "Typed parent ID %s refers to %s, which does not select exactly one of %s"
                            .formatted(parentId, runtimeType.getName(),
                                       parentModelTypes.stream().map(Class::getName).toList()));
        }

        /** Returns the parent's exact persisted identity for a functional parent ID value. */
        public String repositoryId(Object parentId) {
            Class<?> parentModelType = parentModelType(parentId);
            return parentModelType == null
                    ? Objects.requireNonNull(parentId, "Parent ID must not be null").toString()
                    : EntityMetadata.of(parentModelType).repositoryId(parentId);
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
     * @param collectionApplyResult whether the apply returns an ordered collection of models
     * @param dynamicApplyResult whether returned model types require runtime validation
     * @param modelParameters   injected model value or {@link Entity} dependencies
     * @param emittedPayloadTypes statically known payload types emitted by an interceptor
     */
    public record HandlerMethod(
            Executable executable,
            HandlerKind kind,
            Class<?> receiverModelType,
            List<Class<?>> targetModelTypes,
            boolean collectionApplyResult,
            boolean dynamicApplyResult,
            List<ModelParameter> modelParameters,
            List<Class<?>> emittedPayloadTypes) {
        public HandlerMethod {
            targetModelTypes = List.copyOf(targetModelTypes);
            modelParameters = List.copyOf(modelParameters);
            emittedPayloadTypes = List.copyOf(emittedPayloadTypes);
        }

        /** Whether this handler has a statically or dynamically typed model return value. */
        public boolean hasApplyResult() {
            return !targetModelTypes.isEmpty()
                   || dynamicApplyResult;
        }
    }

    private record ApplyResult(
            List<Class<?>> targetModelTypes,
            boolean collection,
            boolean dynamic) {
        private static final ApplyResult NONE =
                new ApplyResult(List.of(), false, false);

        private ApplyResult {
            targetModelTypes = List.copyOf(targetModelTypes);
        }
    }

    /**
     * A model value, {@code Entity<Model>}, {@code Graph<Model>} or ordered graph collection parameter needed by a
     * handler.
     *
     * @param associationProperty explicit payload/metadata property qualifier, or {@code null} for automatic matching
     * @param associationExcludeMetadata whether the explicit qualifier must ignore message metadata
     */
    public record ModelParameter(
            Parameter parameter, Class<?> modelType, boolean entityWrapped, boolean graphWrapped,
            boolean collectionWrapped,
            String associationProperty,
            boolean associationExcludeMetadata) {
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
            ModelConflictPolicy conflictPolicy,
            AutomaticModelHandling automaticHandling,
            boolean eventSourced,
            boolean ignoreUnknownEvents,
            int snapshotPeriod,
            int maxSnapshotCount,
            boolean cached,
            int cachingDepth,
            int checkpointPeriod,
            CommitPolicy commitPolicy,
            EventPublication eventPublication,
            EventPublicationStrategy publicationStrategy,
            AggregateEventRouting eventRouting,
            boolean searchable,
            boolean materializeGraph,
            GraphProjection graphProjection,
            String collection,
            String timestampPath,
            String endPath) {

        static RootConfiguration model(Model annotation) {
            return new RootConfiguration(
                    RootKind.MODEL, annotation.conflictPolicy(), annotation.automaticHandling(),
                    annotation.eventSourced(), annotation.ignoreUnknownEvents(),
                    annotation.snapshotPeriod(), annotation.maxSnapshotCount(), annotation.cached(),
                    annotation.cachingDepth(), annotation.checkpointPeriod(), annotation.commitPolicy(),
                    annotation.eventPublication(), annotation.publicationStrategy(), annotation.eventRouting(),
                    annotation.searchable(), annotation.materializeGraph(), annotation.graphProjection(),
                    annotation.searchProjection().collection(), annotation.searchProjection().timestampPath(),
                    annotation.searchProjection().endPath());
        }

        static RootConfiguration aggregate(Aggregate annotation) {
            return new RootConfiguration(
                    RootKind.AGGREGATE, ModelConflictPolicy.DEFAULT, AutomaticModelHandling.DEFAULT,
                    annotation.eventSourced(), annotation.ignoreUnknownEvents(),
                    annotation.snapshotPeriod(), annotation.maxSnapshotCount(), annotation.cached(),
                    annotation.cachingDepth(), annotation.checkpointPeriod(), annotation.commitPolicy(),
                    annotation.eventPublication(), annotation.publicationStrategy(), annotation.eventRouting(),
                    annotation.searchable(), false, null, annotation.collection(), annotation.timestampPath(),
                    annotation.endPath());
        }

        /** Resolves root and handler-level transition settings without retaining either annotation shape. */
        public TransitionSettings transitionSettings(Apply apply) {
            return transitionSettings(
                    apply == null ? EventPublication.DEFAULT : apply.eventPublication(),
                    apply == null ? EventPublicationStrategy.DEFAULT : apply.publicationStrategy(),
                    apply == null ? AggregateEventRouting.DEFAULT : apply.eventRouting(),
                    apply == null ? ModelConflictPolicy.DEFAULT : apply.conflictPolicy());
        }

        TransitionSettings transitionSettings(
                EventPublication publicationOverride,
                EventPublicationStrategy strategyOverride,
                AggregateEventRouting routingOverride,
                ModelConflictPolicy conflictOverride) {
            EventPublication publication = publicationOverride == EventPublication.DEFAULT
                    ? eventPublication : publicationOverride;
            if (publication == EventPublication.DEFAULT) {
                publication = kind == RootKind.MODEL ? EventPublication.IF_MODIFIED : EventPublication.ALWAYS;
            }
            EventPublicationStrategy strategy = strategyOverride == EventPublicationStrategy.DEFAULT
                    ? publicationStrategy : strategyOverride;
            EventPublicationStrategy eventStrategy = strategy;
            if (strategy == EventPublicationStrategy.DEFAULT) {
                strategy = EventPublicationStrategy.STORE_AND_PUBLISH;
            }
            AggregateEventRouting routing = routingOverride == AggregateEventRouting.DEFAULT
                    ? eventRouting : routingOverride;
            if (routing == AggregateEventRouting.DEFAULT) {
                routing = AggregateEventRouting.MESSAGE_ROUTING_KEY;
            }
            ModelConflictPolicy conflict = conflictOverride == ModelConflictPolicy.DEFAULT
                    ? conflictPolicy : conflictOverride;
            return new TransitionSettings(this, publication, eventStrategy, strategy, routing, conflict);
        }

        RootConfiguration withTransitionDefaults(
                boolean eventSourced,
                EventPublication eventPublication,
                EventPublicationStrategy publicationStrategy,
                AggregateEventRouting eventRouting) {
            return new RootConfiguration(
                    kind, conflictPolicy, automaticHandling, eventSourced, ignoreUnknownEvents,
                    snapshotPeriod, maxSnapshotCount, cached, cachingDepth, checkpointPeriod, commitPolicy,
                    eventPublication, publicationStrategy, eventRouting, searchable, materializeGraph,
                    graphProjection, collection, timestampPath, endPath);
        }

        /** Resolves the shared periodic snapshot policy for this persisted root. */
        public SnapshotSettings snapshotSettings(boolean documentFallback) {
            return new SnapshotSettings(documentFallback ? 1 : snapshotPeriod, Math.max(1, maxSnapshotCount));
        }
    }

    /** Shared immutable snapshot trigger and retention settings for Aggregate and Model roots. */
    public record SnapshotSettings(int period, int maxCount) {

        public boolean enabled() {
            return period > 0;
        }

        public boolean due(long sequenceNumber, int storedEventCount) {
            return enabled() && storedEventCount > 0
                   && periodIndex(sequenceNumber) > periodIndex(sequenceNumber - storedEventCount);
        }

        private long periodIndex(long sequenceNumber) {
            return (sequenceNumber + 1L) / period;
        }
    }

    /** Shared resolved policy for one Aggregate or Model transition. */
    public record TransitionSettings(
            RootConfiguration root,
            EventPublication publication,
            EventPublicationStrategy eventStrategy,
            EventPublicationStrategy strategy,
            AggregateEventRouting routing,
            ModelConflictPolicy conflict) {

        public boolean forceModified() {
            return publication == EventPublication.ALWAYS
                   && strategy != EventPublicationStrategy.PUBLISH_ONLY;
        }

        public TransitionDecision decide(
                boolean modified, boolean cascadedDeletion,
                boolean publishOnlyUpdatesState) {
            if (cascadedDeletion) {
                return new TransitionDecision(true, root.eventSourced(), false, true);
            }
            if (publication == EventPublication.IF_MODIFIED && !modified) {
                return TransitionDecision.INACTIVE;
            }
            if (publication == EventPublication.NEVER) {
                boolean updateState = root.kind() == RootKind.AGGREGATE || modified;
                return new TransitionDecision(updateState, false, false, updateState);
            }
            return switch (strategy) {
                case STORE_AND_PUBLISH -> new TransitionDecision(true, true, true, true);
                case STORE_ONLY -> new TransitionDecision(true, true, false, true);
                case PUBLISH_ONLY -> new TransitionDecision(
                        true, false, true, modified && publishOnlyUpdatesState);
                case DEFAULT -> throw new IllegalStateException("Unresolved root publication strategy");
            };
        }
    }

    /** Storage-neutral effects of one resolved transition. */
    public record TransitionDecision(
            boolean active, boolean storeEvent,
            boolean publishEvent, boolean updateState) {
        private static final TransitionDecision INACTIVE =
                new TransitionDecision(false, false, false, false);
    }

    public enum RootKind {
        MODEL,
        AGGREGATE
    }

    private record ParentProperty(Property property, ParentId annotation) {
    }

    private record ParameterType(
            Class<?> modelType, boolean entityWrapped, boolean graphWrapped, boolean collectionWrapped) {
    }

    private static final class ParentGraphValidation {
        private static final ParentGraphValidation INSTANCE =
                new ParentGraphValidation();

        private ParentGraphValidation() {
        }
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
