Fluxzero packages Java applications as layered OCI images. Publishing is an application delivery step; operating the backing registry is not.

Use the project's Fluxzero build plugin: Maven provides `fluxzero:publish-package`, and Gradle provides
`fluxzeroPublishPackage`. Check the installed plugin's help for version-specific options. Preserve the project's
SDK and plugin versions; verify a newly selected Fluxzero release against Fluxzero Packages. The tools version
is independent of the SDK version.

Before publishing:

- run the application's unit, fixture, integration, and packaging verification;
- use an explicit application/package identity and immutable release identifier;
- keep the Git worktree clean so the artifact is traceable to reviewed source;
- keep credentials out of source, POM files, command output, logs, and generated documentation.

The publishing tool or CI workflow should provide the authenticated destination and credentials. If those inputs are unavailable or rejected, report the failed publishing boundary and stop; do not discover registry internals, inspect protected platform credentials, or change platform permissions.

Record the published immutable artifact reference returned by the supported workflow. Pass that value to the authenticated deployment capability rather than reconstructing a registry URL from undocumented conventions.
