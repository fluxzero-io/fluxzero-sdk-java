Write tests at the behavior boundary the user cares about.

In an active Fluxzero dev session, the agent writes these tests while the dev server selects and executes them. Follow
the post-edit, cursored test events to a terminal lifecycle state and corroborate the current test service status; do
not run the wrapper in parallel just to obtain a second copy of the same result.

- For command behavior, send the command and assert events, resulting queries, or errors.
- For query behavior, seed commands first and assert the typed result.
- For endpoints, use routed `TestFixture.whenGet/whenPost` or the corresponding web-request helper to prove route binding, content negotiation, caller propagation, and response conversion. A direct endpoint invocation may supplement a narrow pure-adapter unit test, but it does not count as routed endpoint coverage.
- For side effects, assert outgoing web requests or messages instead of mocking internals.

Do not use direct calls to `@Apply`, `@AssertLegal`, or handler methods as the main test for a product rule. Dispatch through `TestFixture`, then observe emitted events or state loaded/queryable in a later step so metadata, persistence, replay, and authorization participate.

For a product brief with frontend-callable operations, turn the advertised surface into a checklist. Cover each HTTP operation with `whenGet`/`whenPost` or the `...ByUser` variants, including product-critical validation, duplicate, and authorization failures. Keep domain command/query tests as the deeper behavior layer; endpoint tests verify that request fields, caller context, typed IDs, and response/error mappings reach that behavior intact. Use the focused routed HTTP endpoint testing article for the per-operation matrix and the distinction between domain, route, OpenAPI, and real-network evidence.

Turn implemented domain errors into the same checklist and cover every branch tied to the requested behavior. Test bulk/import commands through the bulk operation itself, including a successful multi-item request, duplicate-in-request and existing-state collisions, and the intended rollback, partial-progress, or per-item outcome semantics. Put a failing item after at least one valid item, then query every affected aggregate. Local nested handlers can roll back with the outer invocation, while an externally dispatched child can already be durable; test the actual routing boundary instead of inferring visibility from the loop. A fail-fast loop is not continue-and-report best effort. Use the focused bulk-workflow article for the complete matrix and executable pattern. For actor-sensitive state, dispatch as one user, load/query the result later, and attempt the next transition as both the owner and another user. Pin time with `atFixedTime(...)` for release dates, expiry, and scheduling rules.

For live updates, test both halves: use the concrete `socket(method, sessionId, payload)` helper from the WebSocket testing article, seed state, and assert the snapshot payload returned by `whenWebRequest(socket(WS_OPEN, sessionId, null))`. Then open a fresh session with `givenWebRequest(socket(WS_OPEN, sessionId, null))`, trigger the real command/event, and assert the emitted `WebResponse`. A snapshot alone is not evidence of later push delivery, and a push-handler annotation alone is not evidence of an initial snapshot.

Request generated OpenAPI through the fixture route, then parse and assert paths, operations, request fields, and response schemas. Avoid reflection-only or broad string-containment assertions. OpenAPI covers HTTP operations, not WebSocket lifecycle pseudo-methods; verify live routes separately.

Tests should make the expected business rule obvious. Prefer one focused scenario per rule over broad setup that hides the intent.

For a two-component durable process, start from the correlated-workflow matrix rather than mirroring only one happy
path. Add synthetic reconstruction in a new default fixture for every primary and secondary route plus an active
deadline. Add a retained-runtime application restart only when persistence survival is part of the required evidence.

For several validation constraints or role levels, write an independent behavior matrix before implementing tests. Keep every control input valid, change one cause, and assert the exact validation path or authorization exception. Use the focused behavior-matrix article for exact-role, inherited-role, adjacent-denial, roleless, and unauthenticated scenarios.

For a tracked handler failure that requires retry, compensation, or a durable correction, use the error-corrections article. Test it with an asynchronous fixture and assert the original error, the triggered follow-up message, and its final observable result independently.

Use `TestFixture.create(...)` for synchronous behavior tests and `createAsync(...)` only when async consumer behavior matters. When payload or document shape changes after deployment, increment `@Revision` and add a `whenUpcasting(...)` test before relying on historical data.
