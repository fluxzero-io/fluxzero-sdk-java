Use this after generating a starter or before building an existing project on a new machine. The project declares the required Java toolchain; the machine's initially installed software does not decide the project version.

## Read the declared requirement

Inspect the authoritative build and toolchain files before installing anything:

- `.java-version` and `.tool-versions` provide a quick project-level JDK declaration when present.
- Maven projects normally pin `java.version`, the compiler `release`, and sometimes an enforcer range in `pom.xml`.
- Gradle projects normally pin `JavaLanguageVersion` or `jvmToolchain` in `build.gradle.kts`.

These declarations should agree. For a generated starter, keep them as generated. For an existing Fluxzero project, preserve a supported Java 25-or-newer choice unless the user requested an upgrade. Do not lower a generated project's toolchain merely because `java` is missing or older on the host.

## Install only what is missing

The native Fluxzero CLI is self-contained, so `fz init` can succeed without a JDK. Compilation and `fluxzero-dev` still require the project JDK. Check the active toolchain explicitly:

```bash
java -version
```

If `java` is missing or its major version does not satisfy the project, use an available platform package manager or JDK manager to install the declared stable JDK. Prefer a user-scoped, non-interactive installation when the environment supports it. Discover the installed JDK location and set `JAVA_HOME` for the development session when it is not selected automatically.

Do not install system Maven or system Gradle. The generated `mvnw`/`mvnw.cmd` and `gradlew`/`gradlew.bat` wrappers pin the build tool. An IDE is optional and is not an onboarding prerequisite.

If the only available installation path requires an administrator password, a graphical confirmation, a license decision, or a machine restart, tell the user the exact single action required and stop at that boundary. Do not silently choose a different Java release or replace the wrapper-based build.

## Verify before starting development

Run the version command for the selected build only:

```bash
./mvnw --version
# or
./gradlew --version
```

Confirm that the reported JVM satisfies the project's declaration. Then start or reconnect the agent-owned environment through `fluxzero-dev`; do not start a parallel wrapper build, application, watcher, or test process once that environment is active.
