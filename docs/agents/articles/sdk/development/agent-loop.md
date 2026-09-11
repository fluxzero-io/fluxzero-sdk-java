The `fluxzero-dev` MCP server is a read-only view of one active project environment. The dev server, not the coding
agent, owns compilation, application processes, source watching, configured commands, and background tests.

## One edit cycle

1. Call `get_status` before editing and retain `cursor.sessionId` and `cursor.sequence`.
2. Make one coherent source or test change.
3. Call `wait_for_change` with that `sessionId` and `afterSequence`. Inspect the returned events and use its returned
   cursor for the next wait.
4. Keep waiting while relevant work is still starting, running, queued, or pending. The first source-change or
   compile-start event is not a completion signal.
5. For backend work, observe the terminal compile/reload state. If the dev server starts tests, continue from the
   pre-edit cursor until the corresponding `source: test`, `stream: lifecycle` event reaches `passed` or `failed`.
   Corroborate that state with `get_test_status.tests`. A green state or event from before the edit is not evidence
   for the edit. If the server legitimately selects no tests, require stable relevant services and no new active
   problem; do not wait for a synthetic test result or start duplicate tests merely to produce one. Read selective
   verification for the impact-selection and explicit full-suite boundaries.
6. For a frontend-only edit, follow `frontend` events and the frontend service state. The frontend dev server owns its
   rebuild or hot reload, and unrelated backend tests need not run.
7. Before finishing, require stable services and no unresolved problem relevant to the change.

`wait_for_change` returns a bounded delta of matching events. A delta can contain both lifecycle transitions and
process output, so inspect each event's `source` and `stream`, advance the returned cursor, and drain `hasMore` pages.
The tool does not decide the agent's stop condition. Filters can narrow events by `serviceIds`, `instanceIds`, `sources`,
and `minimumLevel`; use broad filters when one edit can cross compile, reload, app, commands, and test pipelines. A
timeout means no matching event arrived in that interval, not that the environment succeeded. Do not fetch logs after
every wait: use a bounded, source-filtered `get_logs` call only when the returned events or an active problem need more
diagnostic detail.

If `sessionChanged` is true, discard the old cursor, inspect the replacement session, and restart the observation from
its current cursor.

## Do not duplicate the owner

While the dev environment is active, do not also run `./mvnw test`, `./gradlew test`, a Spring main class, a local
`TestApp`, another `fz dev`, file-watch builds, or `tail -f`. These compete for ports and build locks, repeat work, and
make causality ambiguous.

One direct wrapper invocation is an explicit fallback when the dev environment is unavailable, tests are disabled or
unmanaged, a release/CI-equivalent run was requested, or a build-system change lies outside the active session's
supported contract. State that fallback instead of silently running both paths.
