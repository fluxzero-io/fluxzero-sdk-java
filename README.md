<a href="https://fluxzero.io"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/3fa8f79df95d07678a730147bc1bd0402ae660d5/assets/brand/2026-09/repository-header.svg" alt="Fluxzero — The European cloud for AI-built apps" width="1280"></a>

# Fluxzero SDK for Java and Kotlin

The SDK connects Java and Kotlin applications to [Fluxzero](https://fluxzero.io). Use it to build product behavior with models, messages, web handlers, schedules, validation, authorization, persistence, search, and tests.

[![Build](https://github.com/fluxzero-io/fluxzero-sdk-java/actions/workflows/deploy.yml/badge.svg)](https://github.com/fluxzero-io/fluxzero-sdk-java/actions)
[![Packages](https://img.shields.io/badge/packages-releases-blue)](https://packages.fluxzero.io/maven/io/fluxzero/fluxzero-bom/)
[![Javadoc](https://img.shields.io/badge/javadoc-main-blue)](https://fluxzero-io.github.io/fluxzero-sdk-java/javadoc/apidocs/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)

[Documentation](https://fluxzero.io/docs) · [Get started](https://fluxzero.io/get-started) · [Javadoc](https://fluxzero-io.github.io/fluxzero-sdk-java/javadoc/apidocs/) · [Packages](https://packages.fluxzero.io/maven/io/fluxzero/fluxzero-bom/) · [Releases](https://github.com/fluxzero-io/fluxzero-sdk-java/releases)

## What the SDK does

- Models product state and persists changes.
- Handles commands, events, queries, web requests, WebSockets, and scheduled work.
- Applies validation and authorization where product behavior is defined.
- Routes work across application instances and exposes runtime metrics.
- Tests complete flows locally with `TestFixture` and the in-memory runtime.
- Supports Java, Kotlin, Spring, Maven, and Gradle projects.

The full guides, tutorials, configuration reference, and examples live in the [Fluxzero documentation](https://fluxzero.io/docs).

## Start building

Building with a coding agent? Give it this:

```text
Build my app with Fluxzero. Start at plugins.fluxzero.io
```

The [Fluxzero agent plugins](https://plugins.fluxzero.io) guide your agent through creating, running, testing, and extending the application.

Prefer to work directly in the code? [Install the Fluxzero CLI](https://fluxzero.io/docs/getting-started/installation), then create and run a project:

```shell
fz init --name my-project
cd my-project
./gradlew run
```

Continue with the [first handler](https://fluxzero.io/docs/getting-started/hello-world), browse the [core concepts](https://fluxzero.io/docs/getting-started/core-concepts), or use the installation guide for manual Maven and Gradle setup.

## Work on the SDK

The SDK is a Maven multi-module project and requires JDK 21 or newer.

```shell
git clone https://github.com/fluxzero-io/fluxzero-sdk-java.git
cd fluxzero-sdk-java
./mvnw -B install
```

For a focused SDK test run:

```shell
./mvnw -pl sdk -am test
```

The main modules are `sdk`, `common`, `test-server`, `proxy`, and `fluxzero-bom`. Release maintainers can find the publication process in [RELEASING.md](RELEASING.md).

## Contributing

We welcome your ideas, issues, and pull requests.

- [Open an issue](https://github.com/fluxzero-io/fluxzero-sdk-java/issues/new) for a bug, idea, or question.
- Keep pull requests focused and include tests for changed behavior.
- Run `./mvnw -B install` before opening a pull request.
- Coding agents should read [AGENTS.md](AGENTS.md) before changing the repository.

This project is available under the [Apache License 2.0](LICENSE).
