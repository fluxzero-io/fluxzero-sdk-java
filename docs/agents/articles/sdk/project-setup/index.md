Use this when the user has an empty or existing workspace and wants to build a Fluxzero application. For a new project, install the Fluxzero CLI and generate a supported starter first. Then adapt that generic starter to the product instead of rebuilding wrappers, local runtime wiring, and baseline tests by hand.

Minimum decisions:

- Build tool: Maven or Gradle.
- Language: Java or Kotlin.
- Runtime: Spring Boot app using the Java toolchain declared by the project. Existing supported projects may remain on Java 21 or newer; generated starters pin their own requirement.
- SDK version: use the project's effective Fluxzero BOM and select matching versioned documentation.

For a generated project, keep the starter's declared Java toolchain and install that JDK when it is missing; do not lower the project just because the host starts without Java. For an existing project, keep its chosen Java version when it is supported. Read the focused toolchain article before invoking a wrapper or starting the development server on a new machine.

Use the SDK version resolved by the project and documentation for that exact version. Do not upgrade or downgrade a project solely to match a documentation server. Upgrade only when requested or required by the task, after reviewing the intervening migration notes. For a new project, keep the CLI starter's concrete SDK choice. Verify a newly selected release against Fluxzero Packages; an existing resolved project and matching cached documentation do not require a fresh network lookup for every task. Explicit snapshot/local SDK development is a separate supported workflow, not a reason to silently select latest.

Minimum Fluxzero pieces:

- Import `io.fluxzero:fluxzero-bom` at the exact numeric version selected by the compatibility rule above.
- Add `io.fluxzero:sdk`.
- Enable annotation processing. Java uses `annotationProcessor("io.fluxzero:sdk")` or Maven `annotationProcessorPaths`; Kotlin kapt includes both `io.fluxzero:common` and `io.fluxzero:sdk` under the same BOM.
- Add `io.fluxzero:sdk` with classifier `tests` for local `TestFixture` behavior tests.
- Keep the Spring Boot production main class. Use the Fluxzero dev server for the local runtime, proxy, application
  lifecycle, and affected tests; do not add a test-classpath `TestApp` for the standard path.
- Apply the Fluxzero build plugin included by the starter. It supplies project-local dev launchers; Gradle also uses it
  for the dev-server compile/classpath and selected-test contract.

Optional pieces:

- Configure package publishing only when the project needs it; keep the starter's existing build-plugin integration.
- Add `io.fluxzero.idp:client` and `io.fluxzero.idp:test-support` only when the app uses Fluxzero IDP/BFF login locally.

Recommended agent flow:

1. For a new project, read CLI generation and create the Java or Kotlin starter with the selected build tool.
2. Ensure the JDK declared by the generated project is available; use its wrappers instead of installing Maven or Gradle globally.
3. Read either Maven setup or Gradle setup to validate or repair the generated build. For Maven, read reproducible model guidance before changing the parent/BOM shape.
4. Read Java or Kotlin project shape.
5. Read local runtime and the development loop before starting or observing the development stack.
6. Continue with the create-app recipe, replace starter behavior, then add product commands, queries, and tests.
