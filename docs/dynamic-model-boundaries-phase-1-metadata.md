# Phase 1 model metadata decisions and measurements

This report records the structural metadata slice of
[Dynamic model boundaries](dynamic-model-boundaries-backlog.md). Temporary benchmark launchers and detached baseline
worktrees are deliberately not production code.

## Decisions

- `ModelMetadata` is immutable structural metadata owned by the central `ReflectionUtils.TypeMetadata` cache. No
  parallel class-keyed cache was added.
- The generic specialized-metadata map is allocated lazily. Legacy types that never request feature metadata pay no
  map allocation for it.
- Annotated constructors are inspected only when model metadata is built. Existing aggregate matcher discovery does not
  construct or validate `ModelMetadata`.
- Model startup/registration will call `ModelMetadata.validate`. Wiring that registration belongs to Slice 1.3/2.1;
  putting validation in the legacy aggregate matcher path was measured and rejected.
- Every `@Model` has exactly one scalar `@EntityId` and cannot also be an `@Aggregate`.
- `@ParentId` is child-owned and may occur on multiple properties:
  - `Id<T>` supplies the parent model type;
  - `@ParentId(Parent.class)` supplies it for a `String` or other untyped ID;
  - `@ParentId(types = {...}) Id<?>` declares a polymorphic relation whose concrete typed ID selects one parent type;
  - inferred and explicit types must agree and must refer to `@Model`;
  - `path` is optional and is the explicit opt-in to automatic graph-document composition;
  - a path on an untyped relationship requires an explicit parent model type;
  - statically typed parent cycles fail validation; fully untyped cycles remain a commit-time graph check.
- `@Apply`, `@AssertLegal`, and `@InterceptApply` descriptors record commit-scoped model value/`Entity<T>`
  dependencies. Multiple dependencies of the same model type require unique parameter-level
  `@Association("payloadProperty")` qualifiers.
- An `@Apply` may return one model, an ordered typed model collection, or a runtime-validated heterogeneous
  `Collection<Object>`. Collection results remain one atomic substep; null elements and duplicate persisted identities
  are invalid.
- A model-targeting `void @Apply` is invalid. Model registration will enforce this without changing legacy mutable
  aggregate behavior.
- `ModelRoot` owns persisted-root stream/version/time vocabulary; `AggregateRoot` remains a compatibility
  specialization with its declared methods intact.
- `ModelMetadata.RootConfiguration` exposes common `@Model`/`@Aggregate` persistence settings without making model
  code consume an aggregate annotation. It remains lazily owned by the central type metadata cache.
- `ModelRepository`, `Fluxzero.loadModel(...)`, and model-specific `TestFixture` event-sourcing methods establish the
  public boundary. The default `Fluxzero.modelRepository()` fails clearly until the model-commit transport is wired in
  Phase 3; it never falls back to `AggregateRepository`, which would make an old runtime appear to support only part of
  the model contract.
- Every repository overload reduces an ID to exactly `ID.toString()`. No model name or Java type is added to the
  persisted key.

## Rejected legacy-path hooks

A disposable 30-fork JVM benchmark measured first legacy aggregate apply-matcher discovery:

| Variant | Median | p95 | Decision |
|---|---:|---:|---|
| Baseline control | 25.46 ms | 26.14 ms | control |
| Eager full model validation in matcher discovery | 31.21 ms | 32.30 ms | rejected, about 22.6% median regression |
| Cheap annotation gate in matcher discovery | 28.77 ms | 33.53 ms | rejected |
| No model hook, initial rerun | 26.80 ms | 28.26 ms | retained |
| Matching baseline rerun | 26.58 ms | 27.48 ms | noise-level 0.82% difference |

After making the specialized metadata storage lazy, another alternating run measured:

| Build | Median | p95 |
|---|---:|---:|
| Feature | 24.97 ms | 27.13 ms |
| Baseline | 25.24 ms | 29.61 ms |

The feature result was 1.06% faster in that run. Taken together, the no-hook measurements show no systematic legacy
cold-path regression.

## Model metadata costs

Thirty forked JVMs registered a typed parent, typed child, and handler container:

- full first use including model/reflection class initialization: median 148.55 ms, p95 159.50 ms;
- registration of the same three shapes after warming the metadata framework with another model: median 9.23 ms,
  p95 10.19 ms;
- one million centrally cached `ModelMetadata.of` lookups: median 15.8 ns per lookup.

These are local diagnostics rather than production startup certification. They establish that model discovery belongs
at bounded registration time and cached metadata lookup is negligible.

## Existing aggregate apply guard

The existing `AggregateApplyBenchmark` ran 1,000 load/apply/commit iterations per scenario with 64 branches and 32
leaves per branch. The latest feature/baseline results in operations per second were:

| Scenario | Feature | Baseline |
|---|---:|---:|
| root only | 277 | 280 |
| branch only | 426 | 435 |
| leaf only | 373 | 360 |
| leaf and root | 387 | 376 |
| leaf, branch, and root | 369 | 357 |

An earlier matching run produced feature/baseline results of 289/290, 459/446, 378/377, 397/387, and 371/359.
Variation changes direction by scenario/run and there is no systematic throughput loss. The production Phase 0
statistical and p99 gates continue to apply once the integrated model path exists.

## Slice 1.3 compatibility and verification

- No existing aggregate load, apply, commit, cache, or repository invocation now performs model discovery.
  `ModelRoot` adds no state or allocation, and `AggregateRoot` retains all methods it declared before gaining the shared
  superinterface.
- `Fluxzero.modelRepository()`, the new model methods on `Given`/`When`, and their typed conveniences have defaults so
  existing third-party implementations remain source and binary compatible.
- A parity test compares all `@Model` and `@Aggregate` persistence attributes with `RootConfiguration` record
  components. Adding an annotation setting can therefore not silently omit it from aggregate-neutral metadata.
- Focused model/repository/fixture contract tests, Java and Kotlin downstream builds, the full Maven reactor install,
  Javadoc, and `git diff --check` completed successfully.
