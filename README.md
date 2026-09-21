<a href="https://fluxzero.io"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/3fa8f79df95d07678a730147bc1bd0402ae660d5/assets/brand/2026-09/repository-header.svg" alt="Fluxzero — The European cloud for AI-built apps" width="1280"></a>

# Fluxzero SDK

This is the SDK for building applications on [Fluxzero](https://fluxzero.io). It supports both Java and Kotlin.

[![Build](https://github.com/fluxzero-io/fluxzero-sdk-java/actions/workflows/deploy.yml/badge.svg)](https://github.com/fluxzero-io/fluxzero-sdk-java/actions)
[![Packages](https://img.shields.io/badge/packages-releases-blue)](https://packages.fluxzero.io)
[![Javadoc](https://img.shields.io/badge/javadoc-main-blue)](https://fluxzero-io.github.io/fluxzero-sdk-java/javadoc/apidocs/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)

[SDK documentation](https://fluxzero.io/docs) · [Javadoc](https://fluxzero-io.github.io/fluxzero-sdk-java/javadoc/apidocs/) · [Packages](https://packages.fluxzero.io) · [Releases](https://github.com/fluxzero-io/fluxzero-sdk-java/releases)

## Build product behavior

Use the SDK to describe a feature with plain messages, immutable models, and small handler methods. A handler can change product state, answer a query, expose a web endpoint, react to an event, or schedule work. Validation and authorization stay close to the behavior they protect.

Fluxzero handles delivery, routing, persistence, retries, and observability around that code. You can test complete product flows locally with `TestFixture` and run the same application behavior on Fluxzero.

For external APIs, give each interaction a local command or query whose handler calls the
[WebRequest gateway](docs/developer/guides/Messaging/150-sending-web-requests.mdx). No injected API-service bean or
`@TrackSelf` is required for request auditability, configurable HTTP retries and fixture-based tests. Standard API-key
and authorization headers are masked in visible Auditlog records; authenticated calls do not require a separate HTTP client.

Before adding application classes, choose their owning domains and follow the
[application package structure](docs/developer/getting-started/package-structure.mdx), including the example tree
and the final layout check.

Explore the [core concepts](https://fluxzero.io/docs/getting-started/core-concepts) or browse the full [guides, tutorials, and reference documentation](https://fluxzero.io/docs).

For SDK v2 Models, start with plain `@Model`: [design cohesive details and state](docs/developer/guides/Modeling%20%26%20persistence/195-model-state.mdx), then use the [Model and Graph query guide](docs/developer/guides/Modeling%20%26%20persistence/205-model-query-guide.mdx) to choose storage, queries, and the state they return.

For concurrent invariants, distinguish [read boundaries and commit dependencies](docs/developer/guides/Modeling%20%26%20persistence/202-model-state-boundaries.mdx#model-read-boundaries):
manual Graph reads inside a Model mutation share its snapshot and readset; event reads, explicit current reads outside
mutations and document search have different guarantees.
Consumed Graph alias lookups also protect alias assignment/removal and canonical-ID precedence at commit;
exact-ID reads remain independent of aliases.
Model conflict handling defaults to `RETRY` for updates and creations, independently of `fluxzero.defaults.version`;
choose `FAIL` or `ACCEPT` explicitly with `fluxzero.model.conflictPolicy` (`FLUXZERO_MODEL_CONFLICT_POLICY`).
Retry preserves create-only checks. A targeted `graph.assertAndApply(command)` limits writes, not the command's
cross-Model assertions. See [conflict policies](docs/agents/articles/sdk/models/conflicts.md).

For historical comparisons with `previous()`, keep `EVENT_SOURCED` enabled; `DOCUMENT` alone keeps current state only.
The [Model recipes](docs/developer/guides/Modeling%20%26%20persistence/197-model-recipes.mdx) cover one-to-one companions,
derived Graph preferences, atomic actions versus orchestration, and `graph.current()` without losing history.
For eventless current state, non-searchable documents and erasure, read the
[Model state boundaries](docs/developer/guides/Modeling%20%26%20persistence/202-model-state-boundaries.mdx):
storage and query visibility are not authorization or secret-storage guarantees.
Optional `DOCUMENT` projections are separate from internal Model/Graph sources: direct search returns the projection,
while Graph composition and related-content predicates use the internal source. See the
[migration guide](docs/developer/guides/Modeling%20%26%20persistence/207-model-migration-tests.mdx) for independent reindexing.
Choose [Graph relationships](docs/developer/guides/Modeling%20%26%20persistence/190-nested-entities.mdx) separately from ownership: a plain typed ID is only a reference; `@Parent` registers an edge, with cascade deletion configurable per relation.
See [Model updates](docs/developer/guides/Modeling%20%26%20persistence/180-updating-entities.mdx) for lifecycle checks and dynamic writes, and [schedule reconciliation](docs/developer/guides/Messaging/085-model-schedule-reconciliation.mdx) for delayed work and cascade cleanup.
Returned validation objects can select their own Model dependencies while retaining the triggering command context.

Intentionally embedded `@Member` handlers, including concrete subtype handlers on explicitly addressed owners,
update their owning Model in the same atomic operation and replay stream;
see [embedded members](docs/developer/guides/Modeling%20%26%20persistence/195-model-state.mdx#intentionally-embedded-members).

[Parent-owned schedules](docs/developer/guides/Messaging/086-parent-owned-schedules.mdx) use `@Parent` or
`Schedule.withParents(...)` to cancel delayed work automatically when a committed Model is deleted.

Changing existing state? Use [Model migration tests](docs/developer/guides/Modeling%20%26%20persistence/207-model-migration-tests.mdx) to distinguish value-preserving upcasts, event reconstruction, retained storage and search reindexing.

Keep business facts in Models; use [stateful handlers](https://fluxzero.io/docs/guides/modeling-and-persistence/stateful-handlers)
for durable integration progress, correlation and retries. A provider callback and a completed business transaction
are distinct facts, with an explicit recovery boundary between them.
Use events when every transition matters. Document observers may skip intermediate versions; they can reconcile
work only while the latest state retains every unfinished action. See the stateful guide for bounded continuation
and acknowledgement boundaries.

## Start building

Building with a coding agent? Give it this:

```text
Build my app with Fluxzero. Start at plugins.fluxzero.io
```

The [Fluxzero agent plugins](https://plugins.fluxzero.io) guide your agent through creating, running, testing, and extending the application.

Prefer to work directly in the code? [Install the Fluxzero CLI](https://fluxzero.io/docs/getting-started/installation) and use it to create projects, start the development server, run builds, and deploy applications.

## Versions and releases

SDK packages are published through [Fluxzero Packages](https://packages.fluxzero.io). Maven Central contains releases published before 1 October 2026; releases from that date onward are published only through Fluxzero Packages.

See [Compatibility & dependencies](https://fluxzero.io/docs/about/compatibility) for supported Java versions and SDK/runtime compatibility. Follow the [changelog](https://fluxzero.io/docs/changelog) or [GitHub Releases](https://github.com/fluxzero-io/fluxzero-sdk-java/releases) for changes. Release maintainers can find the publication process in [RELEASING.md](RELEASING.md).

## Proxy response header buffers

Set `fluxzero.proxy.responseHeaderBufferSize` (`FLUXZERO_PROXY_RESPONSE_HEADER_BUFFER_SIZE`)
to select the initial response header buffer capacity. The default is 8192 bytes; the separate
`FLUXZERO_PROXY_MAX_HEADER_SIZE` remains 1 MiB. Large headers can trigger a second allocation,
whereas large response bodies do not require larger header buffers.

HTTP/1.1 header growth has a known connection-close limitation tracked in
[Jetty #15840](https://github.com/jetty/jetty.project/issues/15840); see the
[buffering guidance](docs/agents/articles/sdk/web/advanced-transport.md#proxy-response-header-buffers)
for the workaround scope and configuration fallback.

Jetty uses direct HTTP output buffers by default. Set
`fluxzero.proxy.useOutputDirectByteBuffers=false`
(`FLUXZERO_PROXY_USE_OUTPUT_DIRECT_BYTE_BUFFERS=false`) to use heap output buffers instead. This
setting applies to all HTTP output and should be selected together with the JVM and container
memory budgets. See the [proxy buffering guidance](docs/agents/articles/sdk/web/advanced-transport.md#proxy-response-header-buffers)
and [forked benchmark](proxy/HEADER_BUFFER_BENCHMARK.md).

## Work on the SDK

The SDK is a Maven multi-module project.

```shell
git clone https://github.com/fluxzero-io/fluxzero-sdk-java.git
cd fluxzero-sdk-java
./mvnw -B install
```

## Contributing

Builders, developers, and coding agents are welcome. Open an [issue](https://github.com/fluxzero-io/fluxzero-sdk-java/issues/new/choose) or [pull request](https://github.com/fluxzero-io/fluxzero-sdk-java/compare) whenever you have something useful to share; it does not need to be polished.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the local build and a few practical pointers. Please report suspected vulnerabilities privately by following [SECURITY.md](SECURITY.md).

This project is available under the [Apache License 2.0](LICENSE).


---

<p align="center"><strong>Are you a builder or coding agent?</strong><br>We welcome your ideas, issues, and pull requests!</p>

<p align="center">
  <a href="https://github.com/fluxzero-io/fluxzero-sdk-java"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-light.svg" alt="SDK — Connect your code to Fluxzero"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-agent-plugins"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-light.svg" alt="Agent plugins — Guide your coding agent"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-cli"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-light.svg" alt="CLI — Create, run, and deploy apps"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-dev-server"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-light.svg" alt="Dev Server — Develop and test locally"></picture></a>
</p>

<p align="center">
  <a href="https://fluxzero.io/">Website</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/how-it-works">How it works</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/docs">Docs</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/about">About us</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/contact">Contact us</a>
</p>
