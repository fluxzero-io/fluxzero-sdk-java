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

import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitConflict;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ModelCommitConflictExceptionTest {
    @Test
    void storageConflictRetainsItsResultAndBatchReevaluationContract() {
        var result = CommitModelsResult.conflict(1, "command", List.of(new ModelCommitConflict("model", 2L, 3L)), false);
        var failure = new ModelCommitConflictException(result);
        assertSame(result, failure.getResult());
        assertNull(failure.getReadConflict());
        assertEquals("Model commit command conflicted with model", failure.getMessage());
        assertTrue(ModelBatchScope.canReevaluate(failure));
    }

    @Test
    void preparationConflictCarriesReadEvidenceWithoutInventingStorageEvidence() {
        var evidence = new ModelCommitConflictException.ReadConflict("command", "model", 1L);
        var cause = new IllegalStateException("lost document");
        var failure = new ModelCommitConflictException(evidence, cause);
        assertSame(evidence, failure.getReadConflict());
        assertSame(cause, failure.getCause());
        assertNull(failure.getResult());
        assertFalse(ModelBatchScope.canReevaluate(new java.util.concurrent.CompletionException(failure)));
    }

    @Test
    void unrelatedPreparationFailuresAreNotReclassified() {
        var repository = mock(DefaultModelRepository.class, CALLS_REAL_METHODS);
        for (var failure : List.of(new IllegalArgumentException("application failure"),
                                  new io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException("corrupt data"))) {
            assertSame(failure, repository.preparationFailure("command", 1L, failure));
        }
    }

    @Test
    void onlyTypedRemoteEvidenceAtTheSameBoundaryBecomesAConflict() {
        var repository = mock(DefaultModelRepository.class, CALLS_REAL_METHODS);
        var cause = new io.fluxzero.sdk.common.exception.ServiceException("any text",
                new io.fluxzero.common.api.ErrorResult.ModelHistoryUnavailable("document", 12L));
        for (var failure : List.of(cause, new java.util.concurrent.ExecutionException(cause),
                                   new java.lang.reflect.UndeclaredThrowableException(
                                           new java.util.concurrent.ExecutionException(cause)))) {
            var conflict = assertInstanceOf(ModelCommitConflictException.class,
                    repository.preparationFailure("commit", 12L, failure));
            assertEquals(new ModelCommitConflictException.ReadConflict("commit", "document", 12L),
                         conflict.getReadConflict());
            assertSame(cause, conflict.getCause());
            assertSame(failure, repository.preparationFailure("commit", 11L, failure));
        }
        var legacy = new io.fluxzero.sdk.common.exception.ServiceException(
                "Model history is incomplete at state index 12 for model document");
        assertSame(legacy, repository.preparationFailure("commit", 12L, legacy), "Never parse failure text");
    }
}
