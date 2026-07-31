# Local Development

Use each source only for the information it owns:

- The effective Maven or Gradle model defines the SDK version and build
  configuration actually used by this project.
- These synced manuals come from the GitHub release matching that detected SDK
  version and provide version-specific SDK guidance.
- The installed Fluxzero plugin's `fluxzero-docs` MCP server provides current
  SDK concepts and APIs. Compare its advertised `sdkVersion` with the project
  version before applying version-sensitive guidance.
- The installed CLI and the dev-server version it selects are authoritative for
  local-development commands and configuration.
- If manuals are insufficient, inspect
  `https://github.com/fluxzero-io/fluxzero-sdk-java` at the release tag matching
  the project SDK version. Never use `main` for version-specific conclusions.

## Current Command And Configuration Reference

Consult the tools at the point of use:

1. Run `fz --help` when choosing a CLI command.
2. Run `fz dev --help` before starting or controlling a development environment
   when exact actions, options, defaults, or lifecycle behavior matter.
3. Run `fz dev config` before creating or editing `.fluxzero/dev.yaml`. It
   prints the configuration reference aligned with the dev-server version
   selected for the current project.

Inspect an existing `.fluxzero/dev.yaml` before changing it and preserve its
intent. Do not infer flags, YAML keys, defaults, or precedence from these synced
manuals, memory, another project, or a copied example. If any written guidance
differs from command output, command output wins.

## Agent Workflow

When the installed Fluxzero plugin provides `fluxzero-dev`, use that MCP server
as the owner of the local build, test, application, and feedback loop. Do not
start duplicate applications, watchers, builds, tests, or unbounded log
followers beside it. Use direct `fz dev` control only when the plugin is
unavailable or when the user explicitly asks for manual control.

Project-shared, non-sensitive development configuration may be committed in
`.fluxzero/dev.yaml`. Never place actual secret values in project configuration,
logs, or agent output. Follow `fz dev config` for the secret-reference mechanisms
supported by the selected dev-server version, without resolving or printing the
secret values.
