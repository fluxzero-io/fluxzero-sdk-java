Use `io.fluxzero.common.Decimal` when decimal values should compare by number rather than by scale. It wraps an
immutable `BigDecimal`: `900.00` and `900` are equal and have the same hash code, while `900.01` remains different.
Records, Kotlin data classes, maps and sets therefore use numerical equality without custom comparators.
`BigDecimal` itself retains its existing behavior. Decimal is not a currency or rounding-policy type.

## Java and Kotlin

```java
record Quote(@NotNull @Positive @Digits(integer = 12, fraction = 2) Decimal amount) {}

Decimal amount = new Decimal("900.00");
Decimal total = amount.add(new Decimal("25.50"));
boolean same = new Quote(amount).equals(new Quote(new Decimal("900"))); // true
BigDecimal original = amount.toBigDecimal(); // retains scale 2
```

```kotlin
import io.fluxzero.common.Decimal
import jakarta.validation.constraints.Positive

data class Quote(@field:Positive val amount: Decimal)

val amount = Decimal("900.00")
val total = amount.add(Decimal("25.50"))
check(Quote(amount) == Quote(Decimal("900")))
```

Decimal exposes the public BigDecimal constructors and methods. Operations accept Decimal or BigDecimal operands;
decimal results, including elements of `divideAndRemainder`, remain Decimal. `toBigDecimal()` returns the immutable
underlying value. Scale is preserved for arithmetic and formatting, even though equality ignores it. Equality with a
BigDecimal or another Number type is false in both directions.

Arithmetic follows BigDecimal exactly, including exceptions and rounding. Exact division by three can fail; supply
`MathContext` or an explicit scale and `RoundingMode` when rounding is intended. Overloads that use the dividend's
scale retain that BigDecimal behavior. Deprecated integer rounding-mode overloads are present for API parity; prefer
`RoundingMode`. A double constructor converts the exact binary floating-point value, whereas `valueOf(double)` follows
BigDecimal's decimal-string conversion. Prefer String or integral input for exact decimal literals.

## Exact JSON and numerical documents

Ordinary JSON writes Decimal as a string: `{"amount":"900.00"}`. Readers accept both strings and existing numeric
JSON tokens, without an intermediate double. Null remains null; blank/malformed strings, booleans and objects are
rejected. Jackson's configured maximum number length also applies to decimal strings. Exponent notation is supported.
The codec is attached to the value type, so a normal Jackson mapper also supports it.

Keep the string intact in browser forms and during JSON parse/stringify. Converting it to a JavaScript Number can
lose precision. If the browser must calculate, use decimal arithmetic or request a server calculation. The Java SDK
cannot make JavaScript arithmetic exact automatically.

Document serialization indexes Decimal as NUMERIC, so range queries and sorting retain their numerical meaning.
Document reads recover Decimal from numeric tokens. Other strings stay text; BigDecimal serialization is unchanged.
Existing search range, aggregation and sort precision limits still apply: exact value storage is not a promise of
arbitrary-precision search arithmetic. Custom serializers that override the type's codec own their representation.

OpenAPI describes string output and string-or-number input. A shared request/response DTO receives a separate input
component only when its input schema differs, including differences in recursive/nested types. Server numeric bounds
for Decimal are documented with `x-decimal-minimum` and `x-decimal-maximum`; these are documentation extensions, not
standard JSON-Schema string validators. The server's annotations remain authoritative for inclusivity and validation.
Explicit component overrides retain precedence for both directions; example values remain untouched.

## Validation

The default Fluxzero validator supports `@Min`, `@Max`, `@DecimalMin`, `@DecimalMax`, `@Digits`, `@Positive`,
`@PositiveOrZero`, `@Negative`, `@NegativeOrZero`, `@Null`, `@NotNull` and SDK `@Range`. Ordinary groups, cascades,
container-element constraints and executable validation apply. Null is accepted by numeric constraints; use `@NotNull`
when a value is required. Constraints such as `@Size` do not apply to Decimal.

`@Digits` counts all integer digits, including negative scales: 1000 has four, not one. The existing Fluxzero profile
ignores insignificant trailing fractional zeros. Repeated views of the same constraint on a record field, accessor
and type use produce one violation for the same path and validation group. Distinct constraints remain distinct.

A custom validator declared specifically for BigDecimal does not automatically support Decimal. Implement support for
Decimal explicitly when needed. Third-party Jakarta providers are outside this guarantee; some convert unknown Number
subtypes through double and require a provider-specific adapter for exact comparisons.

## Migration

Adoption is opt-in per field. Old numeric payloads can be read into a Decimal field, but new string output changes the
API contract for clients that insist on numbers. Coordinate client upgrades before switching public fields. Untyped
Object/Map payloads do not acquire Decimal semantics automatically. Review historical data and revision requirements
as for any model change; no global BigDecimal serializer or equality setting is changed.
