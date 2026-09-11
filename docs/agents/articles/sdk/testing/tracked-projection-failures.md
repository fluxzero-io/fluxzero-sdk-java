Use this when an asynchronous tracked handler writes a search projection or performs another durable effect. Waiting for
storage and choosing consumer failure behavior are separate decisions: `indexAndWait()` surfaces a stored-write failure
to the handler, while the consumer's `ErrorHandler` decides whether tracking skips, retries, or stops.

Pair this focused failure seam with the verification-boundaries inventory. Projection replacement, public query
mapping, replay, surfaced storage failure, effect-attempt count, and tracker position are separate evidence rows.

## Make production intent explicit

Use a stable document ID, wait for stored completion, and select the error policy on the consumer that owns the tracked
position:

```java
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ThrowingErrorHandler;
import io.fluxzero.sdk.tracking.handling.HandleEvent;

@Searchable(collection = "item-views")
record ItemView(@EntityId String itemId, String label) {
}

record ItemChanged(String itemId, String label) {
}

@Consumer(name = "item-view-projection", errorHandler = ThrowingErrorHandler.class)
final class ItemViewProjection {
    @HandleEvent
    void on(ItemChanged event) {
        Fluxzero.prepareIndex(new ItemView(event.itemId(), event.label()))
                .id(event.itemId())
                .collection("item-views")
                .indexAndWait();
    }
}
```

The default `LoggingErrorHandler` logs and continues. A normally returned batch can then store its final position, so do
not use the default when silently missing one projection update is unacceptable. `ThrowingErrorHandler` stops before
the failing index. A retry policy invokes the complete handler again; every write, schedule, dispatch, or outbound
request completed before the failure must therefore be idempotent or moved behind its own durable intent.

## Inject the actual storage failure

`TestFixture.spy()` exposes Mockito spies for the document store and tracking client. Configure the failure inside the
same `whenExecuting(...)` phase: fixture phase reset clears earlier spy stubbing. The fluent index operation ultimately
calls the eight-argument `DocumentStore.index(...)` method.

```java
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.search.DocumentStoreException;
import io.fluxzero.sdk.test.TestFixture;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

var failure = new DocumentStoreException(
        "forced test failure", new IllegalStateException("store unavailable"));

TestFixture.createAsync(new ItemViewProjection()).spy()
        .whenExecuting(fluxzero -> {
            doReturn(CompletableFuture.failedFuture(failure))
                    .when(fluxzero.documentStore())
                    .index(any(), any(), any(),
                            nullable(Instant.class), nullable(Instant.class),
                            any(Metadata.class), eq(Guarantee.STORED), anyBoolean());

            Fluxzero.publishEvent(new ItemChanged("item-7", "Revised label"));
        })
        .expectError()
        .expectThat(fluxzero -> verify(
                fluxzero.client().getTrackingClient(MessageType.EVENT), never())
                .storePosition(any(), any(), anyLong()));
```

The asynchronous tracker may publish a technical error envelope whose cause chain contains the storage exception, so
do not assume `expectError(DocumentStoreException.class)` at this boundary. `expectError()` proves failure publication;
the stubbed `DocumentStore.index(...)` call establishes the cause, and the position assertion proves the selected stop
policy. Add a cause-chain predicate only when that wrapper shape is itself part of the application contract.

The no-position assertion is correct for a failure on the first event in this isolated consumer. For a later failure in
the same batch, capture the processed message indexes and assert that only the highest earlier successful index is
stored, never the failing or later index. Keep another test for the default or retrying policy only when that policy is
deliberately part of the application contract.

This is a narrow failure-boundary test, not a replacement for ordinary fixture behavior tests. Also test successful
replacement of one stable document, the public query, and the relevant replay/rebuild path without mocks.
