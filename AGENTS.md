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

## Build And Test

- Use the Maven wrapper: `./mvnw`.
- The project compiles with `maven.compiler.release=21`; CI and Docker images currently run on Temurin/Distroless Java 25.
- Full PR-equivalent verification is `./mvnw -B install`.
- For focused work, prefer targeted Maven runs such as `./mvnw -pl sdk -am test` or `./mvnw -pl proxy -am -Dtest=ProxyServerTest test`.
- Run `./mvnw -Dgpg.skip -DskipPublishing=true -B install -P deploy` before changes that affect release packaging, generated artifacts, or Maven Central metadata.
- Javadoc/site work should be checked with `./mvnw -B site -Pjavadoc`.

## Coding Guidelines

- This public SDK is used by many external projects. Small mistakes can propagate widely, so favor durable, well-tested solutions over quick patches, local workarounds, or behavior that is hard to explain.
- Do not paper over unclear failures. Understand the root cause, preserve invariants, and document any intentional trade-off in code, tests, or the commit body.
- Preserve the existing package boundaries. Put shared serialization/protocol/reflection behavior in `common`, public SDK behavior in `sdk`, and server-specific behavior in `test-server` or `proxy`.
- Follow existing Java style: Apache license headers on Java/XML files that use them, standard Java imports before static imports, Lombok for boilerplate such as constructors/getters/builders where it fits the local pattern, and small package-private tests where possible.
- Keep public SDK APIs backward compatible unless the task explicitly calls for a breaking change.
- Document new or changed public API surface. Prefer field/type/method Javadoc for data objects and Lombok-backed classes; constructor-level Javadoc may be omitted when Lombok or field documentation already makes the constructor contract clear.
- Prefer existing extension points before adding new abstractions: interceptors, gateways, handlers, registries, parameter resolvers, clients, stores, and `TestFixture`.
- Avoid adding dependencies casually. If a dependency is needed, manage versions from the root POM or the relevant BOM/module pattern.
- When changing message handling, tracking, scheduling, websocket, persistence, serialization, or reflection behavior, add focused tests in the owning module and consider both synchronous and asynchronous `TestFixture` paths.
- Do not commit build outputs from `target/`, generated local artifacts, or release-only zips.

## Commit Messages

- Use Conventional Commits with a clear domain scope for human-authored commits.
- Format: `<type>(<domain>): <short imperative summary>`.
- Good examples: `fix(tracking): avoid negative pause sleeps`, `refactor(handling): simplify payload resolver ordering`, `test(logging): cover async appender shutdown`.
- Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `deps`, `perf`, `chore`.
- Useful domains in this repo include `tracking`, `handling`, `modeling`, `entity`, `websocket`, `serialization`, `logging`, `test-server`, `proxy`, `spring`, `ci`, and `deps`.
- For non-trivial commits, include a body that explains why the change is needed, the behavioral impact, and the tests that were run.
