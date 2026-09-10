Use the Fluxzero CLI for a new project instead of recreating wrappers, build files, local runtime wiring, and starter tests by hand. The generated project is a generic starting point; it is not the requested application.

## Prepare the tools

Follow the installed Fluxzero agent plugin's onboarding instructions. Verify `fz version`, then use `fz --help`,
`fz init --help` and `fz dev --help` for the installed command surface. `fz upgrade` updates the CLI itself, not
project dependencies. The CLI owns its embedded template snapshot and the dev-server launcher.

## Generate the starter

Choose exactly one language template and one build system:

- `flux-basic-java` for Java.
- `flux-basic-kotlin` for Kotlin.
- `maven` or `gradle` for `--build`.

Prefer non-interactive flags when the task already supplies the answers:

```bash
fz init --in-place \
  --template flux-basic-java \
  --name my-app \
  --package com.example.app \
  --group-id com.example \
  --artifact-id my-app \
  --description "Fluxzero application" \
  --build maven \
  --git
```

For Kotlin with Gradle, change the template to `flux-basic-kotlin` and the build to `gradle`. Run in the exact empty root watched by the agent dev session and retain its pre-init cursor. Omit `--git` when that root already sits inside a Git repository. Never create and move a child project into a watched root, and never initialize over an existing application.

## Satisfy the generated toolchain

The native `fz` command can generate a project without Java, but that does not prove the project can compile or that the development server can start. Inspect the generated `.java-version`, `.tool-versions`, Maven `java.version` and enforcer rule, or Gradle toolchain before invoking a wrapper. Install the declared JDK when it is unavailable; do not rewrite the starter to the host's older Java version.

The generated Maven and Gradle wrappers are the project build tools. Do not spend time or user attention installing system Maven, system Gradle, or an IDE. Read the focused toolchain article for the verification and user-action boundary, then run the selected wrapper's version command once before starting `fluxzero-dev`.

## Select matching SDK documentation

Use the SDK version resolved by the project and documentation for that exact version. Do not upgrade or downgrade a project solely to match a documentation server. Upgrade only when requested or required by the task, after reviewing the intervening migration notes. For a new project, keep the CLI starter's concrete SDK choice. Verify a newly selected release against Fluxzero Packages; an existing resolved project and matching cached documentation do not require a fresh network lookup for every task. Explicit snapshot/local SDK development is a separate supported workflow, not a reason to silently select latest.

## Turn the starter into the requested application

Inspect the complete generated tree before editing. Then:

1. Keep the selected wrapper and build system; remove the unused alternative only when the product or repository convention requires a single build.
2. Replace generic package names, descriptions, example payloads, routes, and tests with the product's terminology and behavior.
3. Remove starter behavior that the product does not need. Do not expose example endpoints merely because they compile.
4. Add only required SDK, authentication, UI, or third-party dependencies.
5. Implement a narrow end-to-end product slice with Fluxzero commands, state, queries, and behavior tests before broadening the surface.
6. Use the Fluxzero dev environment's structured compile/reload/test feedback while editing, inspect served routes or
   API documents when HTTP is promised, and review the final tree for leftover starter names. Use one direct wrapper
   test only for an explicit full-suite/CI boundary or documented fallback.

The generated project contains `.fluxzero/dev.yaml` defaults and small agent instructions. The installed integration's
`fluxzero-dev` stdio bridge runs `fz mcp` and serves documentation before generation. Call `start_dev` when a
development environment is needed; it can start in an empty workspace and reuse that session after generation. Read the
Maven or Gradle article to validate the generated model, the Java or Kotlin article for language-specific source shape,
and the development loop before editing. Local agent manuals are not part of generated projects; retrieve framework
guidance through this MCP server.
