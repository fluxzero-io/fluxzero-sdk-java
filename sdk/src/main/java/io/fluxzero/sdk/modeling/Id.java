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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import io.fluxzero.common.Leaf;
import io.fluxzero.common.api.HasId;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import lombok.Getter;
import lombok.NonNull;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;

/**
 * Object that represents the identifier of a specific entity. This object makes it easy to prefix the functional id of
 * an entity to with a value before storing or lookup in a repository to prevent clashes with other entities that may
 * share the same functional id.
 * <p>
 * Additionally, this object makes it possible to store and lookup entities using a case-insensitive identifier.
 * <p>
 * It also allows specifying the entity type which prevents the need for dynamic casting after loading the entity.
 * <p>
 * Because an {@code Id} represents a scalar domain value rather than a nested object graph, it implements
 * {@link Leaf}. This ensures Fluxzero treats identifier types as terminal values during reflection-based traversal
 * for features such as search indexing and data protection.
 * <p>
 * Concrete ID properties and standalone IDs serialize as a string of the functionalId. Properties declared as
 * {@code Id<?>}, {@code Id<T>} or abstract ID types instead retain a discriminator: {@code {"name":"model","id":"value"}}
 * for Model IDs, or {@code {"@class":"package.ConcreteId","id":"value"}} for other IDs. Model names resolve through
 * {@link Parent#types()}, a declared Model type argument, or the compile-time {@link ModelTypes} index; the Model must
 * declare that concrete ID class as its {@link EntityId}. Explicit Jackson type information retains its own format.
 * Unknown, conflicting and ambiguous discriminators are rejected. Previously stored polymorphic scalar values need
 * an upcaster when their type cannot be inferred; concrete scalar values remain supported.
 * Deserialization is done by
 * invoking a constructor on your subtype that accepts a single String argument. If such constructor does not exist,
 * please specify your own deserializer, using e.g. {@link JsonDeserialize @JsonDeserialize} on your type.
 * Discriminated properties also use that concrete subtype deserializer, with the original property context.
 *
 * @param <T> the entity type. I.e.: a typical class will look something like
 *            {@code public class ProjectId extends Id<Project>}.
 */
@JsonDeserialize(using = Id.IdDeserializer.class)
public abstract class Id<T> implements HasId, Comparable<Id<?>>, Leaf {
    @JsonValue
    @Getter
    String functionalId;
    @Getter
    Class<T> type;
    String repositoryId;

    /**
     * Construct a case-sensitive id for an entity without prefix. This constructor allows ids to be prefixed with a
     * value to prevent clashes with other entities that may share the same functional id.
     * <p>
     * The identifier's {@code type} will be determined using reflection.
     *
     * @param functionalId The functional id of the entity. The object's toString() method is used to get a string
     *                     representation of the functional id.
     */
    public Id(String functionalId) {
        this(functionalId, "");
    }


    /**
     * Construct a case-sensitive id for an entity without prefix. This constructor allows ids to be prefixed with a
     * value to prevent clashes with other entities that may share the same functional id.
     *
     * @param functionalId The functional id of the entity. The object's toString() method is used to get a string
     *                     representation of the functional id.
     * @param type         The entity's type. This may be a superclass of the actual entity.
     */
    public Id(String functionalId, Class<T> type) {
        this(functionalId, type, "");
    }

    /**
     * Construct a case-sensitive id for an entity. This constructor allows ids to be prefixed with a value to prevent
     * clashes with other entities that may share the same functional id.
     *
     * <p>
     * The identifier's {@code type} will be determined using reflection.
     *
     * @param functionalId The functional id of the entity. The object's toString() method is used to get a string
     *                     representation of the functional id.
     * @param prefix       The prefix that is prepended to the {@link #functionalId} to create the full id under which
     *                     this entity will be stored. Eg, if the prefix of an {@link Id} is "user-", and the id is
     *                     "pete123", the entity will be stored under "user-pete123".
     */
    public Id(String functionalId, String prefix) {
        this(functionalId, prefix, true);
    }

