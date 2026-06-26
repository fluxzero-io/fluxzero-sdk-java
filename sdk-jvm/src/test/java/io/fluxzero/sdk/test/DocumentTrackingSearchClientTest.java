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

package io.fluxzero.sdk.test;

import io.fluxzero.common.api.search.DocumentUpdate;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.api.search.BulkUpdate.Type.index;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentTrackingSearchClientTest {

    @Test
    void cancelsMonitoredDocumentsWhenIndexThrowsSynchronously() {
        SearchClient delegate = mock(SearchClient.class);
        TestFixture.GivenWhenThenInterceptor interceptor = mock(TestFixture.GivenWhenThenInterceptor.class);
        DocumentTrackingSearchClient client = new DocumentTrackingSearchClient(delegate, interceptor);
        SerializedDocument document = document();
        RuntimeException failure = new RuntimeException("boom");
        when(delegate.index(eq(List.of(document)), eq(STORED), eq(false))).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                                               () -> client.index(List.of(document), STORED, false));

        assertSame(failure, thrown);
        verify(interceptor).monitorDocumentDispatch(document);
        verify(interceptor).cancelDocumentDispatch(List.of(document));
    }

    @Test
    void cancelsMonitoredDocumentsWhenBulkUpdateThrowsSynchronously() {
        SearchClient delegate = mock(SearchClient.class);
        TestFixture.GivenWhenThenInterceptor interceptor = mock(TestFixture.GivenWhenThenInterceptor.class);
        DocumentTrackingSearchClient client = new DocumentTrackingSearchClient(delegate, interceptor);
        SerializedDocument document = document();
        List<DocumentUpdate> updates = List.of(DocumentUpdate.builder()
                                                       .type(index).id("id").collection("collection")
                                                       .object(document).build());
        RuntimeException failure = new RuntimeException("boom");
        when(delegate.bulkUpdate(eq(updates), eq(STORED))).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                                               () -> client.bulkUpdate(updates, STORED));

        assertSame(failure, thrown);
        verify(interceptor).monitorDocumentDispatch(document);
        verify(interceptor).cancelDocumentDispatch(List.of(document));
    }

    private static SerializedDocument document() {
        SerializedDocument document = mock(SerializedDocument.class);
        when(document.getId()).thenReturn("id");
        when(document.getCollection()).thenReturn("collection");
        return document;
    }
}
