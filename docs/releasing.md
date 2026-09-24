# Releasing the SDK

The `Deploy` workflow uploads each release to `https://packages.fluxzero.io/publish/maven`
first, then starts Maven Central publication while its publication window is open. The Central job skips publication
from 1 October 2026 (Europe/Amsterdam); Fluxzero Packages remains the release destination. Public downloads remain at
`https://packages.fluxzero.io/maven`. Release versions, GitHub tags and
container publication retain their existing configuration. Sources, Javadoc and
GPG signatures remain part of both Maven publications.
The existing build job publishes to Fluxzero Packages as its last step. The existing
Central job starts afterward; GitHub releases, Javadoc and the website notification
can continue without waiting for Central.

## Maven commands

- `mvn -P sign deploy` publishes signed artifacts to Fluxzero Packages using
  `distributionManagement`.
- `mvn -P sign,central deploy` uploads signed artifacts to Central for automatic publication and returns after
  upload acceptance (`waitUntil=uploaded`). Central validation and publication continue server-side; a green upload
  is not confirmation that the artifacts are already downloadable there. Workflow reruns retain
  `ignorePublishedComponents=true` for already uploaded immutable components. The date cutoff is enforced by the
  workflow, not by manually invoking this Maven profile.

Sources and Javadoc are attached during packaging. The shared `sign` profile adds
GPG signatures before deployment; both workflow steps enable it. Ordinary builds
and tests need no signing key. Without `sign`, `mvn deploy` still publishes to
Fluxzero Packages, but without signatures.

The standard deploy plugin uses `deployAtEnd=true`: all modules must build before
uploads start. Internal annotation-processor and downstream compatibility projects
are excluded from publication. Uploads are separate operations; a network failure
can leave a partial release. Maven retries transient deployment errors with the
same built files. Changed release bytes are rejected by Fluxzero Packages, so an
interrupted deployment must be recovered with its original files or a new version.
Do not disable immutability to republish rebuilt files or regenerated signatures.

## Authentication

Fluxzero Packages trusts branch and tag workflows in the `fluxzero-io` organization
via GitHub OIDC. The publishing job needs `id-token: write` and requests audience
`https://packages.fluxzero.io/publish/maven` immediately before deployment. Its Maven server
has ID `fluxzero`, username `github-actions` and the short-lived token as password.
There is no long-lived package upload secret. The existing GPG secrets are still
needed for signatures, and the Central job retains its own existing credentials.

For a local packaging check without publishing or signing:

```sh
repository="$(mktemp -d)"
./mvnw -B -Dgpg.skip deploy "-DaltDeploymentRepository=fluxzero::file://$repository"
```

When Central publication is retired, remove the `maven-central` job and the `central`
profile. The normal deploy step and shared signing profile remain in place.
