Use this when a product brief contains several validation rules, role levels, searchable fields, or state transitions. Turn each independent rule into a scenario that would fail if that one rule were removed.

## Start with a requirement matrix

Before writing tests, expand words such as "and", "only", "unless", and "inherits" into rows:

| Rule | Valid controls | One changed cause | Expected boundary |
| --- | --- | --- | --- |
| Title is required | Valid description and other fields | Blank title | `ValidationException` for `title` |
| Description is required | Valid title and other fields | Blank description | `ValidationException` for `description` |
| Editors publish articles | Valid draft | Viewer caller | `UnauthorizedException` |
| Owner inherits editor | Valid draft | Owner caller | Success |
| Archive once | Existing archived article | Repeat archive | Domain-specific illegal transition |

One payload with both title and description blank proves only that some validation ran. Keep every control field valid and change one cause per negative scenario. Ask the mutation question: if this annotation, role check, query path, or state branch were removed, which test would fail?

## Assert the intended failure

Do not use a bare `expectExceptionalResult()` for a product rule. It can pass because of an unrelated validation, missing handler, authorization failure, or framework error. Match the most stable signal the application owns:

- Jakarta payload validation: `ValidationException` and the relevant `ValidationException.ViolationSummary` property path.
- Missing identity: `UnauthenticatedException`.
- Authenticated caller without the required role: `UnauthorizedException`.
- Domain legality: the application's functional exception type, error code, or stable rule predicate.

```java
fixture.whenCommand(new CreateProject(" ", "Valid description"))
        .expectExceptionalResult(error ->
                error instanceof ValidationException validation
                && validation.getViolationSummaries().stream()
                        .anyMatch(v -> "title".equals(v.path())));

fixture.whenCommand(new CreateProject("Valid title", " "))
        .expectExceptionalResult(error ->
                error instanceof ValidationException validation
                && validation.getViolationSummaries().stream()
                        .anyMatch(v -> "description".equals(v.path())));
```

Prefer a property path over the validator's complete rendered sentence. The path identifies the broken contract while avoiding dependence on incidental message formatting.

## Prove the role hierarchy

Write the truth table before choosing representative users. For a `VIEWER < EDITOR < OWNER` hierarchy:

| Operation | VIEWER | EDITOR | OWNER |
| --- | --- | --- | --- |
| Browse/approve | allow | allow by inheritance | allow by inheritance |
| Add/import | deny | allow | allow by inheritance |
| Create users | deny | deny | allow |

Cover each distinct boundary: exact-role allow, inherited-role allow, adjacent-role deny, and the top-level allow. Add unauthenticated and authenticated-roleless rows when the product distinguishes them. Use different aggregate IDs in allowed rows so duplicate-state errors cannot mask authorization.

`@RequiresUser` proves that a caller has an identity; `@RequiresUser` does not require `VIEWER`. If every base operation belongs only to the workspace role hierarchy, protect it with `@RequiresAnyRole("VIEWER")` or the application's equivalent role meta-annotation, then test it. Role inheritance is defined by `User.hasRole(...)`; prove it through routed `whenCommandByUser`, `whenQueryByUser`, or web calls rather than only unit-testing an enum.

```java
fixture.whenCommandByUser(viewer, publishFirst)
        .expectExceptionalResult(UnauthorizedException.class);

fixture.whenCommandByUser(editor, publishSecond)
        .expectSuccessfulResult();

fixture.whenCommandByUser(owner, publishThird)
        .expectSuccessfulResult();
```

Use `withProductionUserProvider()` only on a separate unauthenticated scenario where the process-wide production provider is the desired provider; it disables the fixture's system-user fallback. Do not use it to test an arbitrary provider registered on the fixture builder.

## Expand every state-changing command across source states

For a lifecycle, put commands on rows and observable source states on columns before choosing tests. Include a
`MISSING` column; a missing-member failure in one command does not prove the other commands reject it correctly.

| Command | MISSING | REGISTERED | PROCESSING | COMPLETED | INVALID |
| --- | --- | --- | --- | --- | --- |
| Start processing | reject | success + metric | repeated/wrong-state reject | terminal reject | terminal reject |
| Record result | reject | wrong-state reject | success + metric | repeated/conflicting reject | terminal reject |
| Invalidate | reject | success + metric | success + metric | terminal reject | repeated reject |

Write one focused scenario for every product-required non-empty cell. If invalidation is legal from both `REGISTERED`
and `PROCESSING`, test both; one source state does not imply the symmetric path. For results, separate a first result,
an identical repeat, and a conflicting repeat whenever the product distinguishes them. For each rejected row assert
the stable domain error, no event, no custom metric, and unchanged observable state. For each successful row assert the
exact event/state transition and the exact metric or other required side effect.

When a command addresses a nested member by ID alone, repeat the `MISSING` rows through that routed command boundary.
A successful lookup of another member or a missing-member test for a different command cannot prove the handler's own
absence branch.

## Cover fields, states, and side effects independently

- Search each promised field with a term that matches only that field. Test the blank-term browse contract separately.
- Query an existing ID and a missing ID separately.
- For transitions, cover present, missing, already transitioned, and wrong-owner state where the product promises each rule.
- For a command with direct state plus an event, document, outgoing request, or live notification, assert each required observable boundary. One does not prove the others.
- Test a rule deeply at the command/query boundary, then repeat product-critical identity and mapping rows at HTTP or WebSocket boundaries.

For an authenticated socket, open through a user-aware fixture request and assert success. In a separate fixture using the actual production default provider, open without a user, expect `UnauthenticatedException`, and assert the close response. A method annotation or direct open-handler call does not prove that user metadata reaches the socket route.

Avoid a blind Cartesian product. One scenario can cover a row only when it would fail for the same reason. Keep the matrix in the test names or a small table during implementation, then deliver focused tests whose setup makes the single expected rule obvious.