    /**
     * Construct a case-sensitive id for an entity. This constructor allows ids to be prefixed with a value to prevent
     * clashes with other entities that may share the same functional id.
     *
     * @param functionalId The functional id of the entity. The object's toString() method is used to get a string
     *                     representation of the functional id.
     * @param type         The entity's type. This may be a superclass of the actual entity.
     * @param prefix       The prefix that is prepended to the {@link #functionalId} to create the full id under which
     *                     this entity will be stored. Eg, if the prefix of an {@link Id} is "user-", and the id is
     *                     "pete123", the entity will be stored under "user-pete123".
     */
    public Id(String functionalId, Class<T> type, String prefix) {
        this(functionalId, type, prefix, true);
    }

    /**
     * Construct an id for an entity without prefix. This constructor allows ids to be case-insensitive.
     * <p>
     * The identifier's {@code type} will be determined using reflection.
     *
     * @param functionalId  The functional id of the entity. The object's toString() method is used to get a string
     *                      representation of the functional id.
     * @param caseSensitive whether this id is case-sensitive.
     */
    public Id(String functionalId, boolean caseSensitive) {
        this(functionalId, "", caseSensitive);
    }

    /**
     * Construct an id for an entity without prefix. This constructor allows ids to be case-insensitive.
     *
     * @param functionalId  The functional id of the entity. The object's toString() method is used to get a string
     *                      representation of the functional id.
     * @param type          The entity's type. This may be a superclass of the actual entity.
     * @param caseSensitive whether this id is case-sensitive.
     */
    public Id(String functionalId, Class<T> type, boolean caseSensitive) {
        this(functionalId, type, "", caseSensitive);
    }

    /**
     * Construct an id for an entity. This constructor allows ids to be prefixed with a value to prevent clashes with
     * other entities that may share the same functional id. It also enables ids to be case-insensitive.
     * <p>
     * The identifier's {@code type} will be determined using reflection.
     *
     * @param functionalId  The functional id of the entity. The object's toString() method is used to get a string
     *                      representation of the functional id.
     * @param prefix        The prefix that is prepended to the {@link #functionalId} to create the full id under which
     *                      this entity will be stored. Eg, if the prefix of an {@link Id} is "user-", and the id is
     *                      "pete123", the entity will be stored under "user-pete123".
     * @param caseSensitive whether this id is case-sensitive.
     */
    public Id(@NonNull String functionalId, @NonNull String prefix, boolean caseSensitive) {
        this.functionalId = functionalId;
        this.type = ReflectionUtils.getFirstTypeArgument(this.getClass().getGenericSuperclass());
        this.repositoryId = caseSensitive ? prefix + this.functionalId : prefix + this.functionalId.toLowerCase();
    }

    /**
     * Construct an id for an entity. This constructor allows ids to be prefixed with a value to prevent clashes with
     * other entities that may share the same functional id. It also enables ids to be case-insensitive.
     *
     * @param functionalId  The functional id of the entity. The object's toString() method is used to get a string
     *                      representation of the functional id.
     * @param type          The entity's type. This may be a superclass of the actual entity.
     * @param prefix        The prefix that is prepended to the {@link #functionalId} to create the full id under which
     *                      this entity will be stored. Eg, if the prefix of an {@link Id} is "user-", and the id is
     *                      "pete123", the entity will be stored under "user-pete123".
     * @param caseSensitive whether this id is case-sensitive.
     */
    public Id(@NonNull String functionalId, @NonNull Class<T> type, @NonNull String prefix, boolean caseSensitive) {
        if (functionalId.isBlank()) {
            throw new ValidationException("Id value should not be blank", Collections.emptySet());
        }
        this.functionalId = functionalId;
        this.type = type;
        this.repositoryId = caseSensitive ? prefix + this.functionalId : prefix + this.functionalId.toLowerCase();
    }

