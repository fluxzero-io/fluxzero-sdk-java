The dev server evaluates test impact after each relevant backend compile. It does not blindly run the complete suite
for every file change.

- A changed test class selects that test class.
- For changed application code, the recorded test-impact index selects tests that previously exercised the changed
  handlers, payloads, schedules, messages, or web operations.
- Previously failing selected tests remain in the next run until they pass.
- When application code has no reliable recorded impact yet, the server safely falls back to module tests.
- Build files and broad resource/configuration changes fall back to module tests.
- A change outside the backend, test, build, and broad-resource scopes can have no affected tests and start no test run.
  In that case the previous tests service status remains visible; do not report it as verification of the new edit.
- A frontend-root change is delegated to the configured frontend dev server. It does not compile the backend or rerun
  backend tests merely to produce activity.

Use the cursor captured before the edit to establish causality. Events with `source: test` describe whether selected
tests or module tests started and why; the corresponding `stream: lifecycle` event reports `running`, `queued`,
`passed`, or `failed`. `get_test_status.tests` corroborates the current service state and broad reason, but it does not
contain a per-edit `latestRun`, selectors, or timestamp. Continue consuming bounded events until the post-edit run is
terminal. If the edit legitimately starts no test run, require stable relevant services and no new active problem
instead of accepting a historical green status or waiting for a synthetic `skipped` result.

The server executes tests; the agent still writes the tests and judges whether their assertions cover the requested
behavior. Automatic selection improves iteration speed but is not a substitute for adding the correct `TestFixture`,
HTTP, WebSocket, authorization, reconstruction, or integration scenarios. Use an explicit full-suite wrapper run only
for a release/CI boundary, a requested exhaustive check, or a documented fallback.
