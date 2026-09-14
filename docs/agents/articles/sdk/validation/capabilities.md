Fluxzero supplies a focused Jakarta Validation provider for application messages and handler executables. Use the
supported profile directly; do not assume every Hibernate Validator, CDI, XML, or Expression Language feature is
present.

## Supported application profile

The SDK profile covers standard Jakarta constraints, custom constraint validators, groups, cascaded/container
validation, value extractors, method/constructor parameter and return validation, property/value validation, and raw
structured constraint violations. Fluxzero convenience constraints include URL, UUID, unique-elements, range, length,
and credit-card validation.

`ValidationUtils` uses the validator configured on the active Fluxzero instance and exposes:

- `assertValid(...)`, `isValid(...)`, and `checkValidity(...)`;
- `getConstraintViolations(...)` for structured paths;
- executable parameter and return validation;
- authorization helpers distinct from structural validation.

Constrained payload methods can receive parameters from Fluxzero's handler `ParameterResolver` set. If a parameter is
unresolved for that validation run, that constrained method is skipped. Put mandatory rules on fields or no-argument
methods; use injected contextual validation only when absence is intentionally allowed.

## Field and method ordering

The default payload-validation route (`assertValid`, `checkValidity`, `isValid`) checks field constraints and their
cascades before the containing object's method constraints. Pure methods need not repeat null guards for values
required by active field or container-element constraints. `@Valid` alone does not require a value.
Optional/conditional values still need an appropriate rule; groups and cascades must activate the prerequisites.
An earlier group-sequence stage cannot rely on a later stage's constraints.

This is a validation-result guarantee, not an invocation-count guarantee: a diagnostic second pass can still try
methods after field failures, retaining the field rejection if a method throws. Raw `getConstraintViolations` and
Jakarta `validate` do not suppress those exceptions. Replacement validators own their ordering. See the main
validation article for a complete example; keep all constraint methods free of side effects.

## Intentional limitations

Do not design an application around:

- `validation.xml` or XML constraint mappings;
- CDI-managed validator lifecycle;
- `TraversableResolver` reachability policy;
- full Expression Language message evaluation.

Use Java annotations and application code instead. If a required validation contract is not supported, replace the
validator centrally through the advanced builder only after adding parity tests for payloads, groups, nested/container
values, executable parameters/returns, and structured error paths.

## Content filtering is output shaping

`@FilterContent` must be enabled on the handler method, type, or package. Filter methods run recursively over objects,
collections, and maps, can inject the current `User` and root object, and remove a value by returning `null`.

```java
record VisibleItem(String itemId, String internalNote) {
    @FilterContent
    VisibleItem filter(User user) {
        return user.hasRole("operator")
                ? this
                : new VisibleItem(itemId, null);
    }
}
```

Filtering is not an authorization boundary by itself. Reject unauthorized requests before handling and use filtering
only to shape an otherwise permitted response. Test scalar, list, map, nested-root, and complete-removal behavior.

Protected values have a separate storage, restoration, and deletion lifecycle. Read the protected-data article before
using them; validation rules alone do not define when the original value remains available to later handlers.
