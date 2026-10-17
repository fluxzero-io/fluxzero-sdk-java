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

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AliasMutationTargetTest {
    private Fluxzero app() { return app(LocalClient.newInstance()); }

    private Fluxzero app(LocalClient client) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of("fluxzero.model.aliasTargets", "true")))
                .build(client);
    }

    @Test void aliasUpdatesCanonicalOwnerBehindPrimaryShadow() {
        try (var app = app()) {
            commit(app, new Put("shared", "primary-email", "primary-user", 1));
            commit(app, new Put("owner", "shared", "owner-user", 2));
            commit(app, new RenameByEmail("shared", 3));
            assertEquals(3, read(app, "owner").number());
            assertEquals(1, read(app, "shared").number());
        }
    }

    @Test void primaryCreationCanShadowAlias() {
        try (var app = app()) {
            commit(app, new Put("owner", "shared", "owner-user", 2));
            commit(app, new Put("shared", "primary-email", "primary-user", 1));
            assertEquals(1, read(app, "shared").number());
            assertEquals(2, read(app, "owner").number());
        }
    }

    @Test void absentAliasCreatesFromMatchingApplyResult() {
        try (var app = app()) {
            commit(app, new CreateByEmail("new-email", "new-owner"));
            assertEquals(new Account("new-owner", "new-email", "new-user", 1), read(app, "new-owner"));
        }
    }

    @Test void mixedAliasMatchesRequireFinalResultToMatchEveryProperty() {
        try (var app = app()) {
            commit(app, new Put("owner", "known", "old-user", 1));
            commit(app, new ChangeBoth("known", "new-user"));
            assertEquals("new-user", read(app, "owner").username());
        }
    }

    @Test void equalValueOnOtherAliasPropertyIsRejected() {
        try (var app = app()) {
            commit(app, new Put("owner", "email", "username", 1));
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> commit(app, new RenameByEmail("username", 3)));
            assertTrue(failure.getCause().getMessage().contains("different property"), failure::toString);
            assertEquals(1, read(app, "owner").number());
        }
    }

    @Test void replayKeepsCanonicalTargetAfterAliasReassignment() {
        try (var app = app()) {
            commit(app, new Put("first", "shared", "first-user", 1));
            commit(app, new RenameByEmail("shared", 7));
            commit(app, new ReplaceEmail("shared", "released"));
            commit(app, new Put("second", "shared", "second-user", 2));
            assertEquals(new Account("first", "released", "first-user", 7), read(app, "first"));
            assertEquals(2, read(app, "second").number());
        }
    }

    @Test void fullyMatchedAliasMayBeRemovedOrDeleteItsOwner() {
        try (var app = app()) {
            commit(app, new Put("owner", "selected", "owner-user", 1));
            commit(app, new ReplaceEmail("selected", "replacement"));
            assertEquals("replacement", read(app, "owner").email());
            commit(app, new DeleteByEmail("replacement"));
            assertNull(read(app, "owner"));
        }
    }

    @Test void differentOwnersAreAmbiguousAndDoNotWrite() {
        try (var app = app()) {
            commit(app, new Put("one", "one-email", "one-user", 1));
            commit(app, new Put("two", "two-email", "two-user", 2));
            reject(app, new ChangeBoth("one-email", "two-user"), "different Models");
            assertEquals("one-user", read(app, "one").username());
            assertEquals("two-email", read(app, "two").email());
        }
    }

    @Test void creationMustMatchSelectorsAndCannotReplaceAnExistingPrimary() {
        try (var app = app()) {
            reject(app, new InvalidCreate("requested", "wrong"), "must satisfy alias selectors");
            assertNull(read(app, "wrong"));
            commit(app, new Put("occupied", "old", "old-user", 4));
            reject(app, new CreateByEmail("new", "occupied"), "existing primary model");
            assertEquals(4, read(app, "occupied").number());
        }
    }

    @Test void mixedMatchesMustBeSatisfiedInTheResult() {
        try (var app = app()) {
            commit(app, new Put("one", "known", "old", 1));
            reject(app, new IgnoreMissing("known", "missing"), "must satisfy alias selectors");
            assertEquals("old", read(app, "one").username());
        }
    }

    @Test void nullPrimaryNeverFallsBackToMatchingAlias() {
        try (var app = app()) {
            commit(app, new Put("owner", "selected", "user", 1));
            reject(app, new Put(null, "selected", "user", 2), "null");
            assertEquals(1, read(app, "owner").number());
        }
    }

    @Test void collectionSelectorsConjoinRatherThanFanOut() {
        try (var app = app()) {
            commit(app, new PutLabels("one", java.util.List.of("a", "b"), 1));
            commit(app, new LabelUpdate(java.util.List.of("a", "b"), 2));
            int number = app.apply(fc -> Fluxzero.loadModel("one", Labels.class).get().number());
            assertEquals(2, number);
            commit(app, new PutLabels("two", java.util.List.of("c"), 3));
            reject(app, new LabelUpdate(java.util.List.of("a", "c"), 4), "different Models");
        }
    }

    @Test void retryRepeatsAssertionsAndSelectsTheNewOwner() {
        var client = new GateClient();
        try (var app = app(client)) {
            commit(app, new Put("old-owner", "selected", "old-user", 1));
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            client.beforeCommit = request -> {
                if (!request.getAliasObservations().isEmpty() && once.compareAndSet(false, true)) {
                    assertEquals("old-owner", request.getAliasObservations().getFirst().expectedOwner());
                    assertTrue(request.getReadModelIds().contains("old-owner"));
                    commit(app, new Put("old-owner", "released", "old-user", 1));
                    commit(app, new Put("new-owner", "selected", "new-user", 2));
                }
            };
            checks.set(0);
            commit(app, new CheckedRename("selected", 9));
            assertEquals(2, checks.get());
            assertEquals(1, read(app, "old-owner").number());
            assertEquals(9, read(app, "new-owner").number());
        }
    }

    @Test void acceptReportsAChangedSelectionAsFailureWithoutApplyingTheOldDecision() {
        var client = new GateClient();
        try (var app = app(client)) {
            commit(app, new Put("old-owner", "selected", "old-user", 1));
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            client.beforeCommit = request -> {
                if (!request.getAliasObservations().isEmpty() && once.compareAndSet(false, true)) {
                    commit(app, new Put("old-owner", "released", "old-user", 1));
                    commit(app, new Put("new-owner", "selected", "new-user", 2));
                }
            };
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> commit(app, new AcceptRename("selected", 9)));
            assertInstanceOf(ModelCommitConflictException.class, failure.getCause());
            assertEquals(1, read(app, "old-owner").number());
            assertEquals(2, read(app, "new-owner").number());
        }
    }

    @Test void replayRetainsAliasSelectedDependenciesAfterReassignment() {
        try (var app = app()) {
            commit(app, new Put("first", "selected", "first-user", 7));
            commit(app, new RecordNumber("receipt", "selected"));
            commit(app, new ReplaceEmail("selected", "released"));
            commit(app, new Put("second", "selected", "second-user", 99));
            Receipt receipt = app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get());
            assertEquals(7, receipt.number());
        }
    }

    @Test void emptyAliasPropertiesDoNotOverrideEffectiveSelectors() {
        try (var app = app()) {
            commit(app, new Put("owner", "known", "user", 1));
            commit(app, new IgnoreMissing("known", null));
            commit(app, new IgnoreMissing("known", ""));
            reject(app, new IgnoreMissing(null, ""), "At least one non-empty alias selector");
            commit(app, new RecordWithOptionalAlias("receipt", "known", null));
            commit(app, new ReplaceEmail("known", "released"));
            Receipt receipt = app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get());
            assertEquals(1, receipt.number());
        }
    }

    @Test void pendingBatchAliasesAreVisibleIncludingReassignment() {
        try (var app = app()) {
            batch(app, new Put("first", "shared", "first-user", 1),
                    new RenameByEmail("shared", 2),
                    new ReplaceEmail("shared", "released"),
                    new CreateByEmail("shared", "second"),
                    new RenameByEmail("shared", 3));
            assertEquals(new Account("first", "released", "first-user", 2), read(app, "first"));
            assertEquals(3, read(app, "second").number());
        }
    }

    @Test void pendingBatchAliasStillRequiresTheCorrectProperty() {
        try (var app = app()) {
            var failure = assertThrows(Exception.class, () -> batch(app,
                    new Put("owner", "email", "username", 1), new RenameByEmail("username", 2)));
            assertTrue(io.fluxzero.common.ObjectUtils.unwrapException(failure).getMessage()
                    .contains("different property"), failure::toString);
            assertEquals(1, read(app, "owner").number());
        }
    }

    @Test void pendingRemovalMakesAliasAMissingSelectorInAMixedMatch() {
        try (var app = app()) {
            commit(app, new Put("owner", "known", "user", 1));
            batch(app, new ReplaceEmail("known", "released"), new ChangeBoth("known", "user"));
            assertEquals("known", read(app, "owner").email());
        }
    }

    @Test void splitStepsObserveAliasCreationAndEnforcePropertyQualification() {
        try (var app = app()) {
            commit(app, new Split(java.util.List.of(new Put("owner", "email", "user", 1),
                    new RenameByEmail("email", 2), new ReplaceEmail("email", "released"),
                    new ChangeBoth("email", "user"))));
            assertEquals(new Account("owner", "email", "user", 2), read(app, "owner"));
            reject(app, new Split(java.util.List.of(new Put("other", "other-email", "other-user", 3),
                    new RenameByEmail("other-user", 4))), "different property");
            assertNull(read(app, "other"), "An invalid selector rolls back the entire split commit");
        }
    }

    @Test void aliasCreationCannotOverwriteAPrimaryCreatedInTheSameCommit() {
        try (var app = app()) {
            reject(app, new Split(java.util.List.of(new Put("owner", "old", "user", 1),
                    new CreateByEmail("new", "owner"))), "existing primary model");
            assertNull(read(app, "owner"));
        }
    }

    @Test void aliasCreationCanRecreateAPrimaryDeletedEarlierInTheCommit() {
        try (var app = app()) {
            commit(app, new Put("owner", "old", "user", 1));
            commit(app, new Split(java.util.List.of(new DeleteByEmail("old"), new CreateByEmail("new", "owner"))));
            assertEquals(new Account("owner", "new", "new-user", 1), read(app, "owner"));
        }
    }

    record Split(java.util.List<Object> commands) {
        @io.fluxzero.sdk.persisting.eventsourcing.InterceptApply java.util.List<Object> intercept() { return commands; }
    }

    private static void batch(Fluxzero app, Object... commands) {
        app.execute(fc -> {
            var serializer = new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer();
            try (var handlers = new ModelCommitHandlerRegistry(
                    (io.fluxzero.sdk.persisting.repository.DefaultModelRepository) fc.modelRepository(),
                    fc.client().getEventStoreClient(), serializer, serializer, serializer,
                    io.fluxzero.sdk.publishing.DispatchInterceptor.noOp,
                    io.fluxzero.sdk.publishing.DispatchInterceptor.noOp, "test", java.util.List.of(),
                    io.fluxzero.sdk.tracking.handling.HandlerDecorator.noOp,
                    io.fluxzero.common.api.modeling.ModelConflictPolicy.RETRY,
                    ModelConflictResolver.fail(), 3, AutomaticModelHandling.ENABLED, GraphProjectionCompletion.ASYNC)) {
            handlers.setSelfHandlerFilter(io.fluxzero.common.handling.HandlerFilter.ALWAYS_HANDLE);
            var messages = java.util.Arrays.stream(commands).map(command ->
                    new io.fluxzero.sdk.common.serialization.DeserializingMessage(new Message(command),
                            io.fluxzero.common.MessageType.COMMAND, fc.serializer())).toList();
            io.fluxzero.sdk.common.serialization.DeserializingMessage.forEachInBatch(messages, current ->
                    handlers.handle(current).orElseThrow());
            }
        });
    }

    record RecordWithOptionalAlias(String receiptId, String email, String username) {
        @Apply Receipt apply(Account account) { return new Receipt(receiptId, account.number()); }
    }

    @Test void documentPrimaryCanShadowAnEventSourcedAliasWithoutInstability() {
        try (var app = app()) {
            commit(app, new Put("owner", "doc-one", "user", 1));
            commit(app, new PutDocument("one", "document-alias", 2));
            Document document = app.apply(fc -> Fluxzero.loadModel("one", Document.class).get());
            assertEquals(new Document("one", "document-alias", 2), document);
            assertEquals(1, read(app, "owner").number());
        }
    }

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT, eventPublication = EventPublication.NEVER)
    record Document(@EntityId(prefix = "doc-") String documentId, @Alias String alias, int number) {}
    record PutDocument(String documentId, String alias, int number) {
        @Apply Document apply(@Nullable Document before) { return new Document(documentId, alias, number); }
    }

    private static void reject(Fluxzero app, Object command, String message) {
        var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> commit(app, command));
        assertTrue(failure.getCause().getMessage().contains(message), failure::toString);
    }

    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(Message.asMessage(command)).join());
    }
    private static Account read(Fluxzero app, String id) {
        return app.apply(fc -> Fluxzero.loadModel(id, Account.class).get());
    }
    @Model(searchable = false, cached = false, commitPolicy = ModelCommitPolicy.ASYNC_AFTER_BATCH) record Account(@EntityId String accountId, @Alias String email,
                                             @Alias String username, int number) {}
    record Put(String accountId, String email, String username, int number) {
        @Apply Account apply(@Nullable Account before) { return new Account(accountId, email, username, number); }
    }
    record RenameByEmail(String email, int number) {
        @Apply Account apply(Account before) {
            return new Account(before.accountId(), before.email(), before.username(), number);
        }
    }
    record CreateByEmail(String email, String generatedId) {
        @Apply Account apply(@Nullable Account before) {
            assertNull(before);
            return new Account(generatedId, email, "new-user", 1);
        }
    }
    record ChangeBoth(String email, String username) {
        @Apply Account apply(Account before) {
            return new Account(before.accountId(), email, username, before.number());
        }
    }
    record ReplaceEmail(String email, String replacement) {
        @Apply Account apply(Account before) {
            return new Account(before.accountId(), replacement, before.username(), before.number());
        }
    }
    record DeleteByEmail(String email) { @Apply Account apply(Account before) { return null; } }
    record InvalidCreate(String email, String generatedId) {
        @Apply Account apply() { return new Account(generatedId, "different", "user", 1); }
    }
    record IgnoreMissing(String email, String username) { @Apply Account apply(Account before) { return before; } }
    @Model(searchable = false, cached = false)
    record Labels(@EntityId String labelsId, @Alias(prefix = "label-") java.util.List<String> labels, int number) {}
    record PutLabels(String labelsId, java.util.List<String> labels, int number) {
        @Apply Labels apply() { return new Labels(labelsId, labels, number); }
    }
    record LabelUpdate(java.util.List<String> labels, int number) {
        @Apply Labels apply(Labels before) { return new Labels(before.labelsId(), before.labels(), number); }
    }

    static final java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
    record CheckedRename(String email, int number) {
        @AssertLegal void check(Account account) { checks.incrementAndGet(); }
        @Apply(conflictPolicy = io.fluxzero.common.api.modeling.ModelConflictPolicy.RETRY)
        Account apply(Account before) { return new Account(before.accountId(), email, before.username(), number); }
    }
    record AcceptRename(String email, int number) {
        @Apply(conflictPolicy = io.fluxzero.common.api.modeling.ModelConflictPolicy.ACCEPT)
        Account apply(Account before) { return new Account(before.accountId(), email, before.username(), number); }
    }
    private static final class GateClient extends LocalClient {
        java.util.function.Consumer<io.fluxzero.common.api.modeling.CommitModels> beforeCommit = ignored -> {};
        GateClient() { super(null); }
        @Override protected io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient createEventStoreClient() {
            var delegate = super.createEventStoreClient();
            var type = io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient.class;
            return (io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient)
                    java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                            (proxy, method, arguments) -> {
                                if (method.getName().equals("commitModels")) {
                                    beforeCommit.accept((io.fluxzero.common.api.modeling.CommitModels) arguments[0]);
                                }
                                try { return method.invoke(delegate, arguments); }
                                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                            });
        }
    }

    @Model(searchable = false, cached = false) record Receipt(@EntityId String receiptId, int number) {}
    record RecordNumber(String receiptId, String email) {
        @Apply Receipt apply(Account account) { return new Receipt(receiptId, account.number()); }
    }

}
