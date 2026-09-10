Fluxzero Cloud runs tested Fluxzero applications on managed platform services. Application-building agents may assume those services are available and healthy; registry, cluster, database, runtime, and credential administration are platform-operator concerns rather than application APIs.

This MCP exposes documentation, not authenticated cloud actions. Use a separately available authenticated Fluxzero tool or deployment workflow when the user asks to publish or deploy. Never infer private endpoints, credential flows, cluster resources, or platform recovery steps from application symptoms.

Read package publishing to prepare a reproducible application artifact, application deployment for the supported handoff, and deployment verification for application-level acceptance checks.
