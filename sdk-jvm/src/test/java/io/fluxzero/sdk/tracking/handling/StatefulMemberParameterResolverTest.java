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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.ImmutableEntity;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatefulMemberParameterResolverTest {

    @Test
    void metadataParameterViewResolvesMemberValueWithoutReflectionParameter() {
        MemberValue value = new MemberValue("member-1");
        Entity<?> entity = ImmutableEntity.<MemberValue>builder()
                .id("member-1")
                .type(MemberValue.class)
                .value(value)
                .build();
        var message = new StatefulHandler.StatefulMemberMessage(
                new DeserializingMessage(new Message(new Object()), MessageType.EVENT, null), entity);
        var resolver = new StatefulHandler.StatefulMemberParameterResolver(
                List.of(OwnerValue.class, MemberValue.class));
        ParameterView parameter = new MetadataParameterView(
                0, "member", MemberValue.class.getName(), Optional.empty(), Map.of());

        assertTrue(resolver.matches(parameter, null, message));
        assertSame(value, resolver.resolve(parameter, null).apply(message));
    }

    @Test
    void metadataExecutableViewCanApplyWhenAParameterMatchesKnownContextTypes() {
        var resolver = new StatefulHandler.StatefulMemberParameterResolver(List.of(MemberValue.class));
        var executable = new MetadataExecutableView(List.of(new MetadataParameterView(
                0, "member", MemberValue.class.getName(), Optional.empty(), Map.of())));

        assertTrue(resolver.mayApply(executable, OwnerValue.class));
    }

    record OwnerValue(String id) {
    }

    record MemberValue(String id) {
    }

    private record MetadataExecutableView(List<? extends ParameterView> parameters) implements ExecutableView {

        @Override
        public Kind kind() {
            return Kind.METHOD;
        }

        @Override
        public String targetTypeName() {
            return OwnerValue.class.getName();
        }

        @Override
        public String name() {
            return "handle";
        }

        @Override
        public String returnTypeName() {
            return "void";
        }

        @Override
        public Optional<Class<?>> targetClass() {
            return Optional.empty();
        }

        @Override
        public Optional<Executable> executable() {
            return Optional.empty();
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return Optional.empty();
        }
    }

    private record MetadataParameterView(
            int index,
            String name,
            String typeName,
            Optional<Class<?>> type,
            Map<Class<? extends Annotation>, Annotation> annotations) implements ParameterView {

        @Override
        public Optional<Parameter> parameter() {
            return Optional.empty();
        }

        @Override
        public <A extends Annotation> Optional<A> annotation(Class<A> annotationType) {
            return Optional.ofNullable(annotations.get(annotationType)).map(annotationType::cast);
        }
    }
}
