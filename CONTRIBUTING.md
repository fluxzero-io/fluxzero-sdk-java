# Contributing to the Fluxzero SDK

Builders, developers, and coding agents are welcome here. You can report a bug, share an idea, improve the documentation, or submit code. Your first contribution does not need to be polished.

Use an [issue](https://github.com/fluxzero-io/fluxzero-sdk-java/issues/new/choose) when you want to discuss something first. Small fixes can go straight to a pull request, and draft pull requests are welcome. For suspected vulnerabilities, please follow [SECURITY.md](SECURITY.md) so the report stays private.

## Work locally

Clone the repository and run the full build:

```shell
git clone https://github.com/fluxzero-io/fluxzero-sdk-java.git
cd fluxzero-sdk-java
mise install
mise exec -- ./mvnw -B install
```

`mise.toml` pins the development JDK. With `mise activate zsh` configured in your shell,
mise selects it automatically in this directory. Alternatively, set `JAVA_HOME` to JDK 25.0.3 or
newer and run `./mvnw` directly. Maven rejects older JDKs because early Java 25 builds contain
[JDK-8370887](https://github.com/openjdk/jdk/pull/28469), which can delay virtual-thread timers.
The compiler still targets Java 25. Updating Homebrew's Java does not update independently
installed IntelliJ SDKs or the environment of already-running processes.

The full build runs every test, including the Java/Kotlin downstream projects.
The SDK uses four isolated test JVMs; Test Server and Proxy use two each, and
smaller modules use one. Each JVM has a 768 MiB heap cap. Use `-Dtest.forks=1`
on a smaller machine, or override JVM options with `-Dtest.jvmArgs="-Xmx768m ..."`.
The CI workflows select the latest Temurin 25 patch and use one fork per module for runners with few cores. Each fork runs at most two test classes;
waiting tests do not create additional JUnit workers. Methods remain sequential unless a test explicitly opts into concurrency.
Nested JUnit tests run through their enclosing class, not again as independent
fork roots. Targeted commands such as `./mvnw -pl sdk -am test` continue to run tests.

Test output and XML reports are kept in each module's `target/surefire-reports`;
CI preserves them in the `test-reports` artifact even when the build fails.
Assertion failures and build status remain visible in the Maven console.

## IntelliJ IDEA

Import the root Maven project with a Java 25.0.3+ project SDK. `mise where java` prints the pinned
installation directory; on macOS, select its `Contents/Home` directory as the IntelliJ project SDK.
IntelliJ's own JUnit runner does not execute Maven's version check or automatically use mise's shell environment.
Use IntelliJ's Java compiler and the shared
**All tests** JUnit configuration. It runs the five SDK test modules in one Run tab with one results tree,
a single rerun action, and a single stop action. The fixed project working directory lets IntelliJ use one
JVM for the entire project. Test classes run in parallel across module boundaries. Test resources and model
names must therefore coexist on the same classpath; tests that install global service providers use their own
bounded child JVM to keep those providers out of other tests.

The run uses one test class per available processor, with a bounded worker pool.
Methods remain sequential unless a test explicitly opts into concurrency. The short-lived test JVM uses
`-XX:TieredStopAtLevel=1` to avoid spending its lifetime on higher-tier JIT compilation. Maven and
**All tests (full JIT)** retain normal tiered compilation; use those for validation with the full optimizing JVM
and use separate benchmark configurations for performance measurements.
The shared configurations enable fixture cleanup failure reporting and use a 2 GiB test heap. The shared test configurations disable
IntelliJ's extra async exception stack capture for ordinary Run sessions. Normal exception stack traces and Debug
sessions remain available. Individual module configurations are available in **Module tests** for focused work.
Java/Kotlin downstream artifact checks run through `./mvnw -B install`.

After changing branches, check the active configuration if execution appears sequential. IntelliJ can retain options
that differ from the shared file: each launched test JVM should contain
`-Djunit.jupiter.execution.parallel.enabled=true` and `-Djunit.jupiter.extensions.autodetection.enabled=true`.
If Git worktrees live inside the project directory, mark their parent directory as **Excluded** in IntelliJ.
Otherwise IntelliJ also discovers their shared run configurations, whose identical names can select options from
another checkout. Reopen the project after correcting exclusions and select **All tests** again.
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
