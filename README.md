<a href="https://fluxzero.io"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/3fa8f79df95d07678a730147bc1bd0402ae660d5/assets/brand/2026-09/repository-header.svg" alt="Fluxzero — The European cloud for AI-built apps" width="1280"></a>

# Fluxzero SDK

This is the SDK for building applications on [Fluxzero](https://fluxzero.io). It supports both Java and Kotlin.

[![Build](https://github.com/fluxzero-io/fluxzero-sdk-java/actions/workflows/deploy.yml/badge.svg)](https://github.com/fluxzero-io/fluxzero-sdk-java/actions)
[![Packages](https://img.shields.io/badge/packages-releases-blue)](https://packages.fluxzero.io)
[![Javadoc](https://img.shields.io/badge/javadoc-main-blue)](https://fluxzero-io.github.io/fluxzero-sdk-java/javadoc/apidocs/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)

[Website](https://fluxzero.io) · [Documentation](https://fluxzero.io/docs) · [Javadoc](https://fluxzero-io.github.io/fluxzero-sdk-java/javadoc/apidocs/) · [Packages](https://packages.fluxzero.io) · [Releases](https://github.com/fluxzero-io/fluxzero-sdk-java/releases)

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

## Work on the SDK

The SDK is a Maven multi-module project and requires JDK 21 or newer.

```shell
git clone https://github.com/fluxzero-io/fluxzero-sdk-java.git
cd fluxzero-sdk-java
./mvnw -B install
```

Release maintainers can find the publication process in [RELEASING.md](RELEASING.md).

## Contributing

We welcome your ideas, issues, and pull requests.

- [Open an issue](https://github.com/fluxzero-io/fluxzero-sdk-java/issues/new) for a bug, idea, or question.
- Keep pull requests focused and include tests for changed behavior.
- Run `./mvnw -B install` before opening a pull request.
- Coding agents should read [AGENTS.md](AGENTS.md) before changing the repository.

This project is available under the [Apache License 2.0](LICENSE).
