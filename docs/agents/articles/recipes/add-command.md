To add a command:

1. Name the payload imperatively, for example `CreateProject`.
2. Include a typed Model ID and validated details.
3. Use automatic Model command handling; add `@HandleCommand` only for real orchestration.
4. Add `@Apply` methods for create/update/delete state.
5. Add `@AssertLegal` for business rules and ownership.
6. Write a `TestFixture` scenario before adding an endpoint.

Generate IDs outside `@Apply`. Use `@Association` only for ambiguous same-type targets or paths. Use `@Parent` for independent children and `@Member` only for deliberately root-owned state.

Do not add the sending user's ID to the payload. Inject `Sender` in the handler or legal assertion and keep authorization checks close to the command behavior.

One payload may update several Models atomically. Use Model/Graph injected read dependencies for invariants; an
arbitrary external search is not automatically a protected read set. Explicit routing still takes precedence over
inferred single-target routing.
