To add a query:

1. Create a payload implementing `Request<T>`.
2. Keep the handler read-only.
3. Use `Fluxzero.loadModel(id)` for direct lookup, or use `Fluxzero.search(...)` for lists, filters, sorting, and counts.
4. Return a typed result, not loosely shaped maps unless the API is truly dynamic.
5. Add a `TestFixture` query test.

If the query supports user input filters, build those filters in the query handler. Web or UI code should pass intent, not duplicate search behavior.

For an indexed list/count requirement, follow the dedicated search-list-count recipe. Do not call `fetchAll()` and then filter, sort, paginate, or count in Java. Build constraints with `match(...)`, `lookAhead(...)`, `query(...)`, time/range operations, and `sortBy(...)`; finish with a bounded `fetch(...)` or `count()`.

For a standalone/component query handler, use `@LocalHandler` when it should run synchronously and bypass persisted tracking. An ordinary self-handling query without `@TrackSelf` is already local; read self-handling query placement before adding tracking or registering payload classes. Use `Fluxzero.memoize(...)` for lightweight local caching instead of static utility caches.