    @Override
    @JsonIgnore
    public String getId() {
        return functionalId;
    }

    /**
     * Returns the id under which the entity will be stored in a repository. This may differ from the functional
     */
    @Override
    public final String toString() {
        return repositoryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Id<?> id = (Id<?>) o;
        return type.equals(id.type) && repositoryId.equals(id.repositoryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, repositoryId);
    }

    @Override
    public int compareTo(Id<?> o) {
        return repositoryId.compareTo(o.repositoryId);
    }

    /** Writes concrete ID properties as scalars and polymorphic properties with a recoverable type discriminator. */
    public static class IdSerializer extends JsonSerializer<Id<?>> implements ContextualSerializer {
        private final IdTypeContext binding;
        private final JsonSerializer<Object> delegate;

        IdSerializer(JsonSerializer<Object> delegate, IdTypeContext binding) {
            this.delegate = delegate;
            this.binding = binding;
        }

        @Override
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property) throws JsonMappingException {
            JsonSerializer<?> contextual = provider.handlePrimaryContextualization(delegate, property);
            IdTypeContext context = property == null ? null : new IdTypeContext(property.getType(), property);
            if (context == null || !context.polymorphic()) {
                return contextual;
            }
            @SuppressWarnings("unchecked")
            JsonSerializer<Object> resolved = (JsonSerializer<Object>) contextual;
            return new IdSerializer(resolved, context);
        }

        @Override
        public void serialize(Id<?> value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (binding != null) {
                binding.validate(value);
            }
            if (binding == null || !binding.polymorphic()) {
                delegate.serialize(value, generator, provider);
                return;
            }
            try {
                generator.writeStartObject();
                if (IdTypeContext.isModel(value.getType())) {
                    if (IdTypeContext.modelIdType(value.getType()) != value.getClass()) {
                        throw new IllegalArgumentException("Model name does not uniquely identify this Id subtype");
                    }
                    generator.writeStringField("name", ModelNames.name(value.getType()));
                } else {
                    generator.writeStringField("@class", value.getClass().getName());
                }
                generator.writeStringField("id", value.getFunctionalId());
                generator.writeEndObject();
            } catch (IllegalArgumentException e) {
                throw JsonMappingException.from(generator, e.getMessage(), e);
            }
        }

