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
  - inferred and explicit types must agree and must refer to `@Model`;
  - `path` is optional and is the explicit opt-in to automatic graph-document composition;
  - a path on an untyped relationship requires an explicit parent model type;
  - statically typed parent cycles fail validation; fully untyped cycles remain a commit-time graph check.
- `@Apply`, `@AssertLegal`, and `@InterceptApply` descriptors record action-scoped model value/`Entity<T>`
  dependencies. Multiple dependencies of the same model type require unique parameter-level
  `@Association("payloadProperty")` qualifiers.
- A model-targeting `void @Apply` is invalid. Model registration will enforce this without changing legacy mutable
  aggregate behavior.

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
