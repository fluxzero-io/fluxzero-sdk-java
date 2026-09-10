Diagnose an active development failure through structured state before reading broad logs:

1. `get_status` identifies the failing compile, reload, app, frontend, command, or tests service and supplies the current
   cursor.
2. `get_active_problems` returns unresolved warnings/errors. Filter by service, concrete app instance, source, or level
   when several applications run together.
3. `get_test_status` reports the current tests and startup-command service states. Read the post-edit `source: test`
   event delta for selection reasons, progress, and failure output.
4. `get_logs` returns a bounded delta after a cursor. Ask for the failing source or instance and only the amount needed
   to identify the cause.
5. Use `wait_for_change` from the latest cursor after a fix. Repeat until the relevant pipelines reach terminal healthy
   states and the problem disappears.

Do not use an unbounded log follower as a substitute for state. A failed new compile or app start intentionally leaves
the last working application available, so a reachable URL alone does not prove the new edit loaded. Likewise, a green
historical test status does not prove a later compile; require a test lifecycle event after the cursor captured before
the edit.

If the local SDK configuration is correct but a managed runtime remains unreachable, retain the structured application,
namespace, error code, message/handler, timestamp, and request/message IDs and escalate through supported platform
status. Application agents should not operate Fluxzero platform internals.
