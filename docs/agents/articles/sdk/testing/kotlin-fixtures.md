# Kotlin fixture types and routed assertions

Use the same Given/When/Then scenario contract as Java. Kotlin syntax does not change what durable setup or a
captured result proves. Prefer `givenCommands(...)` for ordinary end-to-end business setup; use direct durable
Given APIs only when reconstruction itself is the boundary under test.

## Bind the result before inspecting it

Command/query calls return `Then<Object>` on the Java API. Bind the assertion's generic parameter in Kotlin:

```kotlin
fixture.givenCommands(RegisterFolio(folioId, "First edition"))
    .whenQuery(GetFolio(folioId))
    .expectResult<FolioView> { view -> view.folioId == folioId }
    .andThen()
    .whenQuery(ListFolios())
    .expectResult<List<FolioView>> { views -> views.any { it.folioId == folioId } }
```

`expectResult(FolioView::class.java)` is an alternative class-narrowing step. Collection predicates still need the
element type; do not cast `Object` ad hoc or assume passing a query with `Request<R>` makes the fixture action typed.
`andThen()` returns `Given<*>`, not `TestFixture`. Helper functions that advance a phase should preserve that interface:

```kotlin
fun next(then: Then<*>): Given<*> = then.andThen()
```

For direct search, bind the document type at `whenSearching`:

```kotlin
fixture.whenSearching<FolioView>("folio-views") { search -> search }
    .expectResult<List<FolioView>> { views -> views.all { it.caption.isNotBlank() } }
```

## Decode the HTTP result

```kotlin
fixture.whenGet("/api/folios/folio-1")
    .expectWebResult { response ->
        response.status == 200 &&
            response.getPayloadAs<FolioView>(FolioView::class.java).folioId == "folio-1"
    }
```

`getPayloadAs(Type)` needs its own generic argument; the class argument alone does not bind the return type.
The routed call proves transport mapping rather than directly invoking an endpoint method. Add raw cookie/bearer
tests separately when the requirement concerns credential extraction; `...ByUser` helpers bypass that boundary.

## Target a forbidden event

```kotlin
fixture.whenCommand(command)
    .expectNoEventsLike(PublicationReleased::class.java)

fixture.whenCommand(command)
    .expectNoEventLike<PublicationReleased> { event -> event.editionId == editionId }

fixture.whenQuery(listQuery)
    .expectResultContaining(expectedView)
```

The predicate uses Kotlin's SAM conversion to `io.fluxzero.common.ThrowingPredicate`. These event assertions allow
unrelated events. `expectNoEvents()` means exactly none; `expectResultContaining(...)` is inclusive, not an exact
collection or duplicate-count check. Keep each independent invalid condition and its forbidden effects visible
instead of relying on one combined predicate for a whole rejection matrix.
