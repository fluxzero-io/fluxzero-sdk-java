Use the Fluxzero dev server as the default local development environment. It owns the repeated mechanics around an
application: source watching, compilation, rolling application replacement, the embedded runtime and proxy, an optional
managed frontend, configured startup commands, and affected-test execution.

For a human-operated session, start it with `fz dev`. An installed Fluxzero coding-agent plugin connects through
the `fluxzero-dev` stdio MCP server using `fz mcp`. Documentation is available before development starts;
call `start_dev` to explicitly start or reuse a background project environment. `get_status` only observes it. Do not start another app, wrapper build, test runner, watcher, or
continuous log follower while it is active.

Use the child articles according to the immediate objective:

- Follow the agent loop for cursors, `wait_for_change`, terminal decisions, and fallback boundaries.
- Read selective verification to understand which tests run after backend, test, build, resource, or frontend changes.
- Read configuration when adding a frontend, multiple apps, startup commands, or tracked project defaults.
- Read diagnostics when a compile, reload, app, command, or test pipeline fails.
