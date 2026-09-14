# Executable OpenAPI resource qualification

Run from the SDK checkout after `./mvnw -B install`:

```bash
bash .github/scripts/test-openapi-packaging.sh
```

Maven arguments are forwarded, so a private repository can be selected with `-Dmaven.repo.local=/absolute/path`.
This independent qualification reactor is not a published SDK module.

The script builds and launches six actual executable JARs: Spring Boot nested dependencies and application-configured
Maven Shade, each with compatible resources, conflicting metadata, and duplicate operation IDs. The launch verifies
resource counts, successful endpoint output and actionable startup conflict errors. The Spring Boot case uses its
real launcher/classloader; the Shade case explicitly appends the OpenAPI resources. Generic application transformer
configuration remains the application's responsibility. No simulated classloader URLs are used here.
