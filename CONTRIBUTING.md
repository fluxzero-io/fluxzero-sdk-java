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
The CI workflows use one fork per module for runners with few cores. Each fork runs at most two test classes;
waiting tests do not create additional JUnit workers. Methods remain sequential unless a test explicitly opts into concurrency.
Nested JUnit tests run through their enclosing class, not again as independent
fork roots. Targeted commands such as `./mvnw -pl sdk -am test` continue to run tests.

Test output and XML reports are kept in each module's `target/surefire-reports`;
CI preserves them in the `test-reports` artifact even when the build fails.
Assertion failures and build status remain visible in the Maven console.

## IntelliJ IDEA

Import the root Maven project with a Java 25+ project SDK. Use IntelliJ's Java compiler and the shared
**All tests** compound configuration. It starts the five SDK test modules concurrently in separate JVMs;
each module's before-launch Build step uses IntelliJ's compiler. Results appear in a separate test tab per module.
Module-specific classpaths and working directories keep their test services and model catalogs isolated.

The SDK runs one test class per two available processors (at least one); the other modules run two each.
Methods remain sequential unless a test explicitly opts into concurrency. These short-lived module runs use `-XX:TieredStopAtLevel=1` to avoid
spending their lifetime on higher-tier JIT compilation. Maven and **All tests sequential** retain normal tiered
compilation; use those for validation with the full optimizing JVM and use separate benchmark configurations
for performance measurements.
Each JVM enables fixture cleanup failure reporting and has a 768 MiB heap cap. The shared test configurations disable
IntelliJ's extra async exception stack capture for ordinary Run sessions; this avoids instrumenting every future just
to run tests. Normal exception stack traces and Debug sessions remain available. The **Module tests** folder contains
the individual configurations. Use **All tests sequential** to run the modules sequentially with a single results tree when
memory is constrained. Java/Kotlin downstream artifact checks run through `./mvnw -B install`.

After changing branches, check the active configuration if execution appears sequential. IntelliJ can retain options
that differ from the shared file: each launched test JVM should contain
`-Djunit.jupiter.execution.parallel.enabled=true` and `-Djunit.jupiter.extensions.autodetection.enabled=true`.
Keep the fixture cleanup extension enabled so cleanup failures fail their owning test. If compiled classes are stale
after a branch switch, use **Build → Rebuild Project**.

The test console prints WARN and ERROR events by default; INFO events remain enabled for logging assertions.
Use `-Dtest.console.level=INFO` to include fixture lifecycle and diagnostic traces in the console.

## Open a pull request

Tell us what changed and why. Add or update tests when behavior changes, and update the documentation when people need to use the SDK differently. Keeping a pull request focused usually makes review easier, but related cleanup is fine when it helps explain or complete the change.

Coding agents should read [AGENTS.md](AGENTS.md) before making changes. It contains the repository structure, commands, and conventions.

## Review and releases

Maintainer feedback is part of working through the change together. You are welcome to ask questions or push an early version while the approach is still taking shape.

Pull requests are built and tested by GitHub Actions. Accepted changes on `main` move through the automated release pipeline. See [RELEASING.md](RELEASING.md) for the maintainer process and [GitHub Releases](https://github.com/fluxzero-io/fluxzero-sdk-java/releases) for published changes.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
