Use the Fluxzero dev server for local implementation before cloud deployment. Local work does not require a Fluxzero
Cloud login. From a generated or configured project, a human starts the environment with:

```bash
fz dev
```

The Maven and Gradle plugin launchers are also available:

```bash
./mvnw fluxzero:dev
./gradlew fluxzeroDev
```

An installed coding-agent plugin uses `fz mcp` through its `fluxzero-dev` MCP server. Documentation tools work
without a development environment. Call `start_dev` to start or reuse the background project session. The environment supplies the
embedded runtime, proxy, managed local identity provider, application lifecycle, optional frontend gateway, source
watcher, and affected-test pipeline.

Do not add a `TestApp`, `TestServer`, `ProxyServer`, or wrapper run task merely to make the standard local environment
work. Those belong only to the explicit manual-runtime fallback. Do not run either fallback beside the dev server.

Track stable project choices in `.fluxzero/dev.yaml` and ignore `.fluxzero/dev/`, which contains session state, logs,
diagnostics, and test-impact data. Read the development articles for the agent feedback loop, selective test decisions,
configuration, and diagnostics.

When the user is ready to share a demo URL, move from this local environment to package publishing and cloud deployment.
