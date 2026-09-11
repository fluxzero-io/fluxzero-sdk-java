Recipes are short task paths. Use them when the user asks an agent to build or extend an app.

Recommended order:

1. Create the app and package layout.
2. Add one command and Model transition.
3. Add queries for reads.
4. For browse/search/count requirements, use indexed search constraints.
5. Add web endpoints as thin adapters.
6. If the caller is signed in or authenticated, complete the credential-to-`Sender` path before considering protected endpoints finished.
7. Test behavior locally, including the public transport contract.

If a recipe mentions an SDK symbol you do not know, call `docs_lookup_symbol` or follow the linked detail article before editing code.
