# AGENTS.md

Instructions for coding agents working in this repository.

## Project Shape

This is the Fluxzero Java SDK, built as a Maven multi-module project.

- `common`: shared protocol objects, serialization, reflection, utilities, websocket helpers, and low-level API types.
- `sdk`: the public Java SDK, including configuration, publishing, tracking, handlers, persistence, scheduling, web support, Spring integration, and the `TestFixture`.
- `test-server`: an in-memory Fluxzero server used for local and integration-style testing.
- `proxy`: the request-forwarding proxy server and its shaded runnable artifact.
- `fluxzero-bom`: dependency-management BOM for downstream SDK projects.
- `annotation-processor-tests`, `java-downstream-project`, and `kotlin-downstream-project`: compatibility checks that must keep working when annotations, reflection, handlers, serialization, public artifacts, or downstream project setup change.
- `project-files`: AI-assistant files for projects that use the SDK, not for building this SDK itself.

## Regression Safety

Observable behavior and operational characteristics are compatibility contracts, even when Java API signatures do not
change. This includes returned values and failures; side effects, ordering, delivery guarantees, retries, and
idempotency; time and context propagation; synchronous and asynchronous completion; resource ownership and lifecycle;
persisted and wire formats; extension points; and throughput, latency, startup time, allocation, blocking, backpressure,
and broader resource consumption. Treat existing behavior outside the explicitly requested change as an invariant by
default.

Treat a change as high-risk when it affects shared infrastructure in `common`, a Fluxzero-wide execution path, stored or
exchanged data, security or contextual behavior, scheduling or time, concurrency or lifecycle, or code that runs for
most messages or requests. For high-risk changes:

1. Before editing, inspect the relevant public entry points, callers, tests, and extension points. Identify the intended
   change, the behavior that must remain unchanged, and the likely blast radius.
2. Prefer the narrowest implementation that satisfies the requirement. Keep unaffected inputs and flows on their
   existing path where practical. Do not assume an alternative implementation is equivalent merely because common
   happy-path results match.
3. Review the change across the applicable contract dimensions above, including uncommon but valid inputs, custom
   implementations and configuration, partial failure, cancellation, retries, concurrency, and shutdown.
4. Add focused tests for the new behavior and for preservation of important existing behavior. Exercise relevant public
   behavior and existing extension points instead of only implementation details. Consider both synchronous and
   asynchronous paths and downstream compatibility where applicable.
5. When complexity, materialization, copying, blocking, reflection, caching, I/O, or the execution model changes on a
   potentially hot path, perform a concrete performance and resource analysis. Add or run a benchmark when that
   analysis cannot confidently rule out a meaningful regression; a green functional suite alone is not sufficient.
6. After implementation, perform a separate adversarial review of the final diff before committing or pushing. A
   deliberate self-review focused only on regressions is the default. Use an independent reviewing agent when the
   complexity, blast radius, or risk of implementation bias makes a fresh context materially valuable.
7. Proactively correct obvious, important regressions within the requested scope. In the final report, state what was
   intentionally changed, which important behavior was preserved, how it was verified, and any plausible remaining
   risks or uncertainty.

Keep regression tests deterministic, focused on observable contracts, and proportionate in runtime. Do not make the
suite materially slower or flaky merely to increase coverage.

## Build And Test

- Use the Maven wrapper: `./mvnw`.
- The project compiles with `maven.compiler.release=21`; CI and Docker images currently run on Temurin/Distroless Java 25.
- Full PR-equivalent verification is `./mvnw -B install`.
- For focused work, prefer targeted Maven runs such as `./mvnw -pl sdk -am test` or `./mvnw -pl proxy -am -Dtest=ProxyServerTest test`.
- Apply the Regression Safety workflow for code changes and run checks proportionate to the affected modules, execution paths, and downstream projects.
- Run `./mvnw -Dgpg.skip -DskipPublishing=true -B install -P deploy` before changes that affect release packaging, generated artifacts, or Maven Central metadata.
- Javadoc/site work should be checked with `./mvnw -B site -Pjavadoc`.

## Coding Guidelines

- This public SDK is used by many external projects. Small mistakes can propagate widely, so favor durable, well-tested solutions over quick patches, local workarounds, or behavior that is hard to explain.
- Do not paper over unclear failures. Understand the root cause, preserve invariants, and document any intentional trade-off in code, tests, or the commit body.
- Preserve the existing package boundaries. Put shared serialization/protocol/reflection behavior in `common`, public SDK behavior in `sdk`, and server-specific behavior in `test-server` or `proxy`.
- Follow existing Java style: Apache license headers on Java/XML files that use them, standard Java imports before static imports, Lombok for boilerplate such as constructors/getters/builders where it fits the local pattern, and small package-private tests where possible.
- Preserve supported public API compatibility by default, but treat it as a design trade-off rather than an overriding constraint. Java visibility alone does not make an API supported, and downstream use of an internal or implementation API does not require preserving it. During the final review, identify source- or binary-incompatible changes and verify whether they affect a supported or intentionally consumed extension point. Seek explicit user agreement before committing such a break to a supported API. Do not contort internal design to retain obsolete methods or constructors; if compatibility would materially harm correctness, reliability, performance, or maintainability, explain the trade-off instead.
- When a field is added to a data or value type, let an existing all-fields constructor evolve with the fields by default, especially when Lombok annotations such as `@AllArgsConstructor` own constructor generation. Do not add a legacy constructor overload merely to preserve the previous field set. During the final review, check whether the old constructor was a documented or intentionally supported external contract; raise the compatibility question only in that case. Add an overload only when it has independent domain meaning or the user explicitly requests it.
- Document new or changed public API surface. Prefer field/type/method Javadoc for data objects and Lombok-backed classes; constructor-level Javadoc may be omitted when Lombok or field documentation already makes the constructor contract clear.
- Prefer existing extension points before adding new abstractions: interceptors, gateways, handlers, registries, parameter resolvers, clients, stores, and `TestFixture`.
- Always use `ReflectionUtils` and its central `TypeMetadata` as the owner of class-scoped reflection caches. Extend that metadata instead of adding parallel `ClassValue` or class-keyed caches for methods, fields, annotations, or other structural reflection results. Keep computed values that capture runtime or instance state in a lifecycle-bound local cache; never place such values in the central class cache.
- Avoid adding dependencies casually. If a dependency is needed, manage versions from the root POM or the relevant BOM/module pattern.
- When changing message handling, tracking, scheduling, websocket, persistence, serialization, or reflection behavior, add focused tests in the owning module and consider both synchronous and asynchronous `TestFixture` paths.
- Do not commit build outputs from `target/`, generated local artifacts, or release-only zips.

## Commit Messages

- Use Conventional Commits with a clear domain scope for human-authored commits.
- Format: `<type>(<domain>): <short imperative summary>`.
- Good examples: `fix(tracking): avoid negative pause sleeps`, `refactor(handling): simplify payload resolver ordering`, `test(logging): cover async appender shutdown`.
- Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `deps`, `perf`, `chore`.
- Useful domains in this repo include `tracking`, `handling`, `modeling`, `entity`, `websocket`, `serialization`, `logging`, `test-server`, `proxy`, `spring`, `ci`, and `deps`.
- For non-trivial commits, include a body that explains why the change is needed, and the behavioral impact.
- Never use literal escape sequences such as `\n` or `\r\n` to represent line breaks in a commit message. Supply real line breaks, for example with separate `-m` arguments or a commit-message file, and inspect the resulting message to ensure it renders correctly.
