To add a command:

1. Name the payload imperatively, for example `CreateProject`.
2. Include a typed aggregate ID and validated details.
3. Implement or reuse a self-handling update interface with `@HandleCommand`.
4. Add `@Apply` methods for create/update/delete state.
5. Add `@AssertLegal` for business rules and ownership.
6. Write a `TestFixture` scenario before adding an endpoint.

Generate IDs outside `@Apply`. If the command targets a member entity, include both parent and member IDs so routing is unambiguous.

Do not add the sending user's ID to the payload. Inject `Sender` in the handler or legal assertion and keep authorization checks close to the command behavior.
