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
 *
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.Nullable;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;
import lombok.With;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.sdk.Fluxzero.loadAggregate;
import static io.fluxzero.sdk.Fluxzero.loadAggregateFor;
import static io.fluxzero.sdk.Fluxzero.loadEntity;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Slf4j
@SuppressWarnings({"rawtypes", "SameParameterValue", "unchecked"})
public class AggregateEntitiesTest {
    private TestFixture testFixture;

    @BeforeEach
    void setUp() {
        testFixture = TestFixture.create().given(
                fc -> loadAggregate("test", Aggregate.class).update(s -> Aggregate.builder().build()));
    }

    void expectEntity(Predicate<Entity<?>> predicate) {
        expectEntities(Aggregate.class, entities -> entities.stream().anyMatch(predicate));
    }

    void expectNoEntity(Predicate<Entity<?>> predicate) {
        expectEntities(Aggregate.class, entities -> entities.stream().noneMatch(predicate));
    }

    void expectEntities(Class<?> parentClass, Predicate<Collection<Entity<?>>> predicate) {
        testFixture
                .whenApplying(fc -> loadAggregate("test", (Class<?>) parentClass).allEntities().collect(toList()))
                .expectResult(predicate);
    }

    private boolean folderState(Folder root, List<String> path, List<String> expectedFolders, List<String> expectedFiles) {
        Folder folder = findFolder(root, path);
        return folder != null
               && folder.folders().stream().map(Folder::folderId).toList().equals(expectedFolders)
               && folder.files().stream().map(File::fileId).toList().equals(expectedFiles);
    }

