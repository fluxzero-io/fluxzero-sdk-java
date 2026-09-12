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

Explore the [core concepts](https://fluxzero.io/docs/getting-started/core-concepts) or browse the full [guides, tutorials, and reference documentation](https://fluxzero.io/docs).

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
  <a href="https://github.com/fluxzero-io/fluxzero-sdk-java"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/sdk-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/sdk-light.svg" alt="SDK — Connect your code to Fluxzero" width="150" height="68"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-agent-plugins"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/agents-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/agents-light.svg" alt="Agent plugins — Guide your coding agent" width="150" height="68"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-cli"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/cli-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/cli-light.svg" alt="CLI — Create, run, and deploy apps" width="150" height="68"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-dev-server"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/dev-server-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/dev-server-light.svg" alt="Dev Server — Develop and test locally" width="150" height="68"></picture></a>
</p>

<p align="center">
  <a href="https://fluxzero.io/">Website</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/how-it-works">How it works</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/docs">Docs</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/about">About us</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/contact">Contact us</a>
</p>
