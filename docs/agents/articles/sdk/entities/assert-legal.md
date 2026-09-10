`@AssertLegal` validates whether a command may be applied. Use it for business invariants and ownership checks that depend on current state or sender context.

```java
@AssertLegal
void assertOwner(Project project, Sender sender) {
    if (!project.ownerId().equals(sender.userId())) {
        throw ProjectErrors.unauthorized;
    }
}
```

Assertions may load or query data when needed, but they must not perform updates. Keep error types grouped in a domain error interface so tests can assert the exact rule that failed.

Assertion methods can inject the current entity, ancestors, the payload, metadata, the full message, and user context. Use nullable parameters when absent entities are legal for a create path.

If an assertion returns a non-null object, Fluxzero inspects that object for further `@AssertLegal` methods. Use this only when it makes nested legality reusable; otherwise return `void`.

Use `priority` and `afterHandler` only when assertion ordering changes behavior. Most domains should keep assertions independent enough that ordering does not matter.

Throw domain errors built from Fluxzero `FunctionalException` helpers/constants so callers receive functional failures instead of infrastructure errors.

Security annotations are good coarse gates. Put state-dependent authorization, ownership, quota, and cross-aggregate legality in `@AssertLegal` so the rule is tested with the domain behavior.

Use Jakarta validation for payload shape, nullability, and scalar constraints. Use `@AssertLegal` for rules that depend on existing state, user context, other aggregates, or current search/query results.
