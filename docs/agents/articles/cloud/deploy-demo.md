Deploy only a verified, immutable application artifact. Fluxzero Cloud owns the registry, cluster, runtime, scaling, and rollout mechanics; the application agent owns the code, configuration contract, tests, and observable acceptance criteria.

Use a separately available authenticated Fluxzero deployment tool or the repository's approved delivery workflow. Supply the exact artifact reference returned by publishing and the intended application/environment identifiers. Do not guess internal command types, HTTP endpoints, namespaces, service accounts, credential formats, or cluster objects.

A deployment submission is an asynchronous handoff. Preserve its returned deployment or operation identifier and wait through the supported status interface when one is available. Do not treat command acceptance as proof that the new application is ready.

Keep environment-specific values outside the artifact and validate every required application property at startup. Never include credentials in source, generated configuration, fixture output, or deployment summaries.

After the supported status reaches ready, run the application's public smoke checks from deployment verification. If the platform reports a rollout or infrastructure failure, retain the platform error and hand it to the platform owner instead of attempting undocumented recovery.
