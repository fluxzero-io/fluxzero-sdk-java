# Contributing to the Fluxzero SDK

Builders, developers, and coding agents are welcome here. You can report a bug, share an idea, improve the documentation, or submit code. Your first contribution does not need to be polished.

Use an [issue](https://github.com/fluxzero-io/fluxzero-sdk-java/issues/new/choose) when you want to discuss something first. Small fixes can go straight to a pull request, and draft pull requests are welcome. For suspected vulnerabilities, please follow [SECURITY.md](SECURITY.md) so the report stays private.

## Work locally

Clone the repository and run the full build:

```shell
git clone https://github.com/fluxzero-io/fluxzero-sdk-java.git
cd fluxzero-sdk-java
./mvnw -B install
```

The full build runs every test, including the Java/Kotlin downstream projects.
The SDK uses four isolated test JVMs; Test Server and Proxy use two each, and
smaller modules use one. Each JVM has a 768 MiB heap cap. Use `-Dtest.forks=1`
on a smaller machine, or override JVM options with `-Dtest.jvmArgs="-Xmx768m ..."`.
Nested JUnit tests run through their enclosing class, not again as independent
fork roots. Targeted commands such as `./mvnw -pl sdk -am test` continue to run tests.

Test output and XML reports are kept in each module's `target/surefire-reports`;
CI preserves them in the `test-reports` artifact even when the build fails.
Assertion failures and build status remain visible in the Maven console.

## Open a pull request

Tell us what changed and why. Add or update tests when behavior changes, and update the documentation when people need to use the SDK differently. Keeping a pull request focused usually makes review easier, but related cleanup is fine when it helps explain or complete the change.

Coding agents should read [AGENTS.md](AGENTS.md) before making changes. It contains the repository structure, commands, and conventions.

## Review and releases

Maintainer feedback is part of working through the change together. You are welcome to ask questions or push an early version while the approach is still taking shape.

Pull requests are built and tested by GitHub Actions. Accepted changes on `main` move through the automated release pipeline. See [RELEASING.md](RELEASING.md) for the maintainer process and [GitHub Releases](https://github.com/fluxzero-io/fluxzero-sdk-java/releases) for published changes.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
