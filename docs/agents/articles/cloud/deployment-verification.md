A deployment request is not the same as a verified application. Use the supported deployment status interface first, then verify only the public application contract.

Keep these measurements separate:

1. **Submission:** the authenticated deployment capability accepted the immutable artifact reference and returned an operation identifier.
2. **Platform status:** that operation reached its documented ready/success state rather than merely remaining accepted or pending.
3. **Application readiness:** the deployed application's public readiness endpoint responds successfully.
4. **Functional smoke:** one representative public command/query or HTTP route satisfies its expected response and produces no application-level error.

Use bounded polling with an explicit timeout when the status interface is asynchronous. Record the operation identifier, terminal public status, deployed application URL, smoke inputs, and smoke outputs. Redact credentials and security-sensitive headers.

Do not inspect or modify registry services, cluster workloads, service accounts, secrets, platform databases, runtime administration endpoints, or internal consumers. If the supported status reports a platform failure, preserve that public diagnostic and escalate it to the platform owner. If status is ready but the public smoke check fails, investigate the application contract, configuration validation, handlers, and application logs that the supported tool exposes.

Re-run the same smoke check after any redeploy. This distinguishes delivery success from application correctness without teaching an application-building agent how to administer Fluxzero's platform.