    private Folder findFolder(Folder root, List<String> path) {
        Folder current = root;
        if (current == null || path.isEmpty() || !Objects.equals(current.folderId(), path.getFirst())) {
            return null;
        }
        for (int i = 1; i < path.size(); i++) {
            String childId = path.get(i);
            current = current.folders().stream()
                    .filter(folder -> Objects.equals(folder.folderId(), childId))
                    .findFirst()
                    .orElse(null);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @Nested
    class FindEntityTests {

        @Test
        void findSingleton() {
            expectEntity(e -> "id".equals(e.id()) && "childId".equals(e.idProperty()));
        }

        @Test
        void findSingletonWithCustomPath() {
            expectEntity(e -> "otherId".equals(e.id()) && "customId".equals(e.idProperty()));
        }

        @Test
        void noEntityIfNull() {
            expectNoEntity(e -> "missingId".equals(e.id()));
        }

        @Test
        void findEntitiesInList() {
            expectEntity(e -> "list0".equals(e.id()));
            expectEntity(e -> "list1".equals(e.id()));
            expectEntity(e -> e.id() == null);
        }

        @Test
        void findEntitiesInMapUsingKey() {
            expectEntity(e -> new Key("map0").equals(e.id()));
            expectEntity(e -> new Key("map1").equals(e.id()));
        }

        @Test
        void findGrandChild() {
            expectEntity(e -> e.entities().stream().findFirst().map(c -> "grandChild".equals(c.id())).orElse(false));
        }

        @Test
        void findByAlias() {
            expectEntity(e -> e.getEntity(new GrandChildAlias()).isPresent());
        }

        @Test
        void loadEmptyEntityById() {
            testFixture.whenApplying(fc -> loadAggregateFor(new MissingChildId("missing")))
                    .expectResult(e -> e.isEmpty() && e.type().equals(MissingChild.class));
        }

        @Test
        void findEntityViaMetaMember() {
            testFixture.whenApplying(fc -> loadAggregate("meta-test", MetaAggregate.class)
                            .update(s -> MetaAggregate.builder().build()).allEntities().collect(toList()))
                    .expectResult(entities -> entities.stream().anyMatch(e -> "meta-child".equals(e.id())
                                                                              && "metaChildId".equals(e.idProperty())));
        }
    }

    @Nested
    class AssertLegalTests {
        @BeforeEach
        void setUp() {
            testFixture.registerHandlers(new CommandHandler());
        }

        @Test
        void testRouteToChild() {
            testFixture.whenCommand(new CommandWithRoutingKey("id"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        @Test
        void testRouteToGrandchild() {
            testFixture = TestFixture.create().given(
                    fc -> loadAggregate("test", Aggregate.class).update(s -> Aggregate.builder().build()));
            testFixture.whenCommand(new Object() {
                        @HandleCommand
                        void handle() {
                            Entity<Aggregate> entity = loadAggregate("test", Aggregate.class);
                            entity.assertAndApply(this);
                        }

                        @Apply
                        GrandChild apply(GrandChild grandChild) {
                            throw new MockException();
                        }

                        String getGrandChildId() {
                            return "grandChild";
                        }
                    })
                    .expectExceptionalResult(MockException.class);
        }

        @Test
        void testNoChildRoute() {
            testFixture.whenCommand(new CommandWithoutRoutingKey("somethingRandom")).expectSuccessfulResult();
        }

        @Test
        void testPropertyMatchesChild() {
            testFixture.whenCommand(new CommandWithoutRoutingKey("otherId"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        @Test
        void testPropertyValueMatchesNothing() {
            testFixture.whenCommand(new CommandWithoutRoutingKey("somethingRandom")).expectSuccessfulResult();
        }

        @Test
        void testPropertyPathMatchesNothing() {
            testFixture.whenCommand(new CommandWithWrongProperty("id")).expectSuccessfulResult()
                    .expectEvents(new CommandWithWrongProperty("id"));
        }

        @Test
        void testRouteToGrandchildButFailingOnChild() {
            testFixture.whenCommand(new CommandTargetingGrandchildButFailingOnParent("grandChild"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        @Test
        void updateCommandExpectsExistingChild() {
            testFixture.whenCommand(new UpdateCommandThatFailsIfChildDoesNotExist("whatever"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        @Test
        void updateCommandExpectsExistingChild_kotlin() {
            testFixture.whenCommand(new KotlinUpdateCommandThatFailsIfChildDoesNotExist("whatever"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        @Test
        void testListChildAssertion() {
            testFixture.whenCommand(new CommandWithRoutingKey("list0"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        @Test
        void assertLegalOnChildEntity() {
            AggregateEntitiesTest.this.setUp();
            testFixture.registerHandlers(new Object() {
                @HandleCommand
                void handle(CommandWithRoutingKey command) {
                    loadEntity(command.target()).assertLegal(command);
                }
            });
            testFixture.whenCommand(new CommandWithRoutingKey("list0"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        @Test
        void testRouteToChildHandledByEntity() {
            testFixture.whenCommand(new CommandWithRoutingKeyHandledByEntity("id"))
                    .expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
        }

        class CommandHandler {
            @HandleCommand
            void handle(Object command) {
                loadAggregate("test", Aggregate.class).assertAndApply(command);
            }
        }
    }

    @Nested
    class InterceptApplyTests {
        @BeforeEach
        void setUp() {
            testFixture.registerHandlers(new Object() {
                @HandleCommand
                void handle(Object command) {
                    loadAggregate("test", Aggregate.class).assertAndApply(command);
                }
            });
        }

        @Test
        void commandWithoutInterceptTriggersException() {
            testFixture.whenCommand(new FailingCommand()).expectExceptionalResult(MockException.class);
        }

        @Test
        void ignoreApplyByReturningVoid() {
            testFixture.whenCommand(new FailingCommand() {
                @InterceptApply
                void intercept() {
                }
            }).expectNoResult();
        }

        @Test
        void ignoreApplyByReturningNull() {
            testFixture.whenCommand(new FailingCommand() {
                @InterceptApply
                Object intercept() {
                    return null;
                }
            }).expectNoResult();
        }

        @Test
        void ignoreApplyByReturningEmptyStream() {
            testFixture.whenCommand(new FailingCommand() {
                @InterceptApply
                Stream<?> intercept() {
                    return Stream.empty();
                }
            }).expectNoResult();
        }

        @Test
        void ignoreApplyByReturningEmptyCollection() {
            testFixture.whenCommand(new FailingCommand() {
                @InterceptApply
                Collection<?> intercept() {
                    return List.of();
                }
            }).expectNoResult().expectNoEvents();
        }

        @Test
        void returnDifferentCommand() {
            MissingChildId childId = new MissingChildId("missing");
            testFixture.whenEventsAreApplied("test", Aggregate.class, new Message(new FailingCommand() {
                        @InterceptApply
                        Object intercept(FailingCommand input) {
                            return Message.asMessage(new AddChild(childId))
                                    .addMetadata("fooNew", "barNew");
                        }
                    }, Metadata.of("foo", "bar")))
                    .expectEvents((Predicate<Message>) m -> m.getMetadata().containsKey("foo"))
                    .expectEvents((Predicate<Message>) m -> m.getMetadata().containsKey("fooNew"))
                    .expectThat(fc -> expectEntity(e -> e.get() instanceof MissingChild && childId.equals(e.id())));
        }

        @Test
        void returnTwoCommands() {
            MissingChildId childId = new MissingChildId("missing");
            testFixture.whenCommand(new FailingCommand() {
                @InterceptApply
                List<?> intercept() {
                    return List.of(new AddChild(childId), new UpdateChild("id", "data"));
                }
            }).expectThat(fc -> expectEntity(
                    e -> e.get() instanceof Child && ((Child) e.get()).getData().equals("data")));
        }

        @Test
        void returnTwoCommandsSecondFails() {
            MissingChildId childId = new MissingChildId("missing");
            testFixture.whenCommand(new FailingCommand() {
                @InterceptApply
                List<?> intercept() {
                    return List.of(new AddChild(childId), new FailingCommand());
                }
            }).expectExceptionalResult(MockException.class);
        }

        @Test
        void returnNestedCommands() {
            testFixture.whenCommand(new Object() {
                @InterceptApply
                Object intercept() {
                    return new UpdateChildNested();
                }
            }).expectThat(fc -> expectEntity(
                    e -> e.get() instanceof Child && ((Child) e.get()).getData().equals("data")));
        }

        @Test
        void secondAddChildNotAllowed() {
            AddChild command = new AddChild(new MissingChildId("missing"));
            testFixture.givenCommands(command).whenCommand(command)
                    .expectNoEvents().expectNoErrors();
        }

        class FailingCommand {
            @Getter
            private final String missingChildId = "123";

            @AssertLegal
            void apply(Aggregate aggregate) {
                throw new MockException();
            }
        }

        @Value
        class AddChild {
            MissingChildId missingChildId;

            @InterceptApply
            Object intercept(MissingChild child) {
                return null;
            }

            @Apply
            MissingChild apply() {
                return MissingChild.builder().missingChildId(missingChildId).build();
            }
        }

        @Value
        class UpdateChild {
            @RoutingKey
            Object childId;
            Object data;

            @Apply
            Object apply(Updatable child) {
                return child.withData(data);
            }
        }

        @Value
        class UpdateChildNested {
            @InterceptApply
            List<?> intercept() {
                return List.of(new AddChild(new MissingChildId("missing")), new UpdateChild("id", "data"));
            }
        }

    }

    @Nested
    class CommitTests {
        Object event = "whatever";

        @Test
        void exceptionPreventsEvent() {
            testFixture.registerHandlers(new Object() {
                @HandleCommand
                void handle(Object command) {
                    Entity<Aggregate> entity = loadAggregate("test", Aggregate.class);
                    entity.apply(command);
                    throw new MockException();
                }
            }).whenCommand(event).expectNoEvents();
        }

        @Test
        void commitBeforeHandlerEndYieldsEvent() {
            testFixture.registerHandlers(new Object() {
                @HandleCommand
                void handle(Object command) {
                    Entity<Aggregate> entity = loadAggregate("test", Aggregate.class);
                    entity.apply(command);
                    entity.commit();
                    throw new MockException();
                }
            }).whenCommand(event).expectOnlyEvents(event);
        }

        @Test
        void exceptionOtherAggregatePreventsEvent() {
            testFixture.registerHandlers(new Object() {
                @HandleCommand
                void handle(Object command) {
                    loadAggregate("test", Aggregate.class).apply("first").commit();
                    loadAggregate("test2", Aggregate.class).apply("second");
                    throw new MockException();
                }
            }).whenCommand(event).expectOnlyEvents("first");
        }
    }

    @Nested
    class AsEntityTests {

        @Test
        void applyModifiesAggregateValue() {
            Aggregate input = Aggregate.builder().build();
            MissingChildId childId = new MissingChildId("missing");
            testFixture
                    .whenApplying(fc -> fc.aggregateRepository().asEntity(input).apply(new AddChild(childId)))
                    .expectResult(e -> !e.get().equals(input))
                    .expectResult(e -> e.allEntities()
                            .anyMatch(c -> c.get() instanceof MissingChild && childId.equals(c.id())));
        }

        @Test
        void applyDoesNotYieldAnyEvents() {
            (testFixture = testFixture.spy())
                    .whenApplying(fc -> fc.aggregateRepository().asEntity(
                            Aggregate.builder().build()).apply(new AddChild(new MissingChildId("missing"))))
                    .expectThat(fc -> verifyNoInteractions(fc.eventStore()));
        }

        @Test
        void fromNullValue() {
            MissingChildId childId = new MissingChildId("missing");
            testFixture
                    .whenApplying(fc -> fc.aggregateRepository().asEntity(null)
                            .apply(new CreateAggregate(), new AddChild(childId)))
                    .expectResult(e -> e.allEntities()
                            .anyMatch(c -> c.get() instanceof MissingChild && childId.equals(c.id())));
        }

        @Value
        class CreateAggregate {

            @Apply
            Aggregate apply() {
                return Aggregate.builder().build();
            }
        }

        @Value
        class AddChild {
            MissingChildId missingChildId;

            @Apply
            MissingChild apply() {
                return MissingChild.builder().missingChildId(missingChildId).build();
            }
        }
    }

    @Nested
    class EntityInjectionTests {
        @Test
        void entityShouldNotGetInjectedIfItIsOfTheWrongType() {
            testFixture.registerHandlers(
                            new Object() {
                                @HandleEvent
                                void shouldNotBeInvoked(Entity<String> entity) {
                                    throw new UnsupportedOperationException();
                                }
                            }
                    ).whenEvent(new Object() {
                        @RoutingKey
                        private final String someKey = "whatever";
                    })
                    .expectNoErrors();
        }
    }

    @Nested
    class ApplyTests {

        @BeforeEach
        void setUp() {
            testFixture.registerHandlers(new Object() {
                @HandleCommand
                void handle(Object command) {
                    loadAggregate("test", Aggregate.class).apply(command);
                }
            });
        }

        @Nested
        class SingletonTests {

            @Test
            void testAddSingleton() {
                MissingChildId childId = new MissingChildId("missing");
                testFixture.whenCommand(new AddChild(childId))
                        .expectThat(fc -> expectEntity(
                                e -> e.get() instanceof MissingChild && childId.equals(e.id())));
            }

            @Test
            void testAddSingleton_illegalWhenParentMissing() {
                MissingChildId childId = new MissingChildId("missing");
                TestFixture.create()
                        .registerHandlers(new Object() {
                            @HandleCommand
                            void handle(Object command) {
                                loadAggregate("test", Aggregate.class).apply(command);
                            }
                        })
                        .whenCommand(new AddChild(childId))
                        .expectExceptionalResult(Entity.NOT_FOUND_EXCEPTION)
                        .andThen()
                        .withProperty("fluxzero.assert.apply-compatibility.exception.not-found",
                                      "Parent or child not found")
                        .whenCommand(new AddChild(childId))
                        .expectExceptionalResult(new IllegalCommandException("Parent or child not found"));
            }

            @Test
            void testAddSingletonTwiceNotAllowed() {
                MissingChildId childId = new MissingChildId("missing");
                testFixture
                        .givenCommands(new AddChild(childId))
                        .whenCommand(new AddChild(childId))
                        .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION)
                        .andThen()
                        .withProperty("fluxzero.assert.apply-compatibility.exception.already-exists",
                                      "Child already exists")
                        .whenCommand(new AddChild(childId))
                        .expectExceptionalResult(new IllegalCommandException("Child already exists"))
                        .andThen()
                        .withProperty("fluxzero.assert.apply-compatibility", false)
                        .whenCommand(new AddChild(childId))
                        .expectSuccessfulResult();
            }

            @Test
            void findChildJustAfterAdding() {
                MissingChildId childId = new MissingChildId("missing");
                TestFixture.create().given(
                                fc -> loadAggregate("test", Aggregate.class).update(s -> Aggregate.builder().build()))
                        .registerHandlers(new Object() {
                            @HandleCommand
                            Entity<?> handle(AddChild command) {
                                loadAggregate("test", Aggregate.class).apply(command);
                                return loadEntity(command.getMissingChildId());
                            }
                        })
                        .whenCommand(new AddChild(childId))
                        .<Entity<?>>expectResult(e -> e.get() instanceof MissingChild && childId.equals(e.id()));
            }

            @Test
            void testAddChildAndGrandChild() {
                MissingChildId childId = new MissingChildId("missing");
                testFixture.whenCommand(new AddChildAndGrandChild(childId, "missingGc"))
                        .expectThat(fc -> {
                            expectEntity(e -> Objects.equals(e.id(), childId));
                            expectEntity(e -> Objects.equals(e.id(), "missingGc"));
                        });
            }

            @Test
            void testUpdateSingleton() {
                testFixture.whenCommand(new UpdateChild("id", "data"))
                        .expectThat(fc -> expectEntity(
                                e -> e.get() instanceof Child && ((Child) e.get()).getData().equals("data")));
            }

            @Test
            void testUpdateChildAndRootInSingleApply() {
                testFixture.whenCommand(new UpdateChildAndAggregate("id", "data"))
                        .expectTrue(fc -> {
                            Aggregate aggregate = loadAggregate("test", Aggregate.class).get();
                            return "id:data".equals(aggregate.getClientReference())
                                   && "data".equals(aggregate.getSingleton().getData())
                                   && aggregate.getMap().containsKey(new Key("map0"));
                        });
            }

            @Test
            void testUpdateGrandChildChildAndRootInSingleApply() {
                testFixture.whenCommand(new UpdateGrandChildHierarchy("grandChild", "grandChild2"))
                        .expectTrue(fc -> {
                            Aggregate aggregate = loadAggregate("test", Aggregate.class).get();
                            ChildWithChild child = aggregate.getChildWithGrandChild();
                            return "grandChild2".equals(child.getGrandChild().grandChildId())
                                   && "child-grandChild2".equals(child.getWithChildId())
                                   && "child-grandChild2".equals(aggregate.getClientReference())
                                   && aggregate.getMap().containsKey(new Key("map0"));
                        });
            }

            @Test
            void testUpdateNestedMemberInRecordOwnerUsingWithMethod() {
                TestFixture.create().given(fc -> loadAggregate("record", RecordAggregate.class)
                                .update(s -> new RecordAggregate("record",
                                                                 new RecordChild("recordChild",
                                                                                 new RecordGrandChild("gc0")))))
                        .registerHandlers(new Object() {
                            @HandleCommand
                            void handle(Object command) {
                                loadAggregate("record", RecordAggregate.class).apply(command);
                            }
                        })
                        .whenCommand(new UpdateRecordGrandChild("gc0", "gc1"))
                        .expectTrue(fc -> "gc1".equals(loadAggregate("record", RecordAggregate.class)
                                                               .get().child().grandChild().recordGrandChildId()));
            }

            @Test
            void testUpdateSingleton_illegalBeforeAdding() {
                testFixture.whenCommand(new UpdateChild("missing", "data"))
                        .expectExceptionalResult(Entity.NOT_FOUND_EXCEPTION)
                        .andThen()
                        .withProperty("fluxzero.assert.apply-compatibility.exception.not-found", "Child not found")
                        .whenCommand(new UpdateChild("missing", "data"))
                        .expectExceptionalResult(new IllegalCommandException("Child not found"))
                        .andThen()
                        .withProperty("fluxzero.assert.apply-compatibility", false)
                        .whenCommand(new UpdateChild("missing", "data"))
                        .expectSuccessfulResult();
            }

            @Test
            void testRemoveSingleton() {
                testFixture.whenCommand(new RemoveChild("id"))
                        .expectThat(fc -> {
                            expectNoEntity(e -> "id".equals(e.id()));
                            expectEntity(e -> e.root().previous().allEntities().anyMatch(p -> "id".equals(p.id())));
                            expectEntity(e -> "otherId".equals(e.id()));
                            expectEntity(e -> e.previous() != null && "otherId".equals(e.previous().id()));
                        });
            }

            @Test
            void testRemoveSingleton_noExceptionIfNotFoundDueToApplyOverride() {
                testFixture.whenCommand(new RemoveChild("missing"))
                        .expectNoErrors();
            }

            @Test
            void applyOnChildEntity() {
                AggregateEntitiesTest.this.setUp();
                testFixture.registerHandlers(new Object() {
                    @HandleCommand
                    void handle(UpdateChild command) {
                        loadEntity(command.getChildId()).assertAndApply(command);
                    }
                });
                testFixture.whenCommand(new UpdateChild("id", "data"))
                        .expectThat(fc -> expectEntity(
                                e -> e.get() instanceof Child && ((Child) e.get()).getData().equals("data")));
            }

            @Test
            void addStringAlias() {
                testFixture.whenCommand(new Object() {
                            @Apply
                            Aggregate apply(Aggregate aggregate) {
                                return aggregate.toBuilder().clientReference("clientRef").build();
                            }
                        })
                        .expectTrue(fc -> {
                            Entity<Object> entity = loadEntity("clientRef");
                            return entity.isPresent() && entity.isRoot();
                        });
            }

            @Test
            void addStringAliases() {
                testFixture.whenCommand(new Object() {
                            @Apply
                            Aggregate apply(Aggregate aggregate) {
                                return aggregate.toBuilder()
                                        .otherReference("clientRef1").otherReference("clientRef2")
                                        .otherReference(null).build();
                            }
                        })
                        .expectFalse(fc -> loadEntity("other-clientRef").isPresent())
                        .expectFalse(fc -> loadEntity("other-null").isPresent())
                        .expectTrue(fc -> loadEntity("other-clientRef1").isPresent())
                        .expectTrue(fc -> loadEntity("other-clientRef2").isRoot());
            }

            @Test
            void checkIfEventHandlerGetsEntity() {
                testFixture.registerHandlers(new EventHandler())
                        .whenCommand(new AddChild(new MissingChildId("missing")))
                        .expectEvents("added child to: test");
            }

            @Test
            void assertThatWrongEventHandlerDoesNotGetEntity() {
                testFixture.registerHandlers(new WrongEventHandler())
                        .whenCommand(new AddChild(new MissingChildId("missing")))
                        .expectNoEventsLike("added child to: test");
            }

            @Test
            void checkIfEventHandlerGetsEntity_unwrapped() {
                testFixture.registerHandlers(new EventHandler())
                        .whenCommand(new UpdateChild("id", "missing"))
                        .expectEvents("updated child of: test");
            }

            @Value
            class AddChild {
                MissingChildId missingChildId;

                @Apply
                MissingChild apply() {
                    return MissingChild.builder().missingChildId(missingChildId).build();
                }
            }

            @Value
            class AddChildAndGrandChild {
                MissingChildId missingChildId;
                String missingGrandChildId;

                @Apply
                MissingChild createChild() {
                    return MissingChild.builder().missingChildId(missingChildId)
                            .grandChild(new MissingGrandChild(missingGrandChildId)).build();
                }
            }

            class EventHandler {
                @HandleEvent
                void handle(Entity<Aggregate> entity) {
                    Fluxzero.publishEvent("added child to: " + entity.id());
                }

                @HandleEvent
                void handle(UpdateChild event, Aggregate entity) {
                    Fluxzero.publishEvent("updated child of: " + entity.getId());
                }
            }

            class WrongEventHandler {
                @HandleEvent
                void handle(Entity<String> entity) {
                    Fluxzero.publishEvent("added child to: " + entity.id());
                }
            }
        }

        @Nested
        class ListTests {
            @Test
            void testAddListChild() {
                testFixture.whenCommand(new AddListChild("list2"))
                        .expectThat(fc -> expectEntity(
                                e -> e.get() instanceof ListChild && "list2".equals(e.id())))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getList().size() == 4);
            }

            @Test
            void addToNullList() {
                testFixture.whenCommand(new AddNullListChild("nullChild"))
                        .expectThat(fc -> expectEntity(
                                e -> e.get() instanceof NullListChild && "nullChild".equals(e.id())))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getNullList().size() == 1);
            }

            @Test
            void testUpdateListChild() {
                testFixture.whenCommand(new UpdateChild("list1", "data"))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getList().get(1).data()
                                .equals("data"));
            }

            @Test
            void testRemoveListChild() {
                testFixture.whenCommand(new RemoveChild("list1"))
                        .expectThat(fc -> expectNoEntity(e -> "list1".equals(e.id())))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getList().size() == 2);
            }

            @Value
            class AddListChild {
                String listChildId;

                @Apply
                ListChild apply() {
                    return ListChild.builder().listChildId(listChildId).build();
                }
            }

            @Value
            class AddNullListChild {
                String nullListChildId;

                @Apply
                NullListChild apply() {
                    return new NullListChild(nullListChildId);
                }
            }
        }

        @Nested
        class MapTests {
            @Test
            void testAddMapChild() {
                testFixture.whenCommand(new AddMapChild(new Key("map2")))
                        .expectEvents(AddMapChild.class)
                        .expectThat(fc -> expectEntity(
                                e -> e.get() instanceof MapChild && new Key("map2").equals(e.id())))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getMap().size() == 3);
            }

            @Test
            void testAddMapChild_storeOnly() {
                (testFixture = testFixture.spy())
                        .whenCommand(new StoreOnlyAddMapChild(new Key("map2")))
                        .expectNoEvents()
                        .expectThat(fc -> verify(fc.client().getEventStoreClient())
                                .storeEvents(anyString(), anyList(), eq(true)))
                        .expectThat(fc -> expectEntity(
                                e -> e.get() instanceof MapChild && new Key("map2").equals(e.id())))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getMap().size() == 3);

            }

            @Test
            void testUpdateMapChild() {
                testFixture.whenCommand(new UpdateChild(new Key("map1"), "data"))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getMap().get(new Key("map1"))
                                .data().equals("data"));
            }

            @Test
            void testRemoveMapChild() {
                testFixture.whenCommand(new RemoveChild(new Key("map1")))
                        .expectThat(fc -> expectNoEntity(e -> new Key("map1").equals(e.id())))
                        .expectTrue(fc -> loadAggregate("test", Aggregate.class).get().getMap().size() == 1);
            }

            @Value
            class AddMapChild {
                Key mapChildId;

                @Apply
                MapChild apply(@NonNull Aggregate aggregate, @NonNull Metadata metadata) {
                    return MapChild.builder().mapChildId(mapChildId).build();
                }
            }

            @Value
            class StoreOnlyAddMapChild {
                Key mapChildId;

                @Apply(publicationStrategy = EventPublicationStrategy.STORE_ONLY)
                MapChild apply(@NonNull Aggregate aggregate, @NonNull Metadata metadata) {
                    return MapChild.builder().mapChildId(mapChildId).build();
                }
            }
        }

        @Value
        class RemoveChild {
            @RoutingKey
            Object id;

            @Apply(disableCompatibilityCheck = true)
            Object apply(Updatable target, @NonNull Aggregate aggregate, @NonNull Metadata metadata) {
                return null;
            }
        }

        @Value
        class UpdateChild {
            @RoutingKey
            Object childId;
            Object data;

            @Apply
            Object apply(Updatable child, @NonNull Aggregate aggregate, @NonNull Metadata metadata) {
                return child.withData(data);
            }
        }

        @Value
        class UpdateChildAndAggregate {
            @RoutingKey
            String childId;
            String data;

            @Apply
            Child apply(Child child) {
                return child.withData(data);
            }

            @Apply
            Aggregate apply(Aggregate aggregate) {
                return aggregate.toBuilder().clientReference(childId + ":" + data).build();
            }
        }

        @Value
        class UpdateGrandChildHierarchy {
            @RoutingKey
            String grandChildId;
            String newGrandChildId;

            @Apply
            GrandChild apply(GrandChild grandChild) {
                return new GrandChild(newGrandChildId, grandChild.alias());
            }

            @Apply
            ChildWithChild apply(ChildWithChild child) {
                return new ChildWithChild("child-" + child.getGrandChild().grandChildId(), child.getGrandChild());
            }

            @Apply
            Aggregate apply(Aggregate aggregate) {
                return aggregate.toBuilder()
                        .clientReference(aggregate.getChildWithGrandChild().getWithChildId())
                        .build();
            }
        }

    }

    @Nested
    class MutableEntityTests {
        private final TestFixture testFixture = TestFixture.create(new CommandHandler()).given(
                fc -> loadAggregate("test", MutableAggregate.class)
                        .update(s -> new MutableAggregate(null)));

        @Test
        void createMutableEntity() {
            testFixture.whenCommand(new CreateMutableEntity("childId")).expectThat(
                    fc -> expectEntity(MutableAggregate.class, e -> "childId".equals(e.id())));
        }

        @Test
        void deleteMutableEntity() {
            testFixture.givenCommands(new CreateMutableEntity("childId"))
                    .whenCommand(new DeleteMutableEntity("childId")).expectThat(
                            fc -> expectNoEntity(MutableAggregate.class, e -> "childId".equals(e.id())));
        }

        class CommandHandler {
            @HandleCommand
            void handle(Object command) {
                loadAggregate("test", MutableAggregate.class).apply(command);
            }
        }

        @Value
        class CreateMutableEntity {
            @RoutingKey
            String id;

            @Apply
            MutableEntity create() {
                return new MutableEntity(id);
            }
        }

        @Value
        class DeleteMutableEntity {
            @RoutingKey
            String id;

            @Apply
            MutableEntity delete(MutableEntity entity) {
                return null;
            }
        }

        @Data
        @AllArgsConstructor
        class MutableAggregate {
            @Member
            MutableEntity child;
        }

        @Data
        @AllArgsConstructor
        class MutableEntity {
            @EntityId
            String id;
        }

        void expectEntity(Class<?> parentClass, Predicate<Entity<?>> predicate) {
            expectEntities(parentClass, entities -> entities.stream().anyMatch(predicate));
        }

        void expectNoEntity(Class<?> parentClass, Predicate<Entity<?>> predicate) {
            expectEntities(parentClass, entities -> entities.stream().noneMatch(predicate));
        }

        void expectEntities(Class<?> parentClass, Predicate<Collection<Entity<?>>> predicate) {
            testFixture
                    .whenApplying(fc -> loadAggregate("test", (Class) parentClass).allEntities().collect(toList()))
                    .expectResult(predicate);
        }

    }

    @Nested
    class loadForTests {
        @Test
        void loadAggregateForEntity() {
            testFixture.whenApplying(fc -> Fluxzero.loadAggregateFor("map0"))
                    .expectResult(a -> a.get() != null);
        }

        @Test
        void loadForNewEntityReturnsDefault() {
            testFixture.whenApplying(fc -> Fluxzero.loadAggregateFor("unknown", Aggregate.class))
                    .expectResult(a -> a.get() == null);
        }

        @Test
        void loadForNewEntityReturnsDefaultClass() {
            testFixture.whenApplying(fc -> Fluxzero.loadAggregateFor("unknown", MapChild.class))
                    .expectResult(a -> a.get() == null && a.type().equals(MapChild.class));
        }
    }

    @Nested
    class RelationshipTests {

        @Test
        void getRelationships() {
            testFixture.whenApplying(fc -> null)
                    .expectThat(fc -> assertEquals(List.of(Relationship.builder().entityId("map0").aggregateId("test")
                                                                   .aggregateType(Aggregate.class.getName()).build()),
                                                   fc.client().getEventStoreClient().getRelationships("map0")));
        }

        @Test
        void getLastAggregateId() {
            testFixture.whenApplying(fc -> null)
                    .expectThat(fc -> assertEquals(Optional.of("test"),
                                                   fc.aggregateRepository().getLatestAggregateId("map0")));
        }

        @Test
        void getLastAggregateIdForUnknownEntity() {
            testFixture.whenApplying(fc -> null)
                    .expectThat(fc -> assertEquals(Optional.empty(),
                                                   fc.aggregateRepository().getLatestAggregateId("unknown")));
        }

        @Test
        void updateRelationships() {
            Relationship added = Relationship.builder().entityId("added").aggregateId("test")
                    .aggregateType(Aggregate.class.getName()).build();
            testFixture.whenExecuting(
                            fc -> fc.client().getEventStoreClient().updateRelationships(new UpdateRelationships(
                                    Set.of(added), Set.of(), STORED)).get())
                    .expectThat(fc -> assertEquals(List.of(added), fc.client().getEventStoreClient()
                            .getRelationships("added")));
        }

        @Test
        void repairRelationships() {
            Relationship wrong = Relationship.builder().entityId("wrong").aggregateId("test")
                    .aggregateType(Aggregate.class.getName()).build();
            testFixture.given(fc -> fc.client().getEventStoreClient().updateRelationships(new UpdateRelationships(
                                    Set.of(wrong), Set.of(), STORED))
                            .get())
                    .whenExecuting(fc -> fc.aggregateRepository()
                            .repairRelationships(loadAggregate("test", Aggregate.class)).get())
                    .expectThat(fc -> assertEquals(List.of(), fc.client().getEventStoreClient()
                            .getRelationships("wrong")))
                    .expectTrue(fc -> fc.aggregateRepository().getLatestAggregateId("map0").isPresent());
        }

        @Test
        void repairRelationshipsViaId() {
            Relationship wrong = Relationship.builder().entityId("wrong").aggregateId("test")
                    .aggregateType(Aggregate.class.getName()).build();
            testFixture.given(fc -> fc.client().getEventStoreClient().updateRelationships(new UpdateRelationships(
                                    Set.of(wrong), Set.of(), STORED))
                            .get())
                    .whenExecuting(fc -> fc.aggregateRepository().repairRelationships("test").get())
                    .expectThat(fc -> assertEquals(List.of(), fc.client().getEventStoreClient()
                            .getRelationships("wrong")))
                    .expectTrue(fc -> fc.aggregateRepository().getLatestAggregateId("map0").isPresent());
        }
    }

    @Nested
    class RecursiveRootAggregateTests {
        private final TestFixture recursiveFixture = TestFixture.create(new Object() {
            @HandleCommand
            void handle(Object command) {
                loadAggregate("root", Folder.class).apply(command);
            }
        }).givenCommands(new AddRootFolder("root"));

        @Test
        void canAddNestedFolderWithoutReplacingParent() {
            recursiveFixture.whenCommand(new AddFolderToFolder("root", "child"))
                    .expectTrue(fc -> {
                        Folder rootFolder = currentRootFolder();
                        return rootFolder != null
                               && "root".equals(rootFolder.folderId())
                               && rootFolder.folders().size() == 1
                               && "child".equals(rootFolder.folders().getFirst().folderId());
                    });
        }

        @Test
        void canAddNestedFolderWithRoutingKeyWithoutReplacingParent() {
            recursiveFixture.whenCommand(new AddFolderToFolderWithRoutingKey("root", "child"))
                    .expectTrue(fc -> {
                        Folder rootFolder = currentRootFolder();
                        return rootFolder != null
                               && "root".equals(rootFolder.folderId())
                               && rootFolder.folders().size() == 1
                               && "child".equals(rootFolder.folders().getFirst().folderId());
                    });
        }

        @Test
        void canAddFileToNestedFolder() {
            recursiveFixture.givenCommands(new AddFolderToFolder("root", "child"))
                    .whenCommand(new AddFileToFolder("child", "file-1"))
                    .expectTrue(fc -> {
                        Folder child = Fluxzero.<Folder>loadEntity("child").get();
                        return child != null
                               && child.files().size() == 1
                               && "file-1".equals(child.files().getFirst().fileId());
                    });
        }

        @Test
        void canAddFileToNestedFolderWithRoutingKey() {
            recursiveFixture.givenCommands(new AddFolderToFolder("root", "child"))
                    .whenCommand(new AddFileToFolderWithRoutingKey("child", "file-1"))
                    .expectTrue(fc -> {
                        Folder child = Fluxzero.<Folder>loadEntity("child").get();
                        return child != null
                               && child.files().size() == 1
                               && "file-1".equals(child.files().getFirst().fileId());
                    });
        }

        @Test
        void canAddFileToDeepNestedFolder() {
            recursiveFixture.givenCommands(
                            new AddFolderToFolder("root", "projects"),
                            new AddFolderToFolder("projects", "docs"))
                    .whenCommand(new AddFileToFolder("docs", "file-1"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("projects"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "projects"), List.of("docs"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "projects", "docs"), List.of(),
                                                     List.of("file-1")));
        }

        @Test
        void addFileToMissingFolderStillFails() {
            recursiveFixture.whenCommand(new AddFileToFolder("missing", "file-1"))
                    .expectExceptionalResult(Entity.NOT_FOUND_EXCEPTION);
        }

        @Test
        void addFileToMissingFolderWithRoutingKeyStillFails() {
            recursiveFixture.whenCommand(new AddFileToFolderWithRoutingKey("missing", "file-1"))
                    .expectExceptionalResult(Entity.NOT_FOUND_EXCEPTION);
        }

        @Test
        void canDeleteNestedFolderViaSimpleApply() {
            recursiveFixture.givenCommands(new AddFolderToFolder("root", "child"))
                    .whenCommand(new DeleteFolder("child"))
                    .expectFalse(fc -> loadEntity("child").isPresent())
                    .expectTrue(fc -> {
                        Folder rootFolder = currentRootFolder();
                        return rootFolder != null && rootFolder.folders().isEmpty();
                    });
        }

        @Test
        void canDeleteNestedFolderWithRoutingKeyViaSimpleApply() {
            recursiveFixture.givenCommands(new AddFolderToFolder("root", "child"))
                    .whenCommand(new DeleteFolderWithRoutingKey("child"))
                    .expectFalse(fc -> loadEntity("child").isPresent())
                    .expectTrue(fc -> {
                        Folder rootFolder = currentRootFolder();
                        return rootFolder != null && rootFolder.folders().isEmpty();
                    });
        }

        @Test
        void canUpdateCurrentFolderWithoutTriggeringMissingSelfChildCheck() {
            recursiveFixture.whenCommand(new TouchFolder("root"))
                    .expectNoErrors();
        }

        @Test
        void canUpdateCurrentFolderWithRoutingKeyWithoutTriggeringMissingSelfChildCheck() {
            recursiveFixture.whenCommand(new TouchFolderWithRoutingKey("root"))
                    .expectNoErrors();
        }

        @Test
        void deepFoldersAndFilesStayInCorrectBranches() {
            recursiveFixture.givenCommands(
                            new AddFolderToFolder("root", "projects"),
                            new AddFolderToFolder("projects", "docs"),
                            new AddFolderToFolder("docs", "archive"),
                            new AddFolderToFolder("root", "media"),
                            new AddFileToFolder("root", "file-root"),
                            new AddFileToFolder("projects", "file-projects"),
                            new AddFileToFolder("docs", "file-docs"),
                            new AddFileToFolder("archive", "file-archive"))
                    .whenCommand(new AddFileToFolder("media", "file-media"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("projects", "media"),
                                                  List.of("file-root"))
                                      && folderState(currentRootFolder(), List.of("root", "projects"), List.of("docs"),
                                                     List.of("file-projects"))
                                      && folderState(currentRootFolder(), List.of("root", "projects", "docs"),
                                                     List.of("archive"), List.of("file-docs"))
                                      && folderState(currentRootFolder(), List.of("root", "projects", "docs", "archive"),
                                                     List.of(), List.of("file-archive"))
                                      && folderState(currentRootFolder(), List.of("root", "media"), List.of(),
                                                     List.of("file-media")));
        }

        @Test
        void siblingBranchesKeepTheirOwnFiles() {
            recursiveFixture.givenCommands(
                            new AddFolderToFolder("root", "left"),
                            new AddFolderToFolder("root", "right"),
                            new AddFolderToFolder("left", "left-nested"),
                            new AddFileToFolder("left", "left-file"),
                            new AddFileToFolder("left-nested", "left-nested-file"))
                    .whenCommand(new AddFileToFolder("right", "right-file"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("left", "right"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "left"), List.of("left-nested"),
                                                     List.of("left-file"))
                                      && folderState(currentRootFolder(), List.of("root", "left", "left-nested"),
                                                     List.of(), List.of("left-nested-file"))
                                      && folderState(currentRootFolder(), List.of("root", "right"), List.of(),
                                                     List.of("right-file")));
        }

        @Test
        void deletingBranchRemovesDescendantsButKeepsSiblings() {
            recursiveFixture.givenCommands(
                            new AddFolderToFolder("root", "keep"),
                            new AddFolderToFolder("root", "delete"),
                            new AddFolderToFolder("delete", "delete-child"),
                            new AddFileToFolder("keep", "keep-file"),
                            new AddFileToFolder("delete", "delete-file"))
                    .whenCommand(new AddFileToFolder("delete-child", "delete-child-file"))
                    .expectTrue(fc -> loadEntity("keep").isPresent()
                                      && loadEntity("delete").isPresent()
                                      && loadEntity("delete-child").isPresent()
                                      && loadEntity("keep-file").isPresent()
                                      && loadEntity("delete-file").isPresent()
                                      && loadEntity("delete-child-file").isPresent())
                    .andThen()
                    .whenCommand(new DeleteFolder("delete"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("keep"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "keep"), List.of(),
                                                     List.of("keep-file"))
                                      && !loadEntity("delete").isPresent()
                                      && !loadEntity("delete-child").isPresent()
                                      && !loadEntity("delete-file").isPresent()
                                      && !loadEntity("delete-child-file").isPresent());
        }

        @Test
        void deletingDeepLeafFolderKeepsAncestorAndSiblingContent() {
            recursiveFixture.givenCommands(
                            new AddFolderToFolder("root", "projects"),
                            new AddFolderToFolder("projects", "docs"),
                            new AddFolderToFolder("projects", "images"),
                            new AddFileToFolder("projects", "projects-file"),
                            new AddFileToFolder("images", "images-file"))
                    .whenCommand(new AddFileToFolder("docs", "docs-file"))
                    .andThen()
                    .whenCommand(new DeleteFolder("docs"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("projects"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "projects"), List.of("images"),
                                                     List.of("projects-file"))
                                      && folderState(currentRootFolder(), List.of("root", "projects", "images"),
                                                     List.of(), List.of("images-file"))
                                      && !loadEntity("docs").isPresent()
                                      && !loadEntity("docs-file").isPresent());
        }

        @Test
        void canDeleteDeepFileWithoutTouchingFoldersOrSiblingFiles() {
            recursiveFixture.givenCommands(
                            new AddFolderToFolder("root", "projects"),
                            new AddFolderToFolder("projects", "docs"),
                            new AddFileToFolder("projects", "projects-file"))
                    .whenCommand(new AddFileToFolder("docs", "docs-file"))
                    .andThen()
                    .whenCommand(new DeleteFile("docs-file"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("projects"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "projects"), List.of("docs"),
                                                     List.of("projects-file"))
                                      && folderState(currentRootFolder(), List.of("root", "projects", "docs"),
                                                     List.of(), List.of())
                                      && !loadEntity("docs-file").isPresent()
                                      && loadEntity("projects-file").isPresent()
                                      && loadEntity("docs").isPresent());
        }

        @Test
        void canDeleteDeepFileWithRoutingKeyWithoutTouchingFoldersOrSiblingFiles() {
            recursiveFixture.givenCommands(
                            new AddFolderToFolder("root", "projects"),
                            new AddFolderToFolder("projects", "docs"),
                            new AddFileToFolder("projects", "projects-file"))
                    .whenCommand(new AddFileToFolder("docs", "docs-file"))
                    .andThen()
                    .whenCommand(new DeleteFileWithRoutingKey("docs-file"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("projects"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "projects"), List.of("docs"),
                                                     List.of("projects-file"))
                                      && folderState(currentRootFolder(), List.of("root", "projects", "docs"),
                                                     List.of(), List.of())
                                      && !loadEntity("docs-file").isPresent()
                                      && loadEntity("projects-file").isPresent()
                                      && loadEntity("docs").isPresent());
        }

        @Test
        void deletingMissingFileFails() {
            recursiveFixture.givenCommands(new AddFolderToFolder("root", "projects"))
                    .whenCommand(new DeleteFile("missing-file"))
                    .expectExceptionalResult(Entity.NOT_FOUND_EXCEPTION);
        }

        private Folder currentRootFolder() {
            return loadAggregate("root", Folder.class).get();
        }
    }

    @Nested
    class RecursiveWrappedAggregateTests {
        private final TestFixture wrappedFixture = TestFixture.create(new Object() {
            @HandleCommand
            void handle(Object command) {
                loadAggregate("tree", FolderAggregate.class).apply(command);
            }
        }).given(fc -> loadAggregate("tree", FolderAggregate.class)
                .update(s -> new FolderAggregate("tree", null)));

        @Test
        void wrappedAggregateCanCreateRootFolder() {
            wrappedFixture.whenCommand(new AddRootFolder("root"))
                    .expectTrue(fc -> {
                        Folder rootFolder = currentRootFolder();
                        return rootFolder != null
                               && "root".equals(rootFolder.folderId())
                               && rootFolder.folders().isEmpty()
                               && rootFolder.files().isEmpty();
                    });
        }

        @Test
        void wrappedAggregateCanAddDeepNestedFile() {
            wrappedFixture.givenCommands(
                            new AddRootFolder("root"),
                            new AddFolderToFolder("root", "projects"),
                            new AddFolderToFolder("projects", "docs"))
                    .whenCommand(new AddFileToFolder("docs", "file-1"))
                    .expectTrue(fc -> folderState(currentRootFolder(), List.of("root"), List.of("projects"), List.of())
                                      && folderState(currentRootFolder(), List.of("root", "projects"), List.of("docs"),
                                                     List.of())
                                      && folderState(currentRootFolder(), List.of("root", "projects", "docs"), List.of(),
                                                     List.of("file-1")));
        }

        @Test
        void wrappedAggregateMissingNestedTargetStillFails() {
            wrappedFixture.givenCommands(new AddRootFolder("root"))
                    .whenCommand(new AddFileToFolder("missing", "file-1"))
                    .expectExceptionalResult(Entity.NOT_FOUND_EXCEPTION);
        }

        private Folder currentRootFolder() {
            FolderAggregate aggregate = loadAggregate("tree", FolderAggregate.class).get();
            return aggregate == null ? null : aggregate.rootFolder();
        }
    }

    @Value
    @Builder(toBuilder = true)
    public static class Aggregate {

        @Default
        @EntityId
        String id = "test";

        @Member
        @Default
        Child singleton = Child.builder().build();

        @Member(idProperty = "customId")
        @Default
        Child singletonCustomPath = Child.builder().build();

        @Member
        MissingChild missingChild;

        @Member
        @Default
        List<ListChild> list = List.of(
                ListChild.builder().listChildId("list0").build(),
                ListChild.builder().listChildId("list1").build(), ListChild.builder().listChildId(null).build());

        @Member
        List<NullListChild> nullList;

        @Member
        @Default
        Map<Key, MapChild> map = Map.of(
                new Key("map0"), MapChild.builder().mapChildId(new Key("map0")).build(),
                new Key("map1"), MapChild.builder().build());

        @Member
        @Default
        @With
        ChildWithChild childWithGrandChild = ChildWithChild.builder().build();

        @Alias
        String clientReference;

        @Alias(prefix = "other-")
        @Singular
        List<String> otherReferences;
    }

    @Member
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    private @interface NestedMember {
    }

    @Value
    @Builder(toBuilder = true)
    static class MetaAggregate {
        @Default
        @EntityId
        String id = "meta-test";

        @NestedMember
        @Default
        MetaChild child = MetaChild.builder().build();
    }

    @Value
    @Builder(toBuilder = true)
    static class MetaChild {
        @Default
        @EntityId
        String metaChildId = "meta-child";
    }

    @Value
    @AllArgsConstructor
    @Builder(toBuilder = true)
    static class Child implements Updatable {
        @EntityId
        @Default
        String childId = "id";
        @Default
        String customId = "otherId";
        @Member
        GrandChild grandChild;
        @With
        Object data;

        @AssertLegal
        void assertLegal(CommandWithRoutingKeyHandledByEntity child) {
            throw new IllegalCommandException("Child already exists");
        }
    }

    @Builder(toBuilder = true)
    record ListChild(@EntityId String listChildId, @With Object data) implements Updatable {
    }

    record NullListChild(@EntityId String nullListChildId) {
    }

    @Builder
    record MapChild(@EntityId Key mapChildId, @With Object data) implements Updatable {
    }

    @Builder
    public record MissingChild(@EntityId MissingChildId missingChildId, @Member @With MissingGrandChild grandChild) {
    }

    static class MissingChildId extends Id<MissingChild> {
        public MissingChildId(String functionalId) {
            super(functionalId);
        }
    }

    @Builder
    record MissingGrandChild(@EntityId String missingGrandChildId) {
    }

    record RecordAggregate(@EntityId String id, @Member RecordChild child) {
        RecordAggregate withChild(RecordChild child) {
            return new RecordAggregate(id, child);
        }
    }

    record RecordChild(@EntityId String recordChildId, @Member RecordGrandChild grandChild) {
        RecordChild withGrandChild(RecordGrandChild grandChild) {
            return new RecordChild(recordChildId, grandChild);
        }
    }

    record RecordGrandChild(@EntityId String recordGrandChildId) {
    }

    @Value
    @Builder(toBuilder = true)
    static class ChildWithChild {
        @EntityId
        @Default
        String withChildId = "withChild";

        @Member
        @Default
        @With
        GrandChild grandChild = new GrandChild("grandChild", new GrandChildAlias());
    }

    record GrandChild(@EntityId String grandChildId, @Alias GrandChildAlias alias) {
    }

    record FolderAggregate(@EntityId String id, @Member Folder rootFolder) {
        FolderAggregate withRootFolder(Folder rootFolder) {
            return new FolderAggregate(id, rootFolder);
        }
    }

    @io.fluxzero.sdk.modeling.Aggregate
    record Folder(@EntityId String folderId, @Member List<Folder> folders, @Member List<File> files) {
        Folder withFolders(List<Folder> folders) {
            return new Folder(folderId, folders, files);
        }

        Folder withFiles(List<File> files) {
            return new Folder(folderId, folders, files);
        }
    }

    record File(@EntityId String fileId) {
    }

    record AddRootFolder(String folderId) {
        @Apply
        Folder apply() {
            return new Folder(folderId, List.of(), List.of());
        }
    }

    record AddFolderToFolder(String folderId, String childFolderId) {
        @Apply
        Folder apply(Folder parentFolder) {
            return new Folder(childFolderId, List.of(), List.of());
        }
    }

    record AddFolderToFolderWithRoutingKey(@RoutingKey String targetFolderId, String childFolderId) {
        @Apply
        Folder apply(Folder parentFolder) {
            return new Folder(childFolderId, List.of(), List.of());
        }
    }

    record AddFileToFolder(String folderId, String fileId) {
        @Apply
        File apply(Folder folder) {
            return new File(fileId);
        }
    }

    record AddFileToFolderWithRoutingKey(@RoutingKey String targetFolderId, String fileId) {
        @Apply
        File apply(Folder folder) {
            return new File(fileId);
        }
    }

    record DeleteFolder(String folderId) {
        @Apply
        Folder apply(Folder folder) {
            return null;
        }
    }

    record DeleteFolderWithRoutingKey(@RoutingKey String targetFolderId) {
        @Apply
        Folder apply(Folder folder) {
            return null;
        }
    }

    record TouchFolder(String folderId) {
        @Apply
        Folder apply(Folder folder) {
            return new Folder(folder.folderId(), folder.folders(), folder.files());
        }
    }

    record TouchFolderWithRoutingKey(@RoutingKey String targetFolderId) {
        @Apply
        Folder apply(Folder folder) {
            return new Folder(folder.folderId(), folder.folders(), folder.files());
        }
    }

    record DeleteFile(String fileId) {
        @Apply
        File apply(File file) {
            return null;
        }
    }

    record DeleteFileWithRoutingKey(@RoutingKey String targetFileId) {
        @Apply
        File apply(File file) {
            return null;
        }
    }

    static class GrandChildAlias extends Id<GrandChild> {
        protected GrandChildAlias() {
            super("anyGrandChild", GrandChild.class);
        }
    }

    record CommandWithRoutingKey(@RoutingKey String target) {
        @AssertLegal
        void assertLegal(Object child) {
            if (child != null) {
                throw new IllegalCommandException("Child is unauthorized");
            }
        }
    }

    record CommandWithRoutingKeyHandledByEntity(@RoutingKey String target) {
    }

    record CommandWithoutRoutingKey(String customId) {
        @AssertLegal
        void assertLegal(Child child) {
            throw new IllegalCommandException("Child should not have been targeted");
        }
    }

    record CommandTargetingGrandchildButFailingOnParent(@RoutingKey String id) {
        @AssertLegal
        void assertLegal(ChildWithChild child) {
            if (child != null) {
                throw new IllegalCommandException("Child is unauthorized");
            }
        }
    }

    record CommandWithWrongProperty(String randomProperty) {
        @AssertLegal
        void assertLegal(Child child) {
            if (child != null) {
                throw new IllegalCommandException("Child is unauthorized");
            }
        }
    }

    record UpdateCommandThatFailsIfChildDoesNotExist(String missingChildId) {
        @AssertLegal
        void assertLegal(@Nullable MissingChild child, @NonNull Aggregate aggregate) {
            if (child == null) {
                throw new IllegalCommandException("Expected a child");
            }
        }
    }

    record UpdateRecordGrandChild(@RoutingKey String recordGrandChildId, String newGrandChildId) {
        @Apply
        RecordGrandChild apply(RecordGrandChild grandChild) {
            return new RecordGrandChild(newGrandChildId);
        }
    }


    record Key(String key) {
        @Override
        public String toString() {
            return key;
        }
    }

    interface Updatable {
        Object withData(Object data);
    }
}
