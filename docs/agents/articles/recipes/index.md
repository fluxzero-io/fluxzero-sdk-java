Recipes are short task paths. Use them when the user asks an agent to build or extend an app.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

Recommended order:

1. Create the app and package layout.
2. Add one command and aggregate transition.
3. Add queries for reads.
4. For browse/search/count requirements, use indexed search constraints.
5. Add web endpoints as thin adapters.
6. If the caller is signed in or authenticated, complete the credential-to-`Sender` path before considering protected endpoints finished.
7. Test behavior locally, including the public transport contract.

If a recipe mentions an SDK symbol you do not know, call `docs_lookup_symbol` or follow the linked detail article before editing code.