        @Override
        public void serializeWithType(Id<?> value, JsonGenerator generator, SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            // Explicit Jackson polymorphism keeps its existing scalar payload and discriminator layout.
            if (binding != null) {
                binding.validate(value);
            }
            delegate.serializeWithType(value, generator, provider, typeSerializer);
        }
    }

    /** Reads scalar concrete IDs and discriminated polymorphic IDs. */
    public static class IdDeserializer extends JsonDeserializer<Id<?>> implements ContextualDeserializer {
        private final Class<? extends Id<?>> targetType;
        private final IdTypeContext binding;
        private final BeanProperty property;

        public IdDeserializer() {
            this(null);
        }

        private IdDeserializer(Class<? extends Id<?>> targetType) {
            this(targetType, null);
        }

        private IdDeserializer(Class<? extends Id<?>> targetType, IdTypeContext binding) {
            this(targetType, binding, null);
        }

        private IdDeserializer(Class<? extends Id<?>> targetType, IdTypeContext binding, BeanProperty property) {
            this.targetType = targetType;
            this.binding = binding;
            this.property = property;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property)
                throws JsonMappingException {
            var type = property == null ? context.getContextualType() : property.getType();
            if (type == null) {
                return this;
            }
            Class<?> rawType = idType(type);
            if (!Id.class.isAssignableFrom(rawType)) {
                return this;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Id<?>> idType = (Class<? extends Id<?>>) rawType;
            return new IdDeserializer(rawType == Id.class ? null : idType, new IdTypeContext(type, property), property);
        }

        private static Class<?> idType(JavaType type) {
            while (!Id.class.isAssignableFrom(type.getRawClass()) && type.getContentType() != null) {
                type = type.getContentType();
            }
            return type.getRawClass();
        }

        @Override
        public Object deserializeWithType(
                JsonParser parser, DeserializationContext context, TypeDeserializer typeDeserializer)
                throws IOException {
            JsonToken currentToken = parser.currentToken();
            if (currentToken == null) {
                currentToken = parser.nextToken();
            }
            if (targetType != null || currentToken != JsonToken.START_ARRAY) {
                return super.deserializeWithType(parser, context, typeDeserializer);
            }

            JsonToken typeToken = parser.nextToken();
            if (typeToken == null || !typeToken.isScalarValue()) {
                return context.reportInputMismatch(Id.class, "Expected scalar Id type id");
            }
            JavaType resolvedType = typeDeserializer.getTypeIdResolver()
                    .typeFromId(context, parser.getValueAsString());
            if (resolvedType == null || !Id.class.isAssignableFrom(resolvedType.getRawClass())) {
                return context.reportInputMismatch(Id.class, "Could not determine concrete Id subtype");
            }

            JsonToken valueToken = parser.nextToken();
            if (valueToken == JsonToken.END_ARRAY) {
                return context.reportInputMismatch(resolvedType, "Expected Id value after type id");
            }
            @SuppressWarnings("unchecked")
            Class<? extends Id<?>> idType = (Class<? extends Id<?>>) resolvedType.getRawClass();
            Id<?> value = new IdDeserializer(idType, binding).deserialize(parser, context);
            JsonToken endToken = parser.nextToken();
            if (endToken != JsonToken.END_ARRAY) {
                return context.reportInputMismatch(resolvedType, "Expected end of typed Id wrapper");
            }
            return value;
        }

        @Override
        public Id<?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.START_OBJECT) {
                var node = JsonNodeFactory.instance.objectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) {
                        return context.reportInputMismatch(Id.class, "Expected Id field");
                    }
                    String field = parser.currentName();
                    if (node.has(field)) {
                        return context.reportInputMismatch(Id.class, "Duplicate Id field: %s", field);
                    }
                    parser.nextToken();
                    node.set(field, parser.getCodec().readTree(parser));
                }
                if (node.has("name") || node.has("@class") || node.has("id")) {
                    return readDiscriminated(node, parser, context);
                }
                // Preserve the legacy concrete {functionalId: ...} representation.
                try (JsonParser legacy = node.traverse(parser.getCodec())) {
                    legacy.nextToken();
                    return readValue(legacy, context);
                }
            }
            return readValue(parser, context);
        }

        private Id<?> readDiscriminated(JsonNode node, JsonParser parser, DeserializationContext context)
                throws IOException {
            if (node.has("name") == node.has("@class") || node.size() != 2
                || !node.path("id").isTextual()
                || !(node.has("name") ? node.path("name") : node.path("@class")).isTextual()) {
                return context.reportInputMismatch(Id.class, "Expected Id with exactly one name or @class and a string id");
            }
            IdTypeContext resolvedBinding = binding == null
                    ? new IdTypeContext(context.getContextualType(), null) : binding;
            try {
                Class<?> model = node.has("name") ? resolvedBinding.resolveModel(node.get("name").textValue()) : null;
                Class<?> idType = model == null ? resolvedBinding.resolveClass(
                        node.get("@class").textValue(), context.getTypeFactory().getClassLoader())
                        : IdTypeContext.modelIdType(model);
                if (targetType != null && targetType != idType
                    && !(java.lang.reflect.Modifier.isAbstract(targetType.getModifiers())
                         && targetType.isAssignableFrom(idType))) {
                    return context.reportInputMismatch(Id.class, "Conflicting Id subtype discriminators");
                }
                if (model == null) {
                    var validator = context.getConfig().getPolymorphicTypeValidator();
                    JavaType base = context.constructType(Id.class);
                    var baseValidity = validator.validateBaseType(context.getConfig(), base);
                    var nameValidity = baseValidity == PolymorphicTypeValidator.Validity.INDETERMINATE
                            ? validator.validateSubClassName(context.getConfig(), base, idType.getName()) : baseValidity;
                    if (nameValidity == PolymorphicTypeValidator.Validity.DENIED
                        || nameValidity == PolymorphicTypeValidator.Validity.INDETERMINATE
                           && validator.validateSubType(context.getConfig(), base, context.constructType(idType))
                                   != PolymorphicTypeValidator.Validity.ALLOWED) {
                        return context.reportInputMismatch(Id.class, "Id class rejected by polymorphic type validator");
                    }
                }
                @SuppressWarnings("unchecked")
                Class<? extends Id<?>> concrete = (Class<? extends Id<?>>) idType;
                Id<?> result;
                try (JsonParser scalar = node.get("id").traverse(parser.getCodec())) {
                    scalar.nextToken();
                    JavaType concreteType = context.constructType(concrete);
                    JsonDeserializer<Object> deserializer = context.findNonContextualValueDeserializer(concreteType);
                    // Bind the inherited default directly, avoiding both property-type ambiguity and
                    // repeated contextual metadata work. Custom decoders retain Jackson's property context.
                    Object decoded = deserializer.getClass() == IdDeserializer.class
                            ? new IdDeserializer(concrete).readValue(scalar, context)
                            : context.handleSecondaryContextualization(deserializer, property, concreteType)
                                    .deserialize(scalar, context);
                    if (!concrete.isInstance(decoded)) {
                        return context.reportInputMismatch(Id.class, "Id decoder returned a conflicting subtype");
                    }
                    result = (Id<?>) decoded;
                }
                resolvedBinding.validate(result);
                if (model != null && result.getType() != model || model == null && IdTypeContext.isModel(result.getType())) {
                    throw new IllegalArgumentException("Id discriminator conflicts with its Model type");
                }
                return result;
            } catch (IllegalArgumentException | ClassNotFoundException e) {
                throw JsonMappingException.from(parser, e.getMessage(), e);
            }
        }

        private Id<?> readValue(JsonParser parser, DeserializationContext context) throws IOException {
            Class<? extends Id<?>> concreteTargetType = targetType(context);
            if (concreteTargetType == null) {
                return context.reportInputMismatch(Id.class, "Could not determine concrete Id subtype");
            }
            String functionalId;
            if (parser.currentToken() != null && parser.currentToken().isScalarValue()) {
                functionalId = parser.getValueAsString();
            } else {
                JsonNode node = parser.getCodec().readTree(parser);
                JsonNode functionalIdNode = node.get("functionalId");
                if (functionalIdNode == null) {
                    return context.reportInputMismatch(
                            concreteTargetType, "Expected scalar Id value or object with `functionalId` field");
                }
                functionalId = functionalIdNode.asText();
            }
            try {
                Id<?> result = (Id<?>) ReflectionUtils.getTypeMetadata(concreteTargetType)
                        .invoker(concreteTargetType.getDeclaredConstructor(String.class), true)
                        .invoke(null, functionalId);
                if (binding != null) {
                    binding.validate(result);
                }
                return result;
            } catch (NoSuchMethodException e) {
                return context.reportInputMismatch(
                        concreteTargetType,
                        "Id subtype %s must declare a single String constructor",
                        concreteTargetType.getName());
            } catch (Exception e) {
                throw JsonMappingException.from(
                        parser, "Could not deserialize Id subtype " + concreteTargetType.getName(), e);
            }
        }

        private Class<? extends Id<?>> targetType(DeserializationContext context) {
            if (targetType != null) {
                return targetType;
            }
            JavaType contextualType = context.getContextualType();
            if (contextualType == null) {
                return null;
            }
            Class<?> rawType = idType(contextualType);
            if (!Id.class.isAssignableFrom(rawType) || rawType == Id.class) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Id<?>> idType = (Class<? extends Id<?>>) rawType;
            return idType;
        }
    }
}
